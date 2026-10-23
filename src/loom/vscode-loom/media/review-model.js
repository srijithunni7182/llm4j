/*
 * The view model of the Loom Review panel: what every layout shares. It takes the review (`weave review --format json`) and the
 * graph (`weave graph`) of one script and works out the findings in the engine's order, which step each belongs to, the counts per
 * step, the summary strip, and how findings are grouped, filtered and counted. No DOM, no imports, so the panel loads it as a script
 * and the tests load it with require. A finding, a count or a fix is worked out here once, so no layout can show another version.
 */
(function (root, factory) {
  var api = factory();
  if (typeof module === 'object' && module.exports) { module.exports = api; }
  root.LoomReview = api;
})(typeof globalThis !== 'undefined' ? globalThis : this, function () {
  'use strict';

  var SEVERITIES = ['high', 'medium', 'low', 'info'];
  var SEVERITY_WORD = { high: 'High', medium: 'Medium', low: 'Low', info: 'Info' };
  var SEVERITY_GLYPH = { high: '!', medium: '!', low: 'i', info: 'i' };
  var LENS_NAMES = {
    security: 'Security', privacy: 'Privacy', durability: 'Durability', reliability: 'Reliability', cost: 'Cost',
    validity: 'Validity', prompts: 'Prompts', observability: 'Observability', testability: 'Testability',
    governance: 'Governance', maintainability: 'Maintainability', oversight: 'Oversight', 'blast-radius': 'Blast radius'
  };
  /** The order lenses are listed in everywhere (the engine's own). */
  var LENS_ORDER = ['security', 'validity', 'cost', 'reliability', 'prompts', 'observability', 'durability', 'testability', 'privacy', 'governance', 'maintainability', 'oversight', 'blast-radius'];

  function has(o, k) { return Object.prototype.hasOwnProperty.call(o, k); }
  function num(n) { return typeof n === 'number' && isFinite(n) ? n : null; }
  function baseName(p) { var parts = String(p || '').split(/[\\/]/); return parts[parts.length - 1]; }
  function lensName(id) { return has(LENS_NAMES, id) ? LENS_NAMES[id] : String(id || ''); }
  function severityRank(s) { var i = SEVERITIES.indexOf(s); return i < 0 ? SEVERITIES.length : i; }

  /* ------------------------------------------------------------------ numbers as a person reads them */

  function formatMoney(m) {
    if (m == null) { return null; }
    if (m === 0) { return '$0'; }
    if (m < 0.01) { return '$' + (Math.round(m * 10000) / 10000).toFixed(4).replace(/0+$/, '').replace(/\.$/, ''); }
    if (m < 1) { return '$' + (Math.round(m * 1000) / 1000).toFixed(3).replace(/0+$/, '').replace(/\.$/, ''); }
    return '$' + m.toFixed(2);
  }
  function formatTokens(n) {
    if (n == null) { return null; }
    if (n < 1000) { return n + ' tok'; }
    if (n < 100000) { return (Math.round(n / 100) / 10) + 'k tok'; }
    return Math.round(n / 1000) + 'k tok';
  }
  function formatMs(ms) {
    if (ms == null) { return null; }
    if (ms < 1000) { return Math.round(ms) + ' ms'; }
    var s = ms / 1000;
    if (s < 10) { return (Math.round(s * 10) / 10) + ' s'; }
    if (s < 120) { return Math.round(s) + ' s'; }
    var m = s / 60;
    if (m < 120) { return Math.round(m) + ' min'; }
    return (Math.round(m / 6) / 10) + ' h';
  }
  /** Money when the model is priced, otherwise tokens (never a made-up price). */
  function costText(c) {
    if (!c) { return null; }
    var money = formatMoney(c.money);
    if (money) { return money; }
    return formatTokens((num(c.tokensIn) || 0) + (num(c.tokensOut) || 0));
  }

  /* ------------------------------------------------------------------ findings */

  function normalizeFinding(f, index) {
    var where = f.location || {};
    var fix = f.fix && typeof f.fix === 'object' ? f.fix : null;
    var options = Array.isArray(f.options) ? f.options : [];
    var safety = fix && (fix.safety === 'safe' || fix.safety === 'review') ? fix.safety : null;
    if (!safety && options.length) {
      safety = options.some(function (o) { return o.safety === 'safe'; }) ? 'safe' : 'review';
    }
    return {
      id: String(f.fingerprint || f.rule + ':' + index),
      index: index,
      rule: String(f.rule || ''),
      lens: String(f.lens || ''),
      lensName: lensName(f.lens),
      severity: has(SEVERITY_WORD, f.severity) ? f.severity : 'info',
      title: String(f.title || ''),
      why: String(f.why || ''),
      fixHint: String(f.fixHint || ''),
      file: String(where.file || ''),
      line: num(where.line) || 0,
      endLine: num(where.endLine) || 0,
      column: num(where.column) || 0,
      endColumn: num(where.endColumn) || 0,
      nodeId: where.node ? String(where.node) : null,
      owasp: Array.isArray(f.owasp) ? f.owasp.map(String) : [],
      source: String(f.source || ''),
      fix: fix ? { safety: fix.safety, label: String(fix.label || ''), edits: Array.isArray(fix.edits) ? fix.edits : [] } : null,
      options: options,
      safety: safety
    };
  }

  /* ------------------------------------------------------------------ the graph side */

  /** The workflow of the graph with this name, or the first. */
  function pickWorkflow(graph, name) {
    var list = graph && Array.isArray(graph.workflows) ? graph.workflows : [];
    return list.filter(function (w) { return w.name === name; })[0] || list[0] || null;
  }

  /** The node a finding belongs to: the one the engine named, else the one whose step starts on the finding's line. */
  function resolveNode(finding, workflow) {
    if (!workflow) { return null; }
    var nodes = (workflow.nodes || []).filter(function (n) { return n.kind !== 'start' && n.kind !== 'end'; });
    if (finding.nodeId) {
      var named = nodes.filter(function (n) { return n.id === finding.nodeId; })[0];
      if (named) { return named.id; }
    }
    if (finding.line > 0) {
      var same = nodes.filter(function (n) { return n.source && n.source.line === finding.line && (!finding.file || baseName(n.source.file) === baseName(finding.file) || !n.source.file); })[0];
      if (same) { return same.id; }
    }
    return null;
  }

  function costOfStep(cost, workflowName, node) {
    if (!cost || !Array.isArray(cost.workflows) || !node || !node.source) { return null; }
    var wf = cost.workflows.filter(function (w) { return w.name === workflowName; })[0];
    if (!wf || !Array.isArray(wf.steps)) { return null; }
    var step = wf.steps.filter(function (s) { return s.line === node.source.line; })[0];
    return step ? { typical: step.typical, worst: step.worst, label: step.label, model: step.model, typicalRuns: step.typicalRuns } : null;
  }

  /* ------------------------------------------------------------------ the model */

  /**
   * @param review the JSON of `weave review --format json` (or null while the first run is under way)
   * @param graph the JSON of `weave graph`
   * @param options {workflow: name}
   */
  function build(review, graph, options) {
    options = options || {};
    var wfGraph = pickWorkflow(graph, options.workflow);
    var findings = (review && Array.isArray(review.findings) ? review.findings : []).map(normalizeFinding);
    findings.forEach(function (f) { f.node = resolveNode(f, wfGraph); });

    var counts = { high: 0, medium: 0, low: 0, info: 0 };
    findings.forEach(function (f) { counts[f.severity] += 1; });

    var nodes = Object.create(null);
    function slot(id) {
      if (!nodes[id]) { nodes[id] = { id: id, counts: { high: 0, medium: 0, low: 0, info: 0 }, findings: [], cost: null, total: 0 }; }
      return nodes[id];
    }
    findings.forEach(function (f) {
      if (!f.node) { return; }
      var n = slot(f.node);
      n.counts[f.severity] += 1; n.total += 1; n.findings.push(f.id);
    });
    if (wfGraph) {
      (wfGraph.nodes || []).forEach(function (gn) {
        var c = costOfStep(review && review.cost, wfGraph.name, gn);
        if (c) { slot(gn.id).cost = c; }
      });
    }

    var workflowCost = null;
    if (review && review.cost && Array.isArray(review.cost.workflows)) {
      workflowCost = review.cost.workflows.filter(function (w) { return wfGraph && w.name === wfGraph.name; })[0] || review.cost.workflows[0] || null;
    }
    var summary = summarize(review, counts, workflowCost, findings);

    return {
      file: review ? review.file : '',
      workflows: graph && Array.isArray(graph.workflows) ? graph.workflows.map(function (w) { return w.name; }) : [],
      workflow: wfGraph ? wfGraph.name : null,
      graph: wfGraph,
      findings: findings,
      byId: findings.reduce(function (m, f) { m[f.id] = f; return m; }, Object.create(null)),
      nodes: nodes,
      counts: counts,
      open: findings.length,
      handled: handledOf(review),
      notEvaluated: (review && Array.isArray(review.notes) ? review.notes : []).filter(function (n) { return n.kind === 'not_evaluated'; }),
      notes: review && Array.isArray(review.notes) ? review.notes : [],
      summary: summary
    };
  }

  function handledOf(review) {
    var s = review && review.summary ? review.summary : {};
    return { accepted: num(s.accepted) || 0, dismissed: 0, baselined: num(s.baselined) || 0 };
  }

  function summarize(review, counts, wf, findings) {
    var lenses = review && review.summary && review.summary.lenses ? review.summary.lenses : { ran: [], off: [] };
    var out = {
      counts: counts,
      countsText: SEVERITIES.filter(function (s) { return counts[s] > 0; }).map(function (s) { return counts[s] + ' ' + s; }).join(' · ') || 'no open findings',
      cost: null, time: null, effects: null,
      lenses: { ran: lenses.ran || [], off: lenses.off || [] },
      trifecta: findings.some(function (f) { return f.rule === 'LA01'; }),
      notEvaluated: (review && Array.isArray(review.notes) ? review.notes : []).filter(function (n) { return n.kind === 'not_evaluated'; }).length
    };
    if (wf) {
      var unpriced = (wf.typical && wf.typical.unpriced) || [];
      out.cost = {
        typical: costText(wf.typical), worst: costText(wf.worst),
        typicalMoney: wf.typical ? wf.typical.money : null, worstMoney: wf.worst ? wf.worst.money : null,
        priced: unpriced.length === 0 && wf.typical && wf.typical.money != null,
        unpriced: unpriced,
        calls: wf.typical ? wf.typical.calls : null, worstCalls: wf.worst ? wf.worst.calls : null,
        bound: (wf.notes || []).join('; ')
      };
      var t = wf.time;
      if (t) {
        out.time = {
          typical: formatMs(t.typicalMs),
          worst: t.worst && t.worst.ms != null ? formatMs(t.worst.ms) : (t.worst && t.worst.unbounded ? 'unbounded' : null),
          waits: Array.isArray(t.waits) ? t.waits : [], sleepMs: t.sleepMs || 0
        };
      }
      var e = wf.effects;
      if (e) {
        var sends = e.sends ? e.sends.worst : 0, writes = e.writes ? e.writes.worst : 0;
        out.effects = { sends: sends, writes: writes, text: (sends || writes) ? [sends ? sends + (sends === 1 ? ' send' : ' sends') : null, writes ? writes + (writes === 1 ? ' write' : ' writes') : null].filter(Boolean).join(', ') : 'none' };
      }
    }
    return out;
  }

  /* ------------------------------------------------------------------ filtering and grouping */

  /**
   * The findings that pass the filters, in the engine's order. `filters`: {text, lenses: [ids], severities: [names], node: id,
   * rules: [ids], files: [names]}; an empty or missing filter lets everything through.
   */
  function filterFindings(findings, filters) {
    var f = filters || {};
    var text = String(f.text || '').trim().toLowerCase();
    return findings.filter(function (x) {
      if (f.node && x.node !== f.node) { return false; }
      if (f.lenses && f.lenses.length && f.lenses.indexOf(x.lens) < 0) { return false; }
      if (f.severities && f.severities.length && f.severities.indexOf(x.severity) < 0) { return false; }
      if (f.rules && f.rules.length && f.rules.indexOf(x.rule) < 0) { return false; }
      if (f.files && f.files.length && f.files.indexOf(baseName(x.file)) < 0) { return false; }
      if (!text) { return true; }
      return [x.title, x.why, x.rule, x.lensName, x.file, x.fixHint].join('\n').toLowerCase().indexOf(text) >= 0;
    });
  }

  function groupOf(by, f) {
    switch (by) {
      case 'lens': return { key: f.lens, label: f.lensName };
      case 'rule': return { key: f.rule, label: f.rule };
      case 'file': return { key: baseName(f.file) || '(script)', label: baseName(f.file) || '(script)' };
      default: return { key: f.severity, label: SEVERITY_WORD[f.severity] };
    }
  }

  /**
   * Findings in groups: by severity (default), lens, rule or file. With `by` severity the groups are split again by lens (and the
   * other way round for lens), as the panel shows them. Order: severity high to info; lenses in the engine's order; rules and files
   * by first appearance. Each group says how many findings it holds and how many have a safe fix.
   */
  function group(findings, by, nested) {
    by = by || 'severity';
    var order = [], map = Object.create(null);
    findings.forEach(function (f) {
      var g = groupOf(by, f);
      if (!map[g.key]) { map[g.key] = { key: g.key, label: g.label, by: by, findings: [] }; order.push(g.key); }
      map[g.key].findings.push(f);
    });
    if (by === 'severity') { order.sort(function (a, b) { return severityRank(a) - severityRank(b); }); }
    if (by === 'lens') { order.sort(function (a, b) { return LENS_ORDER.indexOf(a) - LENS_ORDER.indexOf(b); }); }
    return order.map(function (k) {
      var g = map[k];
      g.count = g.findings.length;
      g.safe = g.findings.filter(function (f) { return f.safety === 'safe'; }).length;
      g.severity = by === 'severity' ? g.key : worst(g.findings);
      if (!nested && (by === 'severity' || by === 'lens')) {
        var sub = by === 'severity' ? 'lens' : 'severity';
        g.sub = group(g.findings, sub, true).map(function (s) { return { key: s.key, label: s.label, count: s.count, safe: s.safe, findings: s.findings, by: sub, severity: s.severity }; });
      }
      return g;
    });
  }

  function worst(findings) {
    return findings.reduce(function (w, f) { return severityRank(f.severity) < severityRank(w) ? f.severity : w; }, 'info');
  }

  /** "Fix all safe (n of m)": how many of these have a safe fix, of how many. */
  function safeCount(findings) {
    return { safe: findings.filter(function (f) { return f.safety === 'safe'; }).length, total: findings.length };
  }

  /* ------------------------------------------------------------------ the lens rail */

  /** One entry per lens, in the engine's order: on or off, and how many open findings it has. */
  function lensRail(model) {
    var ran = model.summary.lenses.ran, off = model.summary.lenses.off;
    var all = LENS_ORDER.filter(function (l) { return ran.indexOf(l) >= 0 || off.indexOf(l) >= 0; });
    return all.map(function (l) {
      return { id: l, name: lensName(l), on: ran.indexOf(l) >= 0, count: model.findings.filter(function (f) { return f.lens === l; }).length };
    });
  }

  /* ------------------------------------------------------------------ the finding on a line */

  /** The finding ids whose line (or range) holds this line, for "the cursor moved". */
  function findingsOnLine(model, file, line) {
    return model.findings.filter(function (f) {
      if (f.line <= 0) { return false; }
      if (file && f.file && baseName(file) !== baseName(f.file)) { return false; }
      return line >= f.line && line <= Math.max(f.line, f.endLine);
    }).map(function (f) { return f.id; });
  }

  /** The node whose step starts on the line, or the last step that starts before it (the one the cursor is inside). */
  function nodeOnLine(model, file, line) {
    if (!model.graph) { return null; }
    var best = null;
    (model.graph.nodes || []).forEach(function (n) {
      if (!n.source || n.kind === 'start' || n.kind === 'end') { return; }
      if (file && n.source.file && baseName(n.source.file) !== baseName(file)) { return; }
      if (n.source.line <= line && (!best || n.source.line >= best.source.line)) { best = n; }
    });
    return best ? best.id : null;
  }

  /** The layouts a panel offers. Split is the default. */
  var LAYOUTS = ['split', 'outline', 'list'];
  var GROUPS = ['severity', 'lens', 'rule', 'file'];

  /** The choices a person makes in the panel and expects to survive a switch of layout, a hide or a reload. */
  function defaultUi() {
    return {
      layout: 'split', pane: 'both', groupBy: 'severity',
      filter: { text: '', lenses: [], severities: [] },
      node: null, selected: null, multi: [], collapsed: {}, scroll: 0,
      showNotEvaluated: false, showHandled: false, workflow: null
    };
  }

  /** Reads saved state defensively: anything of the wrong shape falls back to the default for that key. */
  function restoreUi(saved) {
    var ui = defaultUi();
    if (!saved || typeof saved !== 'object') { return ui; }
    if (LAYOUTS.indexOf(saved.layout) >= 0) { ui.layout = saved.layout; }
    if (['findings', 'both', 'graph'].indexOf(saved.pane) >= 0) { ui.pane = saved.pane; }
    if (GROUPS.indexOf(saved.groupBy) >= 0) { ui.groupBy = saved.groupBy; }
    if (saved.filter && typeof saved.filter === 'object') {
      ui.filter.text = typeof saved.filter.text === 'string' ? saved.filter.text : '';
      ui.filter.lenses = Array.isArray(saved.filter.lenses) ? saved.filter.lenses.filter(function (x) { return typeof x === 'string'; }) : [];
      ui.filter.severities = Array.isArray(saved.filter.severities) ? saved.filter.severities.filter(function (x) { return SEVERITIES.indexOf(x) >= 0; }) : [];
    }
    ['node', 'selected', 'workflow'].forEach(function (k) { if (typeof saved[k] === 'string') { ui[k] = saved[k]; } });
    if (Array.isArray(saved.multi)) { ui.multi = saved.multi.filter(function (x) { return typeof x === 'string'; }); }
    if (saved.collapsed && typeof saved.collapsed === 'object') {
      Object.keys(saved.collapsed).forEach(function (k) { if (saved.collapsed[k] === true) { ui.collapsed[k] = true; } });
    }
    if (typeof saved.scroll === 'number' && saved.scroll >= 0) { ui.scroll = saved.scroll; }
    ui.showNotEvaluated = saved.showNotEvaluated === true;
    ui.showHandled = saved.showHandled === true;
    return ui;
  }

  /**
   * Everything a layout draws, worked out once and independent of the layout: the findings that pass the filters, their groups, the
   * counts, the selected finding and step. A layout may arrange this, never change it, so a finding, a count or a fix cannot differ
   * between layouts.
   */
  function view(model, ui) {
    var shown = filterFindings(model.findings, { text: ui.filter.text, lenses: ui.filter.lenses, severities: ui.filter.severities, node: ui.node });
    var counts = { high: 0, medium: 0, low: 0, info: 0 };
    shown.forEach(function (f) { counts[f.severity] += 1; });
    var selected = ui.selected && model.byId[ui.selected] ? model.byId[ui.selected] : null;
    var selectedNode = ui.node || (selected ? selected.node : null);
    return {
      shown: shown,
      shownIds: shown.map(function (f) { return f.id; }),
      groups: group(shown, ui.groupBy),
      counts: counts,
      safe: safeCount(shown),
      selected: selected,
      selectedNode: selectedNode,
      total: model.findings.length,
      filtered: shown.length !== model.findings.length,
      strip: model.summary,
      rail: lensRail(model)
    };
  }

  /** The steps of a workflow in the order the outline lists them, with their findings counted, for the Outline and List views. */
  function stepsOf(model, workflowModel) {
    var out = [];
    (workflowModel ? workflowModel.nodes : []).forEach(function (n) {
      var info = model.nodes[n.id];
      out.push({ id: n.id, label: n.label, kind: n.kind, vk: n.vk, line: n.line, depth: n.depth, section: n.section, agent: n.agent,
        counts: info ? info.counts : { high: 0, medium: 0, low: 0, info: 0 }, total: info ? info.total : 0, cost: info ? info.cost : null });
    });
    return out;
  }

  /** What a screen reader says for a finding: severity, lens, whether there is a fix. */
  function describe(f) {
    var fix = f.safety === 'safe' ? 'safe fix available' : f.safety === 'review' ? 'fix to review available' : 'no automatic fix';
    return SEVERITY_WORD[f.severity] + ', ' + f.lensName + ', ' + fix + ': ' + f.title;
  }

  return {
    SEVERITIES: SEVERITIES, SEVERITY_WORD: SEVERITY_WORD, SEVERITY_GLYPH: SEVERITY_GLYPH, LENS_ORDER: LENS_ORDER,
    build: build, filterFindings: filterFindings, group: group, safeCount: safeCount, lensRail: lensRail,
    findingsOnLine: findingsOnLine, nodeOnLine: nodeOnLine, describe: describe, resolveNode: resolveNode,
    LAYOUTS: LAYOUTS, GROUPS: GROUPS, defaultUi: defaultUi, restoreUi: restoreUi, view: view, stepsOf: stepsOf,
    formatMoney: formatMoney, formatTokens: formatTokens, formatMs: formatMs, costText: costText, lensName: lensName, baseName: baseName
  };
});
