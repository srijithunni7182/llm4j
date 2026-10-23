/*
 * The Loom Review panel page: draws what LoomReview.build works out (graph on the left, findings on the right, the summary strip
 * above, the lens rail below) and sends the extension what a person does. Every piece of text from a script or a finding is set with
 * textContent; no markup is ever built from it. Plain JavaScript, no imports.
 */
(function () {
  'use strict';
  var R = window.LoomReview, M = window.LoomModel, V = window.LoomViews;
  var vscode = typeof acquireVsCodeApi === 'function' ? acquireVsCodeApi() : { postMessage: function () {}, getState: function () { return null; }, setState: function () {} };

  var data = { review: null, graph: null, status: 'loading', message: '', requestedWorkflow: null };
  var model = R.build(null, null);
  var ui = Object.assign({ pane: 'both', groupBy: 'severity', filter: { text: '', lenses: [], severities: [] }, node: null, selected: null, collapsed: {}, showHandled: false, showNotEvaluated: false }, safeState());

  function safeState() { try { var s = vscode.getState(); return s && s.ui ? s.ui : {}; } catch (e) { return {}; } }
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

  /* ----------------------------------------------------------------- the toolbar and the strip */

  function renderToolbar() {
    var select = $('workflow');
    clear(select);
    model.workflows.forEach(function (name) { select.appendChild(el('option', { value: name, text: name + '(' + paramsOf(name) + ')' })); });
    if (model.workflow) { select.value = model.workflow; }
    select.disabled = model.workflows.length < 2;
    var budget = $('runbudget');
    var cost = model.summary.cost;
    budget.hidden = true;
    ['paneFindings', 'paneBoth', 'paneGraph'].forEach(function (id) {
      var b = $(id); b.setAttribute('aria-pressed', String(b.getAttribute('data-pane') === ui.pane));
    });
    var safe = R.safeCount(model.findings);
    var fix = $('fixAll');
    fix.textContent = 'Fix all safe (' + safe.safe + ' of ' + safe.total + ')';
    fix.hidden = true; // fixes arrive with the triage actions
    var clear_ = $('clearNode');
    clear_.hidden = !ui.node;
    if (ui.node) { clear_.textContent = 'Step: ' + nodeLabel(ui.node) + ' ✕'; clear_.setAttribute('aria-label', 'Showing only the findings of ' + nodeLabel(ui.node) + '. Show all.'); }
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
    var open = model.open;
    strip.appendChild(stat('Open findings', s.countsText));
    if (s.cost) {
      var typ = s.cost.typical || 'unknown', worst = s.cost.worst || 'unknown';
      var small = s.cost.priced ? s.cost.bound : 'tokens: no prices for ' + (s.cost.unpriced.length ? s.cost.unpriced.join(', ') : 'this run');
      strip.appendChild(stat('Cost per run', typ + ' typical · ' + worst + ' worst', small));
    }
    if (s.time) {
      var waits = s.time.waits && s.time.waits.length ? '+ waits: ' + s.time.waits.length : '';
      strip.appendChild(stat('Time', (s.time.typical || '?') + ' typical · ' + (s.time.worst || '?') + ' worst', waits));
    }
    if (s.effects) { strip.appendChild(stat('Effects', s.effects.text, 'worst case per run')); }
    strip.appendChild(stat('Lenses', s.lenses.ran.length + ' on · ' + s.lenses.off.length + ' optional off'));
    var end = el('div', { class: 'stat end' });
    if (s.trifecta) { end.appendChild(el('span', { class: 'chip warn', text: 'trifecta present' })); }
    if (s.notEvaluated > 0) {
      end.appendChild(el('button', { class: 'link', type: 'button', 'data-act': 'not-evaluated', text: s.notEvaluated + (s.notEvaluated === 1 ? ' rule' : ' rules') + ' not evaluated', onclick: function () { ui.showNotEvaluated = !ui.showNotEvaluated; save(); renderList(); } }));
    }
    strip.appendChild(end);
    void open;
  }

  /* ----------------------------------------------------------------- the graph */

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

  function graphCard(vm) {
    var vk = M.VK[vm.vk] || M.VK.unknown;
    var selectedNode = ui.selected && model.byId[ui.selected] ? model.byId[ui.selected].node : null;
    var isSel = ui.node === vm.id || selectedNode === vm.id;
    var tone = el('div', { class: 'card k-' + vm.vk + (vm.kind === 'start' || vm.kind === 'end' ? ' term' : '') + (isSel ? ' sel' : '') + (ui.node && ui.node !== vm.id ? ' dim' : ''), 'data-node': vm.id, role: 'button', tabindex: '0', 'aria-pressed': String(isSel) });
    tone.style.setProperty('--h', vk.h); tone.style.setProperty('--c', vk.c);
    var findings = model.nodes[vm.id] ? model.nodes[vm.id].total : 0;
    tone.setAttribute('aria-label', vm.label + (findings ? ', ' + findings + ' open findings' : ''));
    if (vm.kind === 'start' || vm.kind === 'end') {
      tone.appendChild(el('span', { class: 't', text: vm.kind === 'start' ? 'Start' : 'End' }));
    } else {
      add(tone, nodeBadges(vm.id));
      var badge = el('span', { class: 'badge', 'aria-hidden': 'true' });
      badge.appendChild(V.svg(vk.glyph));
      var sub = vm.agent ? vk.name + ' ' + vm.agent : vk.name;
      var body = el('div', { class: 'body' }, el('div', { class: 'r1' }, el('span', { class: 't', text: vm.label }), vm.line ? el('span', { class: 'ln', text: ':' + vm.line }) : null), el('div', { class: 'sub', text: sub }));
      tone.appendChild(badge); tone.appendChild(body);
      var cost = model.nodes[vm.id] && model.nodes[vm.id].cost;
      if (cost && cost.typical) { tone.appendChild(el('span', { class: 'cost', title: 'typical cost of one run of this step' + (cost.worst ? ', worst ' + R.costText(cost.worst) : ''), text: R.costText(cost.typical) })); }
    }
    tone.addEventListener('click', function () { selectNode(vm.id); });
    tone.addEventListener('keydown', function (e) { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); selectNode(vm.id); } });
    return tone;
  }

  function renderGraph() {
    var pane = $('graphpane');
    clear(pane);
    if (!model.graph) {
      pane.appendChild(el('div', { class: 'empty' }, el('b', { text: 'No graph yet. ' }), data.status === 'loading' ? 'Reading the script…' : 'The script could not be drawn; see the message above.'));
      return;
    }
    var vm = M.build(model.graph, { workflows: data.graph && data.graph.workflows });
    var stack = el('div', { class: 'gstack', role: 'list', 'aria-label': 'Workflow steps' });
    var entry = el('div', { class: 'entry' });
    var visible = vm.nodes.filter(function (n) { return vm.main.indexOf(n.id) >= 0 || n.kind === 'start' || n.kind === 'end'; });
    var onMain = vm.main.length ? vm.main : vm.nodes.map(function (n) { return n.id; });
    var ordered = vm.nodes.filter(function (n) { return onMain.indexOf(n.id) >= 0 || n.kind === 'start' || n.kind === 'end'; });
    if (!ordered.length) { ordered = vm.nodes; }
    void visible;
    ordered.forEach(function (n, i) {
      if (i > 0) { entry.appendChild(el('div', { class: 'conn', 'aria-hidden': 'true' })); }
      var card = el('div', { role: 'listitem', style: 'width:100%' }, graphCard(n));
      entry.appendChild(card);
    });
    var off = vm.nodes.filter(function (n) { return ordered.indexOf(n) < 0; });
    stack.appendChild(entry);
    pane.appendChild(stack);
    if (off.length) {
      var offBox = el('div', { class: 'gstack', role: 'list', 'aria-label': 'Steps off the main path' }, el('div', { class: 'entry' }, el('div', { class: 'kicker', text: 'Off the main path' }), off.map(function (n) { return el('div', { role: 'listitem', style: 'width:100%;margin-top:8px' }, graphCard(n)); })));
      pane.appendChild(offBox);
    }
    pane.appendChild(el('div', { class: 'legendbox' },
      el('span', { class: 'kicker', text: 'How to read the graph' }),
      el('span', { style: 'display:flex;gap:8px;align-items:center' }, el('span', { class: 'np s-med' }, el('i', { 'aria-hidden': 'true', text: '!' }), '3'), el('span', { text: 'open findings on a step, by severity' })),
      el('span', { style: 'display:flex;gap:8px;align-items:center' }, el('span', { class: 'mono', style: 'font-size:10.5px;font-weight:600;border:1px solid var(--chip-line);padding:3px 6px', text: '$0.003' }), el('span', { text: 'typical cost of the step (tokens when there are no prices)' })),
      el('span', { class: 'muted', text: 'Click a step to filter the list. Click a finding to select its step.' })));
  }

  /* ----------------------------------------------------------------- the findings */

  function currentFilter() {
    var f = ui.filter;
    return { text: f.text, lenses: f.lenses, severities: f.severities, node: ui.node };
  }

  function findingRow(f) {
    var selected = ui.selected === f.id;
    var row = el('article', { class: 'fd ' + sevClass(f.severity) + (selected ? ' sel' : ''), 'data-finding': f.id, role: 'option', tabindex: selected || (!ui.selected && f.index === firstShownIndex()) ? '0' : '-1', 'aria-selected': String(selected), 'aria-label': R.describe(f) });
    var main = el('div', { class: 'fd-main' });
    var where = f.node ? nodeLabel(f.node) : (f.line > 0 ? 'line ' + f.line : 'whole script');
    var top = el('div', { class: 'fd-top' }, sevMark(f.severity), el('span', { class: 'chip rule', text: f.rule }), el('span', { class: 'chip lens', text: f.lensName }),
      el('button', { class: 'ghost', type: 'button', 'data-act': 'reveal', title: 'Show this in the editor', text: where + (f.file ? ' · ' + R.baseName(f.file) + (f.line > 0 ? ':' + f.line : '') : ''), onclick: function (e) { e.stopPropagation(); reveal(f); } }));
    main.appendChild(top);
    main.appendChild(el('div', { class: 'fd-title', text: f.title }));
    if (selected) {
      if (f.why) { main.appendChild(el('div', { class: 'fd-why', text: f.why })); }
      var hint = f.fixHint || (f.safety ? '' : 'No automatic fix.');
      if (hint) { main.appendChild(el('div', { class: 'fd-acts' }, el('span', { class: 'fx', text: (f.safety === 'safe' ? 'safe fix · ' : f.safety === 'review' ? 'fix to review · ' : '') + hint }))); }
      if (f.fix && f.fix.edits && f.fix.edits.length) { main.appendChild(diffOf(f)); }
    }
    row.appendChild(main);
    row.addEventListener('click', function () { selectFinding(f.id, true); });
    row.addEventListener('keydown', onRowKey);
    return row;
  }

  function diffOf(f) {
    var box = el('div', { class: 'diff', 'aria-label': 'What the fix changes' }, el('div', { class: 'dh', text: (f.fix.label || 'The fix') + ' · ' + f.fix.edits.length + (f.fix.edits.length === 1 ? ' edit' : ' edits') }));
    f.fix.edits.slice(0, 6).forEach(function (e) {
      var text = String(e.newText || '');
      if (!text) { box.appendChild(el('div', { class: 'd', text: '-  (removes lines ' + e.startLine + '–' + e.endLine + ')' })); return; }
      text.split('\n').slice(0, 8).forEach(function (line) { box.appendChild(el('div', { class: 'a', text: '+' + line })); });
    });
    return box;
  }

  function nodeLabel(id) { var n = model.graph && model.graph.nodes.filter(function (x) { return x.id === id; })[0]; return n ? n.label : id; }

  function shownFindings() { return R.filterFindings(model.findings, currentFilter()); }
  function firstShownIndex() { var s = shownFindings(); return s.length ? s[0].index : -1; }

  function groupHeader(g, level) {
    var key = level + ':' + g.key;
    var closed = !!ui.collapsed[key];
    var head = el('div', { class: (level === 0 ? 'gh ' : 'subgh ') + sevClass(g.severity || 'info'), role: 'heading', 'aria-level': String(level + 2) });
    head.appendChild(el('button', { class: 'twist', type: 'button', 'aria-expanded': String(!closed), 'aria-label': (closed ? 'Expand ' : 'Collapse ') + g.label, text: closed ? '▸' : '▾', onclick: function () { ui.collapsed[key] = !closed; save(); renderList(); } }));
    if (g.by === 'severity') { head.appendChild(sevMark(g.key)); } else { head.appendChild(el('b', { text: g.label })); }
    head.appendChild(el('span', { class: 'cnt', text: String(g.count) }));
    return head;
  }

  function renderList() {
    var list = $('flist');
    clear(list);
    var shown = shownFindings();
    var head = $('fcount');
    head.textContent = shown.length === model.findings.length ? model.findings.length + ' open' : shown.length + ' of ' + model.findings.length + ' open';
    $('showHandled').hidden = true;
    var ne = $('showNotEvaluated');
    ne.hidden = model.summary.notEvaluated === 0;
    ne.textContent = (ui.showNotEvaluated ? 'Hide' : 'Show') + ' not evaluated (' + model.summary.notEvaluated + ')';
    if (!shown.length) {
      list.appendChild(el('div', { class: 'empty' }, model.findings.length ? [el('b', { text: 'No finding matches. ' }), 'Clear the filter to see all ' + model.findings.length + '.'] : [el('b', { text: data.status === 'loading' ? 'Reviewing…' : 'No open findings.' }), data.status === 'loading' ? '' : ' Nothing in this script needs your attention.']));
    } else {
      var groups = R.group(shown, ui.groupBy);
      groups.forEach(function (g) {
        list.appendChild(groupHeader(g, 0));
        if (ui.collapsed['0:' + g.key]) { return; }
        if (g.sub) {
          g.sub.forEach(function (s) {
            list.appendChild(groupHeader(s, 1));
            if (!ui.collapsed['1:' + s.key]) { s.findings.forEach(function (f) { list.appendChild(findingRow(f)); }); }
          });
        } else {
          g.findings.forEach(function (f) { list.appendChild(findingRow(f)); });
        }
      });
    }
    if (ui.showNotEvaluated && model.notEvaluated.length) {
      list.appendChild(el('div', { class: 'notes' }, el('div', { class: 'kicker', text: 'Not evaluated' }), el('ul', null, model.notEvaluated.map(function (n) { return el('li', { text: n.message }); }))));
    }
    renderHandled();
  }

  function renderHandled() {
    var h = model.handled, box = $('handled');
    clear(box);
    box.appendChild(el('span', { class: 'kicker', text: 'Handled' }));
    box.appendChild(el('span', { text: h.accepted + ' accepted' }));
    box.appendChild(el('span', { class: 'muted', text: '·' }));
    box.appendChild(el('span', { text: h.dismissed + ' dismissed' }));
    box.appendChild(el('span', { class: 'muted', text: '·' }));
    box.appendChild(el('span', { text: h.baselined + ' baselined' }));
  }

  function toggle(list, value) {
    var i = list.indexOf(value);
    if (i >= 0) { list.splice(i, 1); } else { list.push(value); }
  }

  function renderRail() {
    var footer = $('footer');
    clear(footer);
    var legend = el('span', { class: 'legend', role: 'group', 'aria-label': 'Filter by severity' });
    ['high', 'medium', 'low', 'info'].forEach(function (sv) {
      var on = ui.filter.severities.indexOf(sv) >= 0;
      var b = el('button', { class: 'sev legendbtn ' + sevClass(sv), type: 'button', 'aria-pressed': String(on), 'data-severity': sv, title: (on ? 'Showing only ' : 'Show only ') + R.SEVERITY_WORD[sv] + ' findings', onclick: function () { toggle(ui.filter.severities, sv); save(); renderAll(); } }, el('i', { 'aria-hidden': 'true', text: R.SEVERITY_GLYPH[sv] }), R.SEVERITY_WORD[sv]);
      legend.appendChild(b);
    });
    var top = el('div', { class: 'railtop' }, el('span', { class: 'kicker', text: 'Review' }), el('span', { class: 'note', text: 'Advisory: open findings never block Run, Package or Deploy.' }), legend);
    footer.appendChild(top);
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
      var name = l.name + (l.on && l.count ? ' ' + l.count : '');
      var attrs = { class: 'sec' + (l.count ? ' has' : '') + (l.on ? '' : ' off'), type: 'button', style: 'flex:1;' + gap, 'data-lens': l.id, 'aria-pressed': String(chosen), title: l.on ? (chosen ? 'Showing only ' : 'Show only ') + l.name + ' (' + l.count + ' open)' : l.name + ' is an optional lens and is off', text: name };
      if (l.on) { attrs.onclick = function () { toggle(ui.filter.lenses, l.id); save(); renderAll(); }; } else { attrs.disabled = true; }
      secs.appendChild(el('button', attrs));
    });
    footer.appendChild(bar); footer.appendChild(secs);
  }

  /* ----------------------------------------------------------------- the banner */

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
      var first = shownFindings()[0];
      ui.selected = first ? first.id : null;
    }
    save(); renderAll();
    if (ui.node && ui.selected) { revealFirst(); }
  }
  function revealFirst() { var f = model.byId[ui.selected]; if (f) { reveal(f, true); } }

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
  function cssEscape(s) { return String(s).replace(/["\\]/g, '\\$&'); }
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
    $('main').className = 'main pane-' + ui.pane;
    renderToolbar(); renderBanner(); renderStrip(); renderGraph(); renderList(); renderRail();
    $('filter').value = ui.filter.text;
    ['groupSeverity', 'groupLens', 'groupRule', 'groupFile'].forEach(function (id) { var b = $(id); b.setAttribute('aria-pressed', String(b.getAttribute('data-group') === ui.groupBy)); });
    $('loading').hidden = data.status !== 'loading';
  }

  function rebuild() {
    model = R.build(data.review, data.graph, { workflow: data.requestedWorkflow || ui.workflow });
    // a selection or filter for something that is gone from this result is dropped, never kept pointing at nothing
    if (ui.selected && !model.byId[ui.selected]) { ui.selected = null; }
    if (ui.node && !model.nodes[ui.node] && !(model.graph && model.graph.nodes.some(function (n) { return n.id === ui.node; }))) { ui.node = null; }
    renderAll();
  }

  window.addEventListener('message', function (event) {
    var m = event.data;
    if (!m || typeof m !== 'object') { return; }
    switch (m.type) {
      case 'review':
        data.review = m.review; data.graph = m.graph; data.status = m.status || 'idle'; data.message = m.message || ''; data.entry = typeof m.entry === 'string' ? m.entry : data.entry; save();
        rebuild(); break;
      case 'status':
        data.status = m.status; data.message = m.message || '';
        renderBanner(); $('loading').hidden = data.status !== 'loading'; break;
      case 'cursor':
        if (!model.graph) { break; }
        var onLine = R.findingsOnLine(model, m.file, m.line);
        var node = R.nodeOnLine(model, m.file, m.line);
        if (onLine.length) { ui.selected = onLine[0]; }
        if (node) { scrollToNode(node); }
        save(); renderList(); break;
      default: break;
    }
  });

  function wire() {
    $('workflow').addEventListener('change', function (e) { ui.workflow = e.target.value; data.requestedWorkflow = e.target.value; ui.node = null; save(); rebuild(); post({ type: 'selectWorkflow', name: e.target.value }); });
    [['paneFindings', 'findings'], ['paneBoth', 'both'], ['paneGraph', 'graph']].forEach(function (p) { $(p[0]).setAttribute('data-pane', p[1]); $(p[0]).addEventListener('click', function () { ui.pane = p[1]; save(); renderAll(); }); });
    [['groupSeverity', 'severity'], ['groupLens', 'lens'], ['groupRule', 'rule'], ['groupFile', 'file']].forEach(function (g) { $(g[0]).setAttribute('data-group', g[1]); $(g[0]).addEventListener('click', function () { ui.groupBy = g[1]; save(); renderAll(); }); });
    $('filter').addEventListener('input', function (e) { ui.filter.text = e.target.value; save(); renderList(); $('filter').focus(); });
    $('rerun').addEventListener('click', function () { post({ type: 'rerun' }); });
    $('showNotEvaluated').addEventListener('click', function () { ui.showNotEvaluated = !ui.showNotEvaluated; save(); renderList(); });
    $('clearNode').addEventListener('click', function () { ui.node = null; save(); renderAll(); });
  }

  wire();
  renderAll();
  post({ type: 'ready' });
})();
