/* The workflow graph panel, inside a VS Code webview. It draws what the extension sends (see src/graph/protocol.ts)
   as three views of one graph (Outline, Focus, Flow; see views.js) over a main-path rail, and sends back only the
   messages that protocol lists. Text from a script is put in the page with textContent, never as HTML. */
(function () {
  'use strict';
  var vscode = acquireVsCodeApi();
  var $ = function (id) { return document.getElementById(id); };
  var G = window.LoomGraph, M = window.LoomModel, V = window.LoomViews;
  var VIEWS = ['outline', 'focus', 'flow'];

  var saved = (function () { try { return vscode.getState() || {}; } catch (e) { return {}; } })();
  var state = {
    result: null, workflow: null, stack: [], sections: {}, model: null, selected: null, fromCursor: false,
    view: VIEWS.indexOf(saved.view) >= 0 ? saved.view : 'outline', collapsed: new Set(), stale: null, diagOpen: false, toastTimer: null
  };

  /* ------------------------------------------------------------ helpers */

  function el(tag, attrs) {
    var node = document.createElement(tag), i;
    Object.keys(attrs || {}).forEach(function (key) {
      var value = attrs[key];
      if (value == null || value === false) { return; }
      if (key === 'class') { node.className = value; }
      else if (key === 'text') { node.textContent = value; }
      else if (key.slice(0, 2) === 'on') { node.addEventListener(key.slice(2), value); }
      else { node.setAttribute(key, value === true ? '' : value); }
    });
    for (i = 2; i < arguments.length; i++) { add(node, arguments[i]); }
    return node;
  }
  function add(node, child) {
    if (child == null || child === false) { return; }
    if (Array.isArray(child)) { child.forEach(function (c) { add(node, c); }); return; }
    node.append(child instanceof Node ? child : String(child)); // append() turns a string into a text node, never markup
  }
  function clear(node) { while (node.firstChild) { node.removeChild(node.firstChild); } return node; }
  function baseName(path) { var parts = String(path || '').split(/[\\/]/); return parts[parts.length - 1]; }
  function send(message) { vscode.postMessage(message); }
  function workflowNamed(name, file) {
    var list = state.result ? state.result.workflows : [];
    return list.filter(function (w) { return w.name === name && (file == null || w.file === file); })[0] || list.filter(function (w) { return w.name === name; })[0];
  }
  function agentMap() {
    var out = Object.create(null);
    ((state.result && state.result.agents) || []).forEach(function (a) { out[a.name] = a; });
    return out;
  }
  function budgetLine(b) {
    var text = G.budgetText(b || {});
    return text ? text.replace(/^budget /, '') : '';
  }
  function currentWorkflow() { return workflowNamed(state.workflow); }
  function persist() { try { vscode.setState({ view: state.view }); } catch (e) { /* the state is a convenience */ } }

  /* -------------------------------------------------------------- states */

  function show(which) {
    $('loading').hidden = which !== 'loading';
    $('empty').hidden = which !== 'empty';
    ['mermaid', 'exportPng', 'workflow'].forEach(function (id) { $(id).disabled = which !== 'graph'; });
    Array.prototype.forEach.call(document.querySelectorAll('.vbtn'), function (b) { b.disabled = which !== 'graph'; });
    $('viewHost').hidden = which !== 'graph';
    $('footer').hidden = which !== 'graph';
    if (which !== 'graph') { $('crumbs').hidden = true; }
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
    state.sections = message.sections || {};
    state.stale = null; $('stale').hidden = true;
    renderRunBudget(); renderFiles(); renderDiagnostics();
    if (!message.result.workflows.length) { renderWorkflowSelect(null); state.model = null; showEmpty(); return; }
    var wanted = (previous && workflowNamed(previous)) ? previous : message.workflow;
    if (!workflowNamed(wanted)) { wanted = message.result.workflows[0].name; } // the extension named one that is not there
    var keep = !!previous && wanted === previous && !!state.model;
    state.stack = keep ? state.stack.filter(function (n) { return !!workflowNamed(n); }) : [];
    show('graph');
    showWorkflow(wanted, keep);
  }

  function showWorkflow(name, keep) {
    var wf = workflowNamed(name);
    if (!wf) { return; }
    state.workflow = wf.name;
    state.model = M.build(wf, { sections: state.sections[wf.name + '@' + wf.file] });
    if (!keep) { state.collapsed = new Set(); }
    else { state.collapsed = new Set(Array.from(state.collapsed).filter(function (id) { return !!state.model.by[id]; })); }
    if (!state.selected || !state.model.by[state.selected]) { state.selected = defaultSelection(); }
    renderWorkflowSelect(wf.name);
    renderAll(true);
    send({ type: 'selectWorkflow', name: wf.name });
  }

  function defaultSelection() {
    var m = state.model;
    var first = m.main.filter(function (id) { return m.by[id].kind !== 'start'; })[0];
    return first || (m.nodes[0] && m.nodes[0].id) || null;
  }

  /* -------------------------------------------------------------- toolbar */

  function renderWorkflowSelect(selected) {
    var select = $('workflow'); clear(select);
    if (!state.result) { return; }
    var entryFile = state.result.entry, groups = Object.create(null), order = [];
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

  function renderAgents() {
    var box = clear($('agents')), list = (state.result && state.result.agents) || [];
    $('agentsBox').hidden = !list.length;
    list.forEach(function (a) {
      var tools = a.tools || [], asks = a.approveAll ? tools : (a.approve || []);
      var uses = state.model ? state.model.nodes.filter(function (n) { return n.agent === a.name; })[0] : null;
      box.appendChild(uses ? el('button', { class: 'an', type: 'button', text: a.name, onclick: function () { select(uses.id, { source: true, reveal: true }); } }) : el('span', { text: a.name }));
      box.appendChild(el('span', { class: 'muted', text: a.model || '' }));
      box.appendChild(el('span', { class: 'chips' }, tools.length ? tools.map(function (t) { return el('span', { class: 'chip tool' + (asks.indexOf(t) >= 0 ? ' asks' : ''), text: t }); }) : el('span', { class: 'chip tool none', text: 'no tools' })));
    });
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

  function renderViewSwitch() {
    Array.prototype.forEach.call(document.querySelectorAll('.vbtn'), function (b) { b.setAttribute('aria-pressed', String(b.getAttribute('data-view') === state.view)); });
  }

  function setView(view) {
    if (VIEWS.indexOf(view) < 0 || view === state.view) { return; }
    state.view = view; persist();
    renderAll(true);
  }

  /* ------------------------------------------------------------ breadcrumb */

  function renderCrumbs() {
    var bar = $('crumbs'), wf = currentWorkflow();
    if (!wf || !state.model) { bar.hidden = true; return; }
    bar.hidden = false; clear(bar);
    if (state.stack.length) { bar.appendChild(el('button', { class: 'crumb', type: 'button', text: '‹ Back', onclick: goBack })); }
    var label = wf.name + '(' + wf.params.join(', ') + ')';
    var list = V.crumbs(state.model, state.selected, label, baseName(wf.file), state.stack);
    list.forEach(function (c, i) {
      var last = i === list.length - 1;
      bar.appendChild(el('button', { class: 'crumb' + (c.mono ? ' mono' : '') + (last ? ' here' : ''), type: 'button', text: c.text, 'aria-current': last ? 'location' : null, onclick: function () { crumbAction(c.act, wf); } }));
      if (!last) { bar.appendChild(el('span', { class: 'sep', 'aria-hidden': 'true', text: '›' })); }
    });
    if (wf.file !== state.result.entry) { bar.appendChild(el('span', { class: 'filechip', text: baseName(wf.file) })); }
  }

  function crumbAction(act, wf) {
    if (act.file) { send({ type: 'openSource', file: wf.file, line: wf.line || 1, beside: true }); }
    else if (act.pop != null) { var target = state.stack[act.pop]; state.stack = state.stack.slice(0, act.pop); state.selected = null; showWorkflow(target, false); }
    else if (act.workflow) { select(defaultSelection(), { source: false, reveal: true }); }
    else if (act.select) { select(act.select, { source: false, reveal: true }); }
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

  /** Selects a step. `source`: also show its line beside the panel. `reveal`: scroll to it. */
  function select(id, how) {
    how = how || {};
    var node = id && state.model && state.model.by[id];
    if (!node) { return; }
    var before = state.selected;
    state.selected = id; state.fromCursor = !!how.fromCursor;
    renderAll(how.reveal !== false || before !== id);
    if (how.source && node.file && node.line != null) { send({ type: 'openSource', file: node.file, line: node.line, beside: true }); }
  }

  function activate(node, how) {
    if (!node || node.kind !== 'call' || !node.raw.call || node.raw.unresolved) { return; }
    if (how && how.ctrl) {
      var callee = workflowNamed(node.raw.call.workflow, node.raw.call.file);
      if (callee) { send({ type: 'openSource', file: callee.file, line: callee.line || 1, beside: true }); }
      return;
    }
    drill(node.raw.call);
  }

  function highlight(id) {
    if (!state.model) { return; }
    if (id && state.model.by[id]) {
      select(id, { fromCursor: true, reveal: true });
    } else if (!id && state.fromCursor && state.selected) {
      state.fromCursor = false; // the step the cursor left stays selected: nothing else would tell the person where they were
    }
  }

  /* ------------------------------------------------------------- drawing */

  function context() {
    return {
      view: state.view, model: state.model, selected: state.selected, collapsed: state.collapsed, agents: agentMap(),
      chips: function (raw) { return G.chips(raw); }, budgetLine: budgetLine,
      on: {
        select: function (id, reveal) { select(id, { source: true, reveal: reveal !== false }); },
        source: function (n) { if (n.file) { send({ type: 'openSource', file: n.file, line: n.line || 1, beside: true }); } },
        prompt: function (agent) { if (agent.prompt && agent.prompt.file) { send({ type: 'openSource', file: agent.prompt.file, line: 1, beside: true }); } },
        drill: function (n) { activate(n, {}); },
        view: setView,
        toggle: function (id) { if (state.collapsed.has(id)) { state.collapsed.delete(id); } else { state.collapsed.add(id); } renderAll(false); },
        expandAll: function () { state.collapsed = new Set(); renderAll(false); },
        collapseAll: function () { state.collapsed = new Set(state.model.nodes.filter(function (n) { return n.children.length; }).map(function (n) { return n.id; })); renderAll(false); }
      }
    };
  }

  function focusMemory() {
    var a = document.activeElement;
    if (!a || a === document.body) { return null; }
    var where = ['viewHost', 'rail', 'railSecs'].filter(function (id) { return $(id).contains(a); })[0];
    if (!where) { return null; }
    return { where: where, cls: a.classList.contains('card') ? 'card' : a.classList.contains('row') ? 'row' : null, id: a.getAttribute('data-id'), index: Array.prototype.indexOf.call($(where).children, a), toggle: a.getAttribute('data-toggle'), tag: a.tagName };
  }

  function restoreFocus(mem) {
    if (!mem) { return; }
    var root = $(mem.where), target = null;
    if (mem.id && mem.cls) { target = root.querySelector('.' + mem.cls + '[data-id="' + mem.id.replace(/"/g, '') + '"]'); }
    else if (mem.id) { target = root.querySelector('[data-id="' + mem.id.replace(/"/g, '') + '"]'); }
    else if (mem.toggle) { target = root.querySelector('[data-toggle="' + mem.toggle.replace(/"/g, '') + '"]'); }
    else if (mem.index >= 0 && root.children[mem.index] && root.children[mem.index].tagName === mem.tag) { target = root.children[mem.index]; }
    if (target && typeof target.focus === 'function') { target.focus({ preventScroll: true }); }
  }

  function scrollsOf(host) {
    var out = Object.create(null);
    ['.tree', '.centre', '.flow', '.focus'].forEach(function (sel) { var e = host.querySelector(sel); if (e) { out[sel] = [e.scrollLeft, e.scrollTop]; } });
    return out;
  }

  var drawn = { view: null, workflow: null };

  /** Draws the current view, the rail and the breadcrumb. `reveal`: bring the selected step into view afterwards. */
  function renderAll(reveal) {
    if (!state.model) { return; }
    var host = $('viewHost'), mem = focusMemory(), same = drawn.view === state.view && drawn.workflow === state.workflow, scrolls = same ? scrollsOf(host) : {};
    var ctx = context();
    clear(host);
    host.appendChild(V.render(ctx));
    Object.keys(scrolls).forEach(function (sel) { var e = host.querySelector(sel); if (e) { e.scrollLeft = scrolls[sel][0]; e.scrollTop = scrolls[sel][1]; } });
    drawn = { view: state.view, workflow: state.workflow };
    if (reveal) { V.reveal(host, ctx); }
    renderViewSwitch(); renderCrumbs(); renderAgents();
    V.rail(ctx, { note: $('railNote'), bar: $('rail'), sections: $('railSecs') });
    restoreFocus(mem);
  }

  /* ----------------------------------------------------- interaction in views */

  function nodeOfEvent(e) {
    var item = e.target.closest && e.target.closest('.card[data-id], .row[data-id]');
    return item ? state.model && state.model.by[item.getAttribute('data-id')] : null;
  }

  $('viewHost').addEventListener('click', function (e) {
    if (e.target.closest && e.target.closest('[data-toggle], button')) { return; }
    var n = nodeOfEvent(e);
    if (!n) { return; }
    if ((e.ctrlKey || e.metaKey) && n.kind === 'call') { activate(n, { ctrl: true }); return; }
    select(n.id, { source: true, reveal: false });
    // a double-click is the second click (detail 2): the first one redrew the view, so the browser's own dblclick would miss
    if (e.detail === 2) { activate(n, {}); }
  });
  $('viewHost').addEventListener('keydown', function (e) {
    var item = e.target.closest && e.target.closest('.card[data-id], .row[data-id]');
    if (!item || e.target !== item) { return; }
    var n = state.model.by[item.getAttribute('data-id')];
    if (e.key === 'Enter' || e.key === ' ') {
      e.preventDefault();
      if (e.shiftKey && e.key === 'Enter') { activate(n, {}); } else { select(n.id, { source: true, reveal: false }); }
    }
  });

  document.addEventListener('keydown', function (e) {
    if (!state.model || e.ctrlKey || e.metaKey || e.altKey) { return; }
    var tag = e.target && e.target.tagName;
    if (tag === 'SELECT' || tag === 'INPUT' || tag === 'TEXTAREA') { return; }
    if (e.key === '1' || e.key === '2' || e.key === '3') { setView(VIEWS[Number(e.key) - 1]); return; }
    var m = state.model, order = M.visibleOrder(m, state.collapsed), at = order.map(function (n) { return n.id; }).indexOf(state.selected);
    if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
      var step = e.key === 'ArrowDown' ? 1 : -1, active = document.activeElement, onItem = active && active.getAttribute && active.getAttribute('data-id') && active.closest('#viewHost');
      var from = onItem ? order.map(function (n) { return n.id; }).indexOf(active.getAttribute('data-id')) : at;
      var next = order[from + step] || (from < 0 ? order[0] : null);
      if (!next) { return; }
      e.preventDefault();
      if (state.view === 'focus' || !onItem) { select(next.id, { source: false, reveal: true }); return; }
      var target = $('viewHost').querySelector((state.view === 'outline' ? '.row' : '.stage .card') + '[data-id="' + next.id + '"]');
      if (target) { target.focus(); }
      return;
    }
    if ((e.key === 'ArrowLeft' || e.key === 'ArrowRight') && state.view === 'focus') {
      var edges = e.key === 'ArrowLeft' ? m.inn[state.selected] : m.out[state.selected];
      var other = edges && edges[0] && (e.key === 'ArrowLeft' ? edges[0].from : edges[0].to);
      if (other) { e.preventDefault(); select(other, { source: false, reveal: true }); }
    }
  });

  /* --------------------------------------------------------------- toast */

  function toast(text) {
    var box = $('toast');
    box.textContent = text; box.hidden = false;
    if (state.toastTimer) { clearTimeout(state.toastTimer); }
    state.toastTimer = setTimeout(function () { box.hidden = true; state.toastTimer = null; }, 2600);
  }

  /* -------------------------------------------------------------- export */

  function exportName() {
    var wf = currentWorkflow(), script = baseName(state.result.entry).replace(/\.[^.]*$/, '');
    return (script + '-' + (wf ? wf.name : 'workflow') + '-' + state.view + '.png').replace(/[^\w.\- ]+/g, '_');
  }

  function exportPng() {
    var target = $('viewHost').querySelector('[data-export]');
    if (!target || !state.model) { return; }
    var background = getComputedStyle(document.body).backgroundColor || '#ffffff', name = exportName();
    V.snapshot(target, background, 2).then(function (url) {
      return url.length > 40 * 1024 * 1024 ? V.snapshot(target, background, 1) : url;
    }).then(function (url) {
      send({ type: 'exportPng', name: name, dataUrl: url });
    }).catch(function () { toast('The picture could not be drawn.'); });
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
  Array.prototype.forEach.call(document.querySelectorAll('.vbtn'), function (b) { b.addEventListener('click', function () { setView(b.getAttribute('data-view')); }); });
  $('mermaid').addEventListener('click', function () { if (state.workflow) { send({ type: 'copyMermaid', name: state.workflow }); } });
  $('exportPng').addEventListener('click', exportPng);
  $('exportIcon').appendChild(V.svg('download'));
  V.legend($('legend'));

  window.addEventListener('message', function (event) {
    var message = event.data || {};
    if (message.type === 'loading') { showLoading(message.file); }
    else if (message.type === 'graph') { applyGraph(message); }
    else if (message.type === 'stale') { showStale(message); }
    else if (message.type === 'highlight') { highlight(message.id); }
    else if (message.type === 'exported') { if (message.file) { toast('Saved ' + message.file); } }
  });

  show('loading');
  renderViewSwitch();
  send({ type: 'ready' });
})();
