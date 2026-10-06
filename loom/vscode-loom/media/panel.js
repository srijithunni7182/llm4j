/* The workflow graph panel, inside a VS Code webview. It draws what the extension sends (see src/graph/protocol.ts)
   with the shared renderer (graph-render.js) and sends back only the messages that protocol lists. Text from a script
   is put in the page with textContent, never as HTML. */
(function () {
  'use strict';
  var vscode = acquireVsCodeApi();
  var $ = function (id) { return document.getElementById(id); };
  var G = window.LoomGraph;

  var state = { result: null, workflow: null, stack: [], selected: null, fromCursor: false, view: null, stale: null, diagOpen: false };

  /* ------------------------------------------------------------ helpers */

  function el(tag, attrs) {
    var node = document.createElement(tag), i, child;
    Object.keys(attrs || {}).forEach(function (key) {
      var value = attrs[key];
      if (value == null || value === false) { return; }
      if (key === 'class') { node.className = value; }
      else if (key === 'text') { node.textContent = value; }
      else if (key.slice(0, 2) === 'on') { node.addEventListener(key.slice(2), value); }
      else { node.setAttribute(key, value === true ? '' : value); }
    });
    for (i = 2; i < arguments.length; i++) { child = arguments[i]; add(node, child); }
    return node;
  }
  function add(node, child) {
    if (child == null || child === false) { return; }
    if (Array.isArray(child)) { child.forEach(function (c) { add(node, c); }); return; }
    node.appendChild(typeof child === 'object' ? child : document.createTextNode(String(child)));
  }
  function clear(node) { while (node.firstChild) { node.removeChild(node.firstChild); } return node; }
  function baseName(path) { var parts = String(path || '').split(/[\\/]/); return parts[parts.length - 1]; }
  function send(message) { vscode.postMessage(message); }
  function workflowNamed(name, file) {
    var list = state.result ? state.result.workflows : [];
    return list.filter(function (w) { return w.name === name && (file == null || w.file === file); })[0] || list.filter(function (w) { return w.name === name; })[0];
  }
  function agentMap() {
    var out = {};
    ((state.result && state.result.agents) || []).forEach(function (a) { out[a.name] = a; });
    return out;
  }
  function budgetLine(b) {
    var text = G.budgetText(b || {});
    return text ? text.replace(/^budget /, '') : '';
  }

  /* -------------------------------------------------------------- states */

  function show(which) {
    $('loading').hidden = which !== 'loading';
    $('empty').hidden = which !== 'empty';
    ['zoomOut', 'zoomIn', 'fit', 'mermaid', 'workflow', 'legendButton'].forEach(function (id) { $(id).disabled = which !== 'graph'; });
    if (state.view) { state.view.svg.style.visibility = which === 'graph' ? 'visible' : 'hidden'; }
  }

  function showLoading(file) {
    $('loadingText').textContent = 'Reading ' + baseName(file) + '…';
    show('loading');
  }

  function showEmpty() {
    var entry = state.result.entry;
    $('emptyText').textContent = 'This file defines no workflows.';
    $('emptyOpen').onclick = function () { send({ type: 'openSource', file: entry, line: 1, beside: false }); };
    show('empty');
  }

  /* ---------------------------------------------------------- the graph */

  function applyGraph(message) {
    var previous = state.workflow;
    state.result = message.result;
    state.stale = null; $('stale').hidden = true;
    renderRunBudget(); renderFiles(); renderDiagnostics();
    if (!message.result.workflows.length) { renderWorkflowSelect(null); showEmpty(); return; }
    var wanted = (previous && workflowNamed(previous)) ? previous : message.workflow;
    if (!workflowNamed(wanted)) { wanted = message.result.workflows[0].name; } // the extension named one that is not there
    var keep = !!previous && wanted === previous && !!state.view;
    state.stack = keep ? state.stack.filter(function (n) { return !!workflowNamed(n); }) : [];
    show('graph');
    showWorkflow(wanted, keep);
  }

  function showWorkflow(name, keep) {
    var wf = workflowNamed(name);
    if (!wf) { return; }
    state.workflow = wf.name;
    if (!state.view) {
      state.view = new G.View($('canvas'), {
        embedCss: false,
        onSelect: selectedByClick,
        onActivate: activated,
        onHover: hovered,
        onZoom: function (k) { $('zoomLabel').textContent = Math.round(k * 100) + '%'; }
      });
    }
    var stillThere = state.selected && wf.nodes.some(function (n) { return n.id === state.selected; });
    if (!stillThere) { state.selected = null; }
    state.view.selected = state.selected;
    var context = { agents: agentMap(), currentFile: wf.file };
    if (keep) {
      // blocks folded before the refresh stay folded, for the steps that are still there
      var ids = {}; wf.nodes.forEach(function (n) { ids[n.id] = true; });
      context.collapsed = new Set(Array.from(state.view.collapsed).filter(function (id) { return ids[id]; }));
    }
    state.view.setGraph({ nodes: wf.nodes, edges: wf.edges }, context, keep);
    state.view.svg.style.visibility = 'visible';
    renderWorkflowSelect(wf.name); renderCrumbs(wf); renderSelected();
    send({ type: 'selectWorkflow', name: wf.name });
  }

  function currentWorkflow() { return workflowNamed(state.workflow); }

  /* -------------------------------------------------------------- toolbar */

  function renderWorkflowSelect(selected) {
    var select = $('workflow'); clear(select);
    if (!state.result) { return; }
    var entryFile = state.result.entry, groups = {}, order = [];
    state.result.workflows.forEach(function (w) {
      if (!groups[w.file]) { groups[w.file] = []; order.push(w.file); }
      groups[w.file].push(w);
    });
    order.forEach(function (file) {
      var container = file === entryFile ? select : select.appendChild(el('optgroup', { label: 'From ' + baseName(file) }));
      groups[file].forEach(function (w) { container.appendChild(el('option', { value: w.name, text: w.name + '(' + w.params.join(', ') + ')' })); });
    });
    if (selected) { select.value = selected; }
  }

  function renderRunBudget() {
    var chip = $('runbudget'), b = state.result && state.result.runBudget;
    var text = b && Object.keys(b).length ? budgetLine(b) : '';
    chip.hidden = !text;
    if (text) {
      chip.textContent = 'run budget ' + text;
      chip.title = 'Run budget: ' + Object.keys(b).map(function (k) { return k + ' ' + b[k]; }).join(', ');
    }
  }

  function renderCrumbs(wf) {
    var bar = $('crumbs');
    if (!state.stack.length) { bar.hidden = true; return; }
    bar.hidden = false; clear(bar);
    bar.appendChild(el('button', { type: 'button', text: '‹ Back', onclick: goBack }));
    var path = state.stack.concat([wf.name]);
    path.forEach(function (name, i) {
      var last = i === path.length - 1;
      bar.appendChild(last ? el('span', { class: 'here', text: name }) : el('button', { type: 'button', text: name, onclick: function () { state.stack = path.slice(0, i); state.selected = null; showWorkflow(name, false); } }));
      if (!last) { bar.appendChild(el('span', { class: 'muted', text: '›' })); }
    });
    if (wf.file !== state.result.entry) { bar.appendChild(el('span', { class: 'filechip', text: baseName(wf.file) })); }
  }

  function goBack() {
    var previous = state.stack.pop();
    if (previous) { state.selected = null; showWorkflow(previous, false); }
  }

  function drill(call) {
    var target = workflowNamed(call.workflow, call.file);
    if (!target) { return; }
    state.stack.push(state.workflow); state.selected = null;
    showWorkflow(target.name, false);
  }

  /* ----------------------------------------------------------- selection */

  function selectedByClick(id, node) {
    state.selected = id; state.fromCursor = false;
    renderSelected();
    if (node && node.source) { send({ type: 'openSource', file: node.source.file, line: node.source.line, beside: true }); }
  }

  function activated(id, node, how) {
    if (!node || node.kind !== 'call' || !node.call || node.unresolved) { return; }
    if (how.ctrl) {
      var callee = workflowNamed(node.call.workflow, node.call.file);
      if (callee) { send({ type: 'openSource', file: callee.file, line: callee.line || 1, beside: true }); }
      return;
    }
    drill(node.call);
  }

  function highlight(id) {
    if (!state.view) { return; }
    if (id) {
      state.selected = id; state.fromCursor = true;
      state.view.select(id); state.view.revealNode(id); renderSelected();
    } else if (state.fromCursor && state.selected) {
      state.selected = null; state.fromCursor = false;
      state.view.select(null); renderSelected();
    }
  }

  function renderSelected() {
    var box = clear($('selected')), wf = currentWorkflow();
    var node = wf && state.selected && wf.nodes.filter(function (n) { return n.id === state.selected; })[0];
    if (!node || node.kind === 'start' || node.kind === 'end') {
      box.appendChild(el('p', { class: 'muted', text: 'Click a step to see its settings and jump to its line. Double-click a call to open it.' }));
      return;
    }
    var rows = [el('dt', { text: 'Kind' }), el('dd', { text: node.kind }), el('dt', { text: 'Step' }), el('dd', { text: node.label })];
    var sub = G.subtitle(node, { agents: agentMap(), currentFile: wf.file });
    if (sub) { rows.push(el('dt', { text: 'Uses' }), el('dd', { text: sub })); }
    var chips = G.chips(node);
    if (chips.length) { rows.push(el('dt', { text: 'Settings' }), el('dd', null, el('span', { class: 'chips' }, chips.map(function (c) { return el('span', { class: 'chip ' + (c.v || ''), text: c.t }); })))); }
    var where = [];
    if (node.source) {
      where.push(el('span', { class: 'filechip', text: baseName(node.source.file) + ':' + node.source.line }), ' ',
        el('button', { class: 'link', type: 'button', text: 'Go to source', onclick: function () { send({ type: 'openSource', file: node.source.file, line: node.source.line, beside: true }); } }));
    }
    if (node.kind === 'call' && node.call && !node.unresolved) {
      where.push(' ', el('button', { class: 'link', type: 'button', text: 'Open ' + node.call.workflow, onclick: function () { drill(node.call); } }));
    }
    var promptAgent = node.agent && agentMap()[node.agent];
    if (promptAgent && promptAgent.prompt && promptAgent.prompt.file) {
      where.push(' ', el('button', { class: 'link', type: 'button', text: 'Open prompt ' + (promptAgent.prompt.ref || ''), onclick: function () { send({ type: 'openSource', file: promptAgent.prompt.file, line: 1, beside: true }); } }));
    }
    if (where.length) { rows.push(el('dt', { text: 'Source' }), el('dd', null, where)); }
    box.appendChild(el('dl', null, rows));
  }

  /* --------------------------------------------------------- agent card */

  function hovered(id, node, element) {
    var card = $('card');
    var agent = node && node.agent && agentMap()[node.agent];
    if (!agent) { card.hidden = true; return; }
    var rows = [];
    function row(label, value) { if (value) { rows.push(el('dt', { text: label }), el('dd', { text: value })); } }
    row('Model', [agent.model, agent.temperature != null ? 'temp ' + agent.temperature : null].filter(Boolean).join(' · '));
    row('Persona', agent.persona);
    row('Prompt', agent.prompt ? agent.prompt.ref + (agent.prompt.version ? ' (' + agent.prompt.version + ')' : ' (not found)') : '');
    row('Tools', (agent.tools || []).length ? agent.tools.join(', ') : 'none');
    row('Approval', agent.approveAll ? 'Needs approval for every tool' : (agent.approve || []).length ? 'Needs approval: ' + agent.approve.join(', ') : 'none');
    row('Budget', agent.budget && Object.keys(agent.budget).length ? budgetLine(agent.budget) : 'none');
    row('Limit', agent.maxIterations ? 'max ' + agent.maxIterations + ' iterations' : '');
    clear(card);
    card.appendChild(el('h3', null, agent.name, agent.source ? el('span', { class: 'filechip', text: baseName(agent.source.file) + ':' + agent.source.line }) : null));
    card.appendChild(el('dl', null, rows));
    card.hidden = false;
    var wrap = $('canvas').getBoundingClientRect(), r = element.getBoundingClientRect();
    var left = r.right - wrap.left + 10;
    if (left + 260 > wrap.width) { left = Math.max(8, r.left - wrap.left - 270); }
    card.style.left = left + 'px';
    card.style.top = Math.min(Math.max(8, r.top - wrap.top), Math.max(8, wrap.height - 200)) + 'px';
  }

  /* ------------------------------------------------- files, diagnostics */

  function renderFiles() {
    var box = clear($('files'));
    state.result.files.forEach(function (file, i) {
      var item = el('div', { class: 'fileitem' + (i ? ' imported' : '') },
        el('button', { class: 'name', type: 'button', text: (i ? '└ ' : '') + baseName(file.path), onclick: function () { send({ type: 'openSource', file: file.path, line: 1, beside: true }); } }),
        el('span', { class: 'filechip', text: i ? 'imported' : 'entry' }));
      state.result.workflows.filter(function (w) { return w.file === file.path; }).forEach(function (w) {
        item.appendChild(el('button', { class: 'wf', type: 'button', text: w.name, onclick: function () { state.stack = []; state.selected = null; showWorkflow(w.name, false); } }));
      });
      box.appendChild(item);
    });
  }

  function renderDiagnostics() {
    var box = clear($('diagnostics')), list = state.result.diagnostics || [];
    if (!list.length) { box.hidden = true; return; }
    box.hidden = false;
    var errors = list.filter(function (d) { return d.severity === 'error'; }).length;
    var label = errors ? plural(errors, 'error') + (list.length > errors ? ', ' + plural(list.length - errors, 'warning') : '') : plural(list.length, 'warning');
    var toggle = el('button', { type: 'button', class: errors ? 'error' : '', 'aria-expanded': String(state.diagOpen), onclick: function () { state.diagOpen = !state.diagOpen; renderDiagnostics(); } }, el('span', { text: state.diagOpen ? '▾' : '▸' }), el('span', { text: label }));
    box.appendChild(toggle);
    if (state.diagOpen) {
      box.appendChild(el('ul', null, list.map(function (d) {
        return el('li', null, el('button', { type: 'button', text: baseName(d.file) + (d.line ? ':' + d.line : ''), onclick: function () { send({ type: 'openSource', file: d.file, line: d.line || 1, beside: false }); } }), ' ' + d.message);
      })));
    }
  }
  function plural(n, word) { return n + ' ' + word + (n === 1 ? '' : 's'); }

  /* --------------------------------------------------------------- stale */

  function showStale(message) {
    state.stale = message;
    $('staleText').textContent = 'Showing the last good graph. ' + message.message;
    var open = $('staleOpen');
    open.hidden = !message.file;
    open.onclick = function () { if (message.file) { send({ type: 'openSource', file: message.file, line: message.line || 1, beside: false }); } };
    $('stale').hidden = false;
  }

  /* ------------------------------------------------------------ controls */

  $('workflow').addEventListener('change', function (e) { state.stack = []; state.selected = null; showWorkflow(e.target.value, false); });
  $('zoomIn').addEventListener('click', function () { if (state.view) { state.view.zoomBy(1.2); } });
  $('zoomOut').addEventListener('click', function () { if (state.view) { state.view.zoomBy(1 / 1.2); } });
  $('fit').addEventListener('click', function () { if (state.view) { state.view.fit(); } });
  $('mermaid').addEventListener('click', function () { if (state.workflow) { send({ type: 'copyMermaid', name: state.workflow }); } });
  $('legendButton').addEventListener('click', function () {
    var legend = $('legend'), open = legend.hidden;
    if (open) {
      clear(legend);
      G.legend().forEach(function (item) { legend.appendChild(el('span', { class: 'lg-' + item[0] }, el('i'), item[1])); });
    }
    legend.hidden = !open;
    $('legendButton').setAttribute('aria-expanded', String(open));
  });

  window.addEventListener('message', function (event) {
    var message = event.data || {};
    if (message.type === 'loading') { showLoading(message.file); }
    else if (message.type === 'graph') { applyGraph(message); }
    else if (message.type === 'stale') { showStale(message); }
    else if (message.type === 'highlight') { highlight(message.id); }
  });

  show('loading');
  send({ type: 'ready' });
})();
