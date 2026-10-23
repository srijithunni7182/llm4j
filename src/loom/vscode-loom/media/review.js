/*
 * The Loom Review panel page. One review model, three layouts (Split, Outline, List) that arrange the same data: the summary strip,
 * the filter and grouping, the findings and the lens rail are shared, and the choices a person makes survive a switch of layout. Every
 * piece of text from a script or a finding is set with textContent; no markup is ever built from it. Plain JavaScript, no imports.
 */
(function () {
  'use strict';
  var R = window.LoomReview, M = window.LoomModel, V = window.LoomViews;
  var vscode = typeof acquireVsCodeApi === 'function' ? acquireVsCodeApi() : { postMessage: function () {}, getState: function () { return null; }, setState: function () {} };

  var data = { review: null, graph: null, status: 'loading', message: '', entry: null };
  var model = R.build(null, null);
  var ui = R.restoreUi(savedState());

  function savedState() { try { var s = vscode.getState(); return s && s.ui ? s.ui : null; } catch (e) { return null; } }
  function save() { try { vscode.setState({ ui: ui, entry: data.entry || null }); } catch (e) { /* the page state is a convenience */ } }

  /* ----------------------------------------------------------------- small DOM helpers (text only) */

  function el(tag, attrs) {
    var node = document.createElement(tag);
    Object.keys(attrs || {}).forEach(function (k) {
      var v = attrs[k];
      if (v == null || v === false) { return; }
      if (k === 'text') { node.textContent = v; }
      else if (k === 'class') { node.className = v; }
      else if (k === 'style') { String(v).split(';').forEach(function (d) { var i = d.indexOf(':'); if (i > 0) { node.style.setProperty(d.slice(0, i).trim(), d.slice(i + 1).trim()); } }); }
      else if (k.slice(0, 2) === 'on' && typeof v === 'function') { node.addEventListener(k.slice(2), v); }
      else { node.setAttribute(k, v === true ? '' : String(v)); }
    });
    for (var i = 2; i < arguments.length; i++) { add(node, arguments[i]); }
    return node;
  }
  function add(node, child) {
    if (child == null || child === false) { return; }
    if (Array.isArray(child)) { child.forEach(function (c) { add(node, c); }); return; }
    node.appendChild(typeof child === 'string' ? document.createTextNode(child) : child);
  }
  function $(id) { return document.getElementById(id); }
  function clear(node) { while (node.firstChild) { node.removeChild(node.firstChild); } }
  function sevClass(s) { return 's-' + (s === 'medium' ? 'med' : s); }
  function sevMark(s) { return el('span', { class: 'sev ' + sevClass(s) }, el('i', { 'aria-hidden': 'true', text: R.SEVERITY_GLYPH[s] }), R.SEVERITY_WORD[s]); }
  function post(message) { vscode.postMessage(message); }
  function cssEscape(s) { return String(s).replace(/["\\]/g, '\\$&'); }
  function toggle(list, value) { var i = list.indexOf(value); if (i >= 0) { list.splice(i, 1); } else { list.push(value); } }
  function nodeLabel(id) { var n = model.graph && model.graph.nodes.filter(function (x) { return x.id === id; })[0]; return n ? n.label : id; }
  function badgeOf(vk, size) {
    var k = M.VK[vk] || M.VK.unknown;
    var b = el('span', { class: 'badge' + (size ? ' s22' : '') + (k.shape === 'rhombus' ? ' rh' : ''), 'aria-hidden': 'true' });
    b.appendChild(V.svg(k.glyph));
    return b;
  }
  function tone(node, vk) { var k = M.VK[vk] || M.VK.unknown; node.style.setProperty('--h', k.h); node.style.setProperty('--c', k.c); return node; }

  /* ----------------------------------------------------------------- the shared controls */

  function renderToolbar() {
    var select = $('workflow');
    clear(select);
    model.workflows.forEach(function (name) { select.appendChild(el('option', { value: name, text: name + '(' + paramsOf(name) + ')' })); });
    if (model.workflow) { select.value = model.workflow; }
    select.disabled = model.workflows.length < 2;
    $('runbudget').hidden = true;
    [['layoutSplit', 'split'], ['layoutOutline', 'outline'], ['layoutList', 'list']].forEach(function (p) { $(p[0]).setAttribute('aria-pressed', String(ui.layout === p[1])); });
    // the pane switch belongs to Split alone: Outline and List are single-flow layouts
    $('paneSwitch').hidden = ui.layout !== 'split';
    ['paneFindings', 'paneBoth', 'paneGraph'].forEach(function (id) { var b = $(id); b.setAttribute('aria-pressed', String(b.getAttribute('data-pane') === ui.pane)); });
    var safe = R.safeCount(model.findings);
    $('fixAll').textContent = 'Fix all safe (' + safe.safe + ' of ' + safe.total + ')';
    $('fixAll').hidden = true; // fixes arrive with the triage actions
    $('acceptAll').hidden = true;
  }

  function paramsOf(name) {
    var w = (data.graph && data.graph.workflows || []).filter(function (x) { return x.name === name; })[0];
    return w && w.params ? w.params.join(', ') : '';
  }

  function stat(label, value, small) {
    return el('div', { class: 'stat' }, el('span', { class: 'kicker', text: label }), el('span', { class: 'v' }, value, small ? [' ', el('small', { text: small })] : null));
  }

  function renderStrip() {
    var s = model.summary, strip = $('strip');
    clear(strip);
    strip.appendChild(stat('Open findings', s.countsText));
    if (s.cost) {
      var small = s.cost.priced ? s.cost.bound : 'tokens: no prices for ' + (s.cost.unpriced.length ? s.cost.unpriced.join(', ') : 'this run');
      strip.appendChild(stat('Cost per run', (s.cost.typical || 'unknown') + ' typical · ' + (s.cost.worst || 'unknown') + ' worst', small));
    }
    if (s.time) { strip.appendChild(stat('Time', (s.time.typical || '?') + ' typical · ' + (s.time.worst || '?') + ' worst', s.time.waits && s.time.waits.length ? '+ waits: ' + s.time.waits.length : '')); }
    if (s.effects) { strip.appendChild(stat('Effects', s.effects.text, 'worst case per run')); }
    strip.appendChild(stat('Lenses', s.lenses.ran.length + ' on · ' + s.lenses.off.length + ' optional off'));
    var end = el('div', { class: 'stat end' });
    if (s.trifecta) { end.appendChild(el('span', { class: 'chip warn', text: 'trifecta present' })); }
    if (s.notEvaluated > 0) {
      end.appendChild(el('button', { class: 'link', type: 'button', 'data-act': 'not-evaluated', text: s.notEvaluated + (s.notEvaluated === 1 ? ' rule' : ' rules') + ' not evaluated', onclick: function () { ui.showNotEvaluated = !ui.showNotEvaluated; save(); renderAll(); } }));
    }
    strip.appendChild(end);
  }

  function renderControls(v) {
    $('filter').value = ui.filter.text;
    ['groupSeverity', 'groupLens', 'groupRule', 'groupFile'].forEach(function (id) { var b = $(id); b.setAttribute('aria-pressed', String(b.getAttribute('data-group') === ui.groupBy)); });
    $('fcount').textContent = v.filtered ? v.shown.length + ' of ' + v.total + ' open' : v.total + ' open';
    var clearNode = $('clearNode');
    clearNode.hidden = !ui.node;
    if (ui.node) { clearNode.textContent = 'Step: ' + nodeLabel(ui.node) + ' ✕'; clearNode.setAttribute('aria-label', 'Showing only the findings of ' + nodeLabel(ui.node) + '. Show all.'); }
    $('showHandled').hidden = true;
    var ne = $('showNotEvaluated');
    ne.hidden = model.summary.notEvaluated === 0;
    ne.textContent = (ui.showNotEvaluated ? 'Hide' : 'Show') + ' not evaluated (' + model.summary.notEvaluated + ')';
  }

  /* ----------------------------------------------------------------- findings (shared by every layout) */

  function firstShownId(v) { return v.shown.length ? v.shown[0].id : null; }

  function where(f) { return f.node ? nodeLabel(f.node) : (f.line > 0 ? 'line ' + f.line : 'whole script'); }

  function diffOf(f) {
    var box = el('div', { class: 'diff', 'aria-label': 'What the fix changes' }, el('div', { class: 'dh', text: (f.fix.label || 'The fix') + ' · ' + f.fix.edits.length + (f.fix.edits.length === 1 ? ' edit' : ' edits') }));
    f.fix.edits.slice(0, 6).forEach(function (e) {
      var text = String(e.newText || '');
      if (!text) { box.appendChild(el('div', { class: 'd', text: '-  (removes lines ' + e.startLine + '–' + e.endLine + ')' })); return; }
      text.split('\n').slice(0, 8).forEach(function (line) { box.appendChild(el('div', { class: 'a', text: '+' + line })); });
    });
    return box;
  }

  /** Why, what would fix it, and what the fix changes: the same in every layout that shows a finding opened. */
  function detailOf(f) {
    var parts = [];
    if (f.why) { parts.push(el('div', { class: 'fd-why', text: f.why })); }
    var hint = f.fixHint || (f.safety ? '' : 'No automatic fix.');
    if (hint) { parts.push(el('div', { class: 'fd-acts' }, el('span', { class: 'fx', text: (f.safety === 'safe' ? 'safe fix · ' : f.safety === 'review' ? 'fix to review · ' : '') + hint }))); }
    if (f.fix && f.fix.edits && f.fix.edits.length) { parts.push(diffOf(f)); }
    return parts;
  }

  function revealButton(f) {
    return el('button', { class: 'ghost', type: 'button', 'data-act': 'reveal', title: 'Show this in the editor', text: where(f) + (f.file ? ' · ' + R.baseName(f.file) + (f.line > 0 ? ':' + f.line : '') : ''), onclick: function (e) { e.stopPropagation(); reveal(f); } });
  }

  function findingCard(f, v) {
    var selected = ui.selected === f.id;
    var row = el('article', { class: 'fd ' + sevClass(f.severity) + (selected ? ' sel' : ''), 'data-finding': f.id, role: 'option', tabindex: selected || (!ui.selected && f.id === firstShownId(v)) ? '0' : '-1', 'aria-selected': String(selected), 'aria-label': R.describe(f) });
    var main = el('div', { class: 'fd-main' }, el('div', { class: 'fd-top' }, sevMark(f.severity), el('span', { class: 'chip rule', text: f.rule }), el('span', { class: 'chip lens', text: f.lensName }), revealButton(f)), el('div', { class: 'fd-title', text: f.title }));
    if (selected) { add(main, detailOf(f)); }
    row.appendChild(main);
    row.addEventListener('click', function () { selectFinding(f.id, true); });
    row.addEventListener('keydown', onRowKey);
    return row;
  }

  function findingRow(f, v) {
    var selected = ui.selected === f.id;
    var row = el('div', { class: 'fd trow ' + sevClass(f.severity) + (selected ? ' sel' : ''), 'data-finding': f.id, role: 'option', tabindex: selected || (!ui.selected && f.id === firstShownId(v)) ? '0' : '-1', 'aria-selected': String(selected), 'aria-label': R.describe(f) },
      sevMark(f.severity), el('span', { class: 'mono rulecol', text: f.rule }), el('span', { class: 'chip lens', text: f.lensName }), el('span', { class: 'ttl', text: f.title, title: f.title }),
      el('span', { class: 'where', text: where(f) + (f.line > 0 ? ' · ' + R.baseName(f.file) + ':' + f.line : '') }));
    row.addEventListener('click', function () { selectFinding(f.id, true); });
    row.addEventListener('keydown', onRowKey);
    return row;
  }

  function groupHeader(g, level) {
    var key = level + ':' + g.key;
    var closed = !!ui.collapsed[key];
    var head = el('div', { class: (level === 0 ? 'gh ' : 'subgh ') + sevClass(g.severity || 'info'), role: 'heading', 'aria-level': String(level + 2) });
    head.appendChild(el('button', { class: 'twist', type: 'button', 'aria-expanded': String(!closed), 'aria-label': (closed ? 'Expand ' : 'Collapse ') + g.label, text: closed ? '▸' : '▾', onclick: function () { ui.collapsed[key] = !closed; save(); renderAll(); } }));
    if (g.by === 'severity') { head.appendChild(sevMark(g.key)); } else { head.appendChild(el('b', { text: g.label })); }
    head.appendChild(el('span', { class: 'cnt', text: String(g.count) }));
    return head;
  }

  /** The grouped findings into `container`, as cards or as table rows. */
  function fillFindings(container, v, variant) {
    var make = variant === 'table' ? findingRow : findingCard;
    if (!v.shown.length) {
      container.appendChild(el('div', { class: 'empty' }, model.findings.length ? [el('b', { text: 'No finding matches. ' }), 'Clear the filter to see all ' + model.findings.length + '.'] : [el('b', { text: data.status === 'loading' ? 'Reviewing…' : 'No open findings.' }), data.status === 'loading' ? '' : ' Nothing in this script needs your attention.']));
    } else {
      v.groups.forEach(function (g) {
        container.appendChild(groupHeader(g, 0));
        if (ui.collapsed['0:' + g.key]) { return; }
        if (g.sub) {
          g.sub.forEach(function (s) {
            container.appendChild(groupHeader(s, 1));
            if (!ui.collapsed['1:' + s.key]) { s.findings.forEach(function (f) { container.appendChild(make(f, v)); }); }
          });
        } else {
          g.findings.forEach(function (f) { container.appendChild(make(f, v)); });
        }
      });
    }
    if (ui.showNotEvaluated && model.notEvaluated.length) {
      container.appendChild(el('div', { class: 'notes' }, el('div', { class: 'kicker', text: 'Not evaluated' }), el('ul', null, model.notEvaluated.map(function (n) { return el('li', { text: n.message }); }))));
    }
  }

  function handledBox() {
    var h = model.handled;
    return el('div', { class: 'handled', id: 'handled' }, el('span', { class: 'kicker', text: 'Handled' }), el('span', { text: h.accepted + ' accepted' }), el('span', { class: 'muted', text: '·' }), el('span', { text: h.dismissed + ' dismissed' }), el('span', { class: 'muted', text: '·' }), el('span', { text: h.baselined + ' baselined' }));
  }

  /* ----------------------------------------------------------------- the graph (Split) */

  function nodeBadges(nodeId) {
    var n = model.nodes[nodeId];
    if (!n || !n.total) { return null; }
    var wrap = el('span', { class: 'nsev' });
    ['high', 'medium', 'low', 'info'].forEach(function (s) {
      if (!n.counts[s]) { return; }
      wrap.appendChild(el('span', { class: 'np ' + sevClass(s), title: n.counts[s] + ' ' + s, 'aria-label': n.counts[s] + ' ' + s + ' findings' }, el('i', { 'aria-hidden': 'true', text: R.SEVERITY_GLYPH[s] }), String(n.counts[s])));
    });
    return wrap;
  }

  function graphCard(vm, v) {
    var vk = M.VK[vm.vk] || M.VK.unknown;
    var isSel = v.selectedNode === vm.id;
    var card = tone(el('div', { class: 'card k-' + vm.vk + (vm.kind === 'start' || vm.kind === 'end' ? ' term' : '') + (isSel ? ' sel' : '') + (ui.node && ui.node !== vm.id ? ' dim' : ''), 'data-node': vm.id, role: 'button', tabindex: '0', 'aria-pressed': String(isSel) }), vm.vk);
    var count = model.nodes[vm.id] ? model.nodes[vm.id].total : 0;
    card.setAttribute('aria-label', vm.label + (count ? ', ' + count + ' open findings' : ''));
    if (vm.kind === 'start' || vm.kind === 'end') {
      card.appendChild(el('span', { class: 't', text: vm.kind === 'start' ? 'Start' : 'End' }));
    } else {
      add(card, nodeBadges(vm.id));
      card.appendChild(badgeOf(vm.vk, false));
      card.appendChild(el('div', { class: 'body' }, el('div', { class: 'r1' }, el('span', { class: 't', text: vm.label }), vm.line ? el('span', { class: 'ln', text: ':' + vm.line }) : null), el('div', { class: 'sub', text: vm.agent ? vk.name + ' ' + vm.agent : vk.name })));
      var cost = model.nodes[vm.id] && model.nodes[vm.id].cost;
      if (cost && cost.typical) { card.appendChild(el('span', { class: 'cost', title: 'typical cost of one run of this step' + (cost.worst ? ', worst ' + R.costText(cost.worst) : ''), text: R.costText(cost.typical) })); }
    }
    card.addEventListener('click', function () { selectNode(vm.id); });
    card.addEventListener('keydown', function (e) { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); selectNode(vm.id); } });
    return card;
  }

  function renderGraph(pane, v) {
    if (!model.graph) {
      pane.appendChild(el('div', { class: 'empty' }, el('b', { text: 'No graph yet. ' }), data.status === 'loading' ? 'Reading the script…' : 'The script could not be drawn; see the message above.'));
      return;
    }
    var vm = M.build(model.graph, { workflows: data.graph && data.graph.workflows });
    var onMain = vm.main.length ? vm.main : vm.nodes.map(function (n) { return n.id; });
    var ordered = vm.nodes.filter(function (n) { return onMain.indexOf(n.id) >= 0 || n.kind === 'start' || n.kind === 'end'; });
    if (!ordered.length) { ordered = vm.nodes; }
    var entry = el('div', { class: 'entry' });
    ordered.forEach(function (n, i) {
      if (i > 0) { entry.appendChild(el('div', { class: 'conn', 'aria-hidden': 'true' })); }
      entry.appendChild(el('div', { role: 'listitem', style: 'width:100%' }, graphCard(n, v)));
    });
    pane.appendChild(el('div', { class: 'gstack', role: 'list', 'aria-label': 'Workflow steps' }, entry));
    var off = vm.nodes.filter(function (n) { return ordered.indexOf(n) < 0; });
    if (off.length) {
      pane.appendChild(el('div', { class: 'gstack', role: 'list', 'aria-label': 'Steps off the main path' }, el('div', { class: 'entry' }, el('div', { class: 'kicker', text: 'Off the main path' }), off.map(function (n) { return el('div', { role: 'listitem', style: 'width:100%;margin-top:8px' }, graphCard(n, v)); }))));
    }
    pane.appendChild(el('div', { class: 'legendbox' },
      el('span', { class: 'kicker', text: 'How to read the graph' }),
      el('span', { style: 'display:flex;gap:8px;align-items:center' }, el('span', { class: 'np s-med' }, el('i', { 'aria-hidden': 'true', text: '!' }), '3'), el('span', { text: 'open findings on a step, by severity' })),
      el('span', { style: 'display:flex;gap:8px;align-items:center' }, el('span', { class: 'mono costkey', text: '$0.003' }), el('span', { text: 'typical cost of the step (tokens when there are no prices)' })),
      el('span', { class: 'muted', text: 'Click a step to filter the list. Click a finding to select its step.' })));
  }

  /* ----------------------------------------------------------------- layout: Split */

  function renderSplit(main, v) {
    main.className = 'main layout-split pane-' + ui.pane;
    var graph = el('section', { class: 'graphpane dots', id: 'graphpane', 'aria-label': 'Workflow steps' });
    renderGraph(graph, v);
    var list = el('div', { class: 'flist', id: 'flist', role: 'listbox', 'aria-label': 'Open findings', tabindex: '-1' });
    fillFindings(list, v, 'card');
    var inspector = el('aside', { class: 'inspector', id: 'inspector', 'aria-label': 'Findings' }, el('div', { class: 'fhead' }, el('div', { class: 'r' }, el('span', { class: 'kicker', text: 'Findings' }), el('span', { class: 'mono muted small', text: v.filtered ? v.shown.length + ' of ' + v.total : String(v.total) }))), list, handledBox());
    main.appendChild(graph); main.appendChild(inspector);
  }

  /* ----------------------------------------------------------------- layout: Outline */

  function sevBars() {
    var c = model.counts, total = Math.max(1, model.findings.length);
    return el('div', { class: 'bars' }, ['high', 'medium', 'low'].map(function (s) {
      var bar = el('div', { class: 'bar ' + sevClass(s) }, sevMark(s), el('span', { class: 'tr' }, el('span', { class: 'fl' })), el('span', { class: 'mono', text: String(c[s]) }));
      bar.querySelector('.fl').style.setProperty('width', Math.round(100 * c[s] / total) + '%');
      return bar;
    }));
  }

  function renderOutline(main, v) {
    main.className = 'main layout-outline';
    var vm = model.graph ? M.build(model.graph, { workflows: data.graph && data.graph.workflows }) : null;
    var steps = R.stepsOf(model, vm);

    // left: the steps, in the order the script has them, with their findings
    var tree = el('nav', { class: 'tree', id: 'tree', 'aria-label': 'Steps' }, el('div', { class: 'treehead' }, el('span', { class: 'kicker', text: 'Outline' })));
    var lastSection = -1;
    steps.filter(function (s) { return s.kind !== 'start' && s.kind !== 'end'; }).forEach(function (s) {
      if (vm && vm.hasSections && s.section !== lastSection) {
        lastSection = s.section;
        var sec = vm.sections[s.section];
        tree.appendChild(el('div', { class: 'sechead' }, el('span', { class: 'secn', text: String(sec.n) }), el('span', { class: 'sect', text: sec.title || '' })));
      }
      var row = tone(el('button', { class: 'row k-' + s.vk + (s.depth ? ' in' + Math.min(2, s.depth) : '') + (v.selectedNode === s.id ? ' sel' : ''), type: 'button', 'data-node': s.id, 'aria-pressed': String(ui.node === s.id), 'aria-label': s.label + (s.total ? ', ' + s.total + ' open findings' : ''), onclick: function () { selectNode(s.id); } }), s.vk);
      row.appendChild(badgeOf(s.vk, true));
      row.appendChild(el('span', { class: 'lbl', text: s.label, title: s.label }));
      ['high', 'medium', 'low', 'info'].forEach(function (sv) { if (s.counts[sv]) { row.appendChild(el('span', { class: 'np ' + sevClass(sv), 'aria-label': s.counts[sv] + ' ' + sv }, el('i', { 'aria-hidden': 'true', text: R.SEVERITY_GLYPH[sv] }), String(s.counts[sv]))); } });
      tree.appendChild(row);
    });
    var whole = model.findings.filter(function (f) { return !f.node; }).length;
    tree.appendChild(el('div', { class: 'sechead wholehead' }, el('span', { class: 'secn', text: '·' }), el('span', { class: 'sect small', text: 'Whole script' }), el('span', { class: 'sp' }), el('span', { class: 'mono muted small', text: whole + ' open' })));

    // centre: the selected step with its estimates and findings, or every finding when no step is chosen
    var centre = el('section', { class: 'centre', id: 'centre', 'aria-label': ui.node ? 'Findings of ' + nodeLabel(ui.node) : 'All findings' });
    var step = ui.node ? steps.filter(function (s) { return s.id === ui.node; })[0] : null;
    if (step) {
      centre.appendChild(el('div', { class: 'sechd' }, el('span', { class: 'bign', text: String(step.total) }), el('span', { class: 'tt', text: step.label }), el('span', { class: 'sp' }), el('span', { class: 'mono muted small', text: step.line ? 'line ' + step.line : '' })));
      var c = step.cost;
      var all = model.summary.cost;
      centre.appendChild(el('div', { class: 'est' },
        el('div', null, el('span', { class: 'kicker', text: 'Cost of this step' }), el('b', null, c && c.typical ? R.costText(c.typical) : '–', ' ', el('small', { class: 'muted', text: c && c.typical ? 'typical' : '' })), el('span', { class: 'muted small', text: c && c.worst ? R.costText(c.worst) + ' worst' : 'no model call' })),
        el('div', null, el('span', { class: 'kicker', text: 'Calls' }), el('b', null, c && c.typical ? String(c.typical.calls) : '–', ' ', el('small', { class: 'muted', text: c && c.typical ? 'typical' : '' })), el('span', { class: 'muted small', text: c && c.worst ? c.worst.calls + ' worst' : '' })),
        el('div', null, el('span', { class: 'kicker', text: 'Model' }), el('b', { text: c && c.model ? c.model : '–' }), el('span', { class: 'muted small', text: step.agent ? 'Agent ' + step.agent : '' })),
        el('div', null, el('span', { class: 'kicker', text: 'Whole run' }), el('b', { text: all && all.typical ? all.typical : '–' }), el('span', { class: 'muted small', text: 'typical, all steps' }))));
    } else {
      centre.appendChild(el('div', { class: 'sechd' }, el('span', { class: 'bign', text: String(v.shown.length) }), el('span', { class: 'tt', text: 'All findings' }), el('span', { class: 'sp' }), el('span', { class: 'mono muted small', text: 'choose a step on the left to narrow them' })));
    }
    var list = el('div', { class: 'flist', id: 'flist', role: 'listbox', 'aria-label': 'Open findings', tabindex: '-1' });
    fillFindings(list, v, 'card');
    centre.appendChild(list);

    // right: the workflow's summary, the lenses, what is handled
    var narrow = window.matchMedia && window.matchMedia('(max-width: 900px)').matches;
    var side = el('details', { class: 'insp', id: 'outlineSide', open: narrow ? null : '' }, el('summary', { class: 'sidesum', text: 'Workflow summary and lenses' }), sevBars());
    var s = model.summary;
    side.appendChild(el('div', { class: 'sumlines' },
      el('span', null, el('b', { text: s.cost ? (s.cost.typical || '?') : '?' }), ' typical · ', el('b', { text: s.cost ? (s.cost.worst || '?') : '?' }), ' worst per run'),
      s.time ? el('span', null, el('b', { text: s.time.typical || '?' }), ' typical · ', el('b', { text: s.time.worst || '?' }), ' worst') : null,
      s.effects ? el('span', null, el('b', { text: s.effects.text }), ' worst case') : null,
      s.trifecta ? el('span', { class: 'chip warn', text: 'trifecta present' }) : null));
    var lenses = el('div', { class: 'lenses', role: 'group', 'aria-label': 'Lenses' }, el('div', { class: 'kicker', text: 'Lenses' }));
    v.rail.forEach(function (l) {
      var chosen = ui.filter.lenses.indexOf(l.id) >= 0;
      var attrs = { class: 'lensrow' + (l.on ? '' : ' muted'), type: 'button', 'data-lens-row': l.id, 'aria-pressed': String(chosen), title: l.on ? (chosen ? 'Showing only ' : 'Show only ') + l.name : l.name + ' is an optional lens and is off' };
      if (l.on) { attrs.onclick = function () { toggle(ui.filter.lenses, l.id); save(); renderAll(); }; } else { attrs.disabled = true; }
      lenses.appendChild(el('button', attrs, el('span', { class: 'tg' + (l.on ? '' : ' off') }), l.name, el('span', { class: 'n', text: l.on ? String(l.count) : 'off' })));
    });
    side.appendChild(lenses);
    side.appendChild(handledBox());
    main.appendChild(tree); main.appendChild(centre); main.appendChild(side);
  }

  /* ----------------------------------------------------------------- layout: List */

  function renderList(main, v) {
    main.className = 'main layout-list';
    var table = el('section', { class: 'tbl', id: 'table', 'aria-label': 'Findings' });
    table.appendChild(el('div', { class: 'thead', 'aria-hidden': 'true' }, el('span', { text: 'Severity' }), el('span', { text: 'Rule' }), el('span', { text: 'Lens' }), el('span', { text: 'Finding' }), el('span', { text: 'Where' })));
    var list = el('div', { class: 'flist', id: 'flist', role: 'listbox', 'aria-label': 'Open findings', tabindex: '-1' });
    fillFindings(list, v, 'table');
    table.appendChild(list);
    table.appendChild(handledBox());

    var mm = el('aside', { class: 'mm', id: 'minimap', 'aria-label': 'Where the findings are' }, el('div', { class: 'fhead' }, el('span', { class: 'kicker', text: 'Where they are' })));
    if (model.graph) {
      var vm = M.build(model.graph, { workflows: data.graph && data.graph.workflows });
      var mini = el('div', { class: 'mini dots' });
      R.stepsOf(model, vm).forEach(function (s, i) {
        if (i > 0) { mini.appendChild(el('div', { class: 'ml', 'aria-hidden': 'true' })); }
        if (s.kind === 'start' || s.kind === 'end') { mini.appendChild(tone(el('div', { class: 'mn k-term endcap' }, el('span', { text: s.kind === 'start' ? 'Start' : 'End' })), 'term')); return; }
        var node = tone(el('button', { class: 'mn k-' + s.vk + (v.selectedNode === s.id ? ' sel' : ''), type: 'button', 'data-node': s.id, 'aria-pressed': String(ui.node === s.id), 'aria-label': s.label + (s.total ? ', ' + s.total + ' open findings' : ''), onclick: function () { selectNode(s.id); } }), s.vk);
        node.appendChild(el('span', { class: 'mnl', text: s.label }));
        var dots = el('span', { class: 'd', 'aria-hidden': 'true' });
        ['high', 'medium', 'low', 'info'].forEach(function (sv) { for (var k = 0; k < s.counts[sv]; k++) { dots.appendChild(el('i', { class: sevClass(sv) })); } });
        node.appendChild(dots);
        mini.appendChild(node);
      });
      mm.appendChild(mini);
    }
    mm.appendChild(el('div', { class: 'fhead top' }, el('span', { class: 'kicker', text: 'Focused finding' }), v.selected ? [el('span', { class: 'chip rule', text: v.selected.rule }), el('span', { class: 'chip lens', text: v.selected.lensName })] : null));
    var focus = el('div', { class: 'focus', id: 'focus' });
    if (v.selected) {
      focus.appendChild(el('div', { class: 'fd-title', text: v.selected.title }));
      add(focus, detailOf(v.selected));
      focus.appendChild(el('div', { class: 'fd-acts' }, revealButton(v.selected)));
    } else {
      focus.appendChild(el('div', { class: 'muted', text: 'Select a finding to see why it matters and what would fix it.' }));
    }
    mm.appendChild(focus);
    main.appendChild(table); main.appendChild(mm);
  }

  /* ----------------------------------------------------------------- the rail and the banner */

  function renderRail() {
    var footer = $('footer');
    clear(footer);
    var legend = el('span', { class: 'legend', role: 'group', 'aria-label': 'Filter by severity' });
    ['high', 'medium', 'low', 'info'].forEach(function (sv) {
      var on = ui.filter.severities.indexOf(sv) >= 0;
      legend.appendChild(el('button', { class: 'sev legendbtn ' + sevClass(sv), type: 'button', 'aria-pressed': String(on), 'data-severity': sv, title: (on ? 'Showing only ' : 'Show only ') + R.SEVERITY_WORD[sv] + ' findings', onclick: function () { toggle(ui.filter.severities, sv); save(); renderAll(); } }, el('i', { 'aria-hidden': 'true', text: R.SEVERITY_GLYPH[sv] }), R.SEVERITY_WORD[sv]));
    });
    footer.appendChild(el('div', { class: 'railtop' }, el('span', { class: 'kicker', text: 'Review' }), el('span', { class: 'note', text: 'Advisory: open findings never block Run, Package or Deploy.' }), legend));
    var rail = R.lensRail(model);
    if (!rail.length) { return; }
    var bar = el('div', { class: 'rail', 'aria-hidden': 'true' });
    var secs = el('div', { class: 'railsecs', role: 'group', 'aria-label': 'Filter by lens' });
    var offStarted = false;
    rail.forEach(function (l) {
      var gap = !l.on && !offStarted ? 'margin-left:14px' : '';
      if (!l.on) { offStarted = true; }
      var chosen = ui.filter.lenses.indexOf(l.id) >= 0;
      bar.appendChild(el('span', { class: 'seg' + (l.count ? ' has' : '') + (l.on ? '' : ' off'), style: 'flex:1;' + gap }));
      var attrs = { class: 'sec' + (l.count ? ' has' : '') + (l.on ? '' : ' off'), type: 'button', style: 'flex:1;' + gap, 'data-lens': l.id, 'aria-pressed': String(chosen), title: l.on ? (chosen ? 'Showing only ' : 'Show only ') + l.name + ' (' + l.count + ' open)' : l.name + ' is an optional lens and is off', text: l.name + (l.on && l.count ? ' ' + l.count : '') };
      if (l.on) { attrs.onclick = function () { toggle(ui.filter.lenses, l.id); save(); renderAll(); }; } else { attrs.disabled = true; }
      secs.appendChild(el('button', attrs));
    });
    footer.appendChild(bar); footer.appendChild(secs);
  }

  function renderBanner() {
    var b = $('banner');
    clear(b);
    b.hidden = true;
    b.className = 'banner';
    if (data.status === 'stale') {
      b.hidden = false; b.classList.add('stale');
      b.appendChild(el('span', { 'data-chip': 'stale', class: 'chip warn', text: 'stale' }));
      b.appendChild(el('span', { text: data.message || 'The script changed after this review. Save, or re-run, to update it.' }));
    } else if (data.status === 'error') {
      b.hidden = false; b.classList.add('error');
      b.appendChild(el('span', { text: data.message || 'The review could not run.' }));
    } else if (data.status === 'running') {
      b.hidden = false;
      b.appendChild(el('span', { 'data-chip': 'running', class: 'chip plain', text: 'reviewing…' }));
      b.appendChild(el('span', { class: 'muted', text: model.findings.length ? 'Showing the previous result until the new one arrives.' : '' }));
    }
    document.body.classList.toggle('running', data.status === 'running');
  }

  /* ----------------------------------------------------------------- actions */

  function selectNode(id) {
    ui.node = ui.node === id ? null : id;
    if (ui.node) {
      var first = R.view(model, ui).shown[0];
      ui.selected = first ? first.id : null;
    }
    save(); renderAll();
    if (ui.node && ui.selected) { var f = model.byId[ui.selected]; if (f) { reveal(f, true); } }
  }

  function selectFinding(id, doReveal) {
    var hadFocus = !!(document.activeElement && document.activeElement.closest && document.activeElement.closest('#flist'));
    ui.selected = id;
    var f = model.byId[id];
    if (f && f.node && ui.node && ui.node !== f.node) { ui.node = null; }
    save(); renderAll();
    if (f) { scrollToNode(f.node); if (doReveal) { reveal(f, true); } }
    var row = document.querySelector('[data-finding="' + cssEscape(id) + '"]');
    if (row && hadFocus) { row.focus(); }
  }

  function scrollToNode(id) {
    if (!id) { return; }
    var card = document.querySelector('[data-node="' + cssEscape(id) + '"]');
    if (card && card.scrollIntoView) { card.scrollIntoView({ block: 'nearest' }); }
  }
  function reveal(f, beside) { if (f.line > 0) { post({ type: 'reveal', file: f.file, line: f.line, beside: !!beside }); } }

  function onRowKey(e) {
    var rows = Array.prototype.slice.call(document.querySelectorAll('#flist .fd'));
    var i = rows.indexOf(e.currentTarget);
    if (e.key === 'ArrowDown' && i < rows.length - 1) { e.preventDefault(); rows[i + 1].focus(); selectFinding(rows[i + 1].getAttribute('data-finding'), false); }
    else if (e.key === 'ArrowUp' && i > 0) { e.preventDefault(); rows[i - 1].focus(); selectFinding(rows[i - 1].getAttribute('data-finding'), false); }
    else if (e.key === 'Enter') { e.preventDefault(); var f = model.byId[e.currentTarget.getAttribute('data-finding')]; if (f) { reveal(f, true); } }
  }

  /* ----------------------------------------------------------------- putting it together */

  function renderAll() {
    var oldList = $('flist');
    if (oldList && oldList.scrollTop > 0) { ui.scroll = oldList.scrollTop; }
    var v = R.view(model, ui);
    document.body.classList.toggle('layout-split', ui.layout === 'split');
    document.body.classList.toggle('layout-outline', ui.layout === 'outline');
    document.body.classList.toggle('layout-list', ui.layout === 'list');
    renderToolbar(); renderBanner(); renderStrip(); renderControls(v);
    var main = $('main');
    clear(main);
    if (ui.layout === 'outline') { renderOutline(main, v); } else if (ui.layout === 'list') { renderList(main, v); } else { renderSplit(main, v); }
    renderRail();
    $('loading').hidden = data.status !== 'loading';
    var list = $('flist');
    if (list) {
      list.scrollTop = ui.scroll || 0;
      list.addEventListener('scroll', function () { ui.scroll = list.scrollTop; save(); });
    }
  }

  function rebuild() {
    model = R.build(data.review, data.graph, { workflow: ui.workflow });
    // a selection or filter for something that is gone from this result is dropped, never kept pointing at nothing
    if (ui.selected && !model.byId[ui.selected]) { ui.selected = null; }
    ui.multi = ui.multi.filter(function (id) { return !!model.byId[id]; });
    if (ui.node && !(model.graph && model.graph.nodes.some(function (n) { return n.id === ui.node; }))) { ui.node = null; }
    renderAll();
  }

  window.addEventListener('message', function (event) {
    var m = event.data;
    if (!m || typeof m !== 'object') { return; }
    switch (m.type) {
      case 'review':
        data.review = m.review; data.graph = m.graph; data.status = m.status || 'idle'; data.message = m.message || ''; data.entry = typeof m.entry === 'string' ? m.entry : data.entry;
        save(); rebuild(); break;
      case 'settings':
        // the starting layout, for a panel that has no saved choice yet
        if (!savedState() && R.LAYOUTS.indexOf(m.layout) >= 0) { ui.layout = m.layout; save(); renderAll(); }
        break;
      case 'status':
        data.status = m.status; data.message = m.message || '';
        renderBanner(); $('loading').hidden = data.status !== 'loading'; break;
      case 'cursor':
        if (!model.graph) { break; }
        var onLine = R.findingsOnLine(model, m.file, m.line);
        var node = R.nodeOnLine(model, m.file, m.line);
        if (onLine.length) { ui.selected = onLine[0]; }
        save(); renderAll();
        if (node) { scrollToNode(node); }
        break;
      default: break;
    }
  });

  function wire() {
    $('workflow').addEventListener('change', function (e) { ui.workflow = e.target.value; ui.node = null; save(); rebuild(); post({ type: 'selectWorkflow', name: e.target.value }); });
    [['layoutSplit', 'split'], ['layoutOutline', 'outline'], ['layoutList', 'list']].forEach(function (p) { $(p[0]).addEventListener('click', function () { ui.layout = p[1]; save(); renderAll(); }); });
    [['paneFindings', 'findings'], ['paneBoth', 'both'], ['paneGraph', 'graph']].forEach(function (p) { $(p[0]).setAttribute('data-pane', p[1]); $(p[0]).addEventListener('click', function () { ui.pane = p[1]; save(); renderAll(); }); });
    [['groupSeverity', 'severity'], ['groupLens', 'lens'], ['groupRule', 'rule'], ['groupFile', 'file']].forEach(function (g) { $(g[0]).setAttribute('data-group', g[1]); $(g[0]).addEventListener('click', function () { ui.groupBy = g[1]; save(); renderAll(); }); });
    $('filter').addEventListener('input', function (e) { ui.filter.text = e.target.value; save(); renderAll(); $('filter').focus(); });
    $('rerun').addEventListener('click', function () { post({ type: 'rerun' }); });
    $('showNotEvaluated').addEventListener('click', function () { ui.showNotEvaluated = !ui.showNotEvaluated; save(); renderAll(); });
    $('clearNode').addEventListener('click', function () { ui.node = null; save(); renderAll(); });
  }

  wire();
  renderAll();
  post({ type: 'ready' });
})();
