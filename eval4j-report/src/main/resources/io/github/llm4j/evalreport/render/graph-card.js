/* eval4j report: the workflow graph card. Draws a workflow trace's graph with the shared renderer (graph-render.js)
   and lays the run over it: which steps were taken, missed or unexpected, and how often a step ran. Model text is
   never parsed as HTML. No network access. The functions that read the trace are pure, so they can be tested without
   a browser. */
(function (root, factory) {
  var api = factory(root);
  if (typeof module === 'object' && module.exports) { module.exports = api; }
  root.EvalGraphCard = api;
})(typeof globalThis !== 'undefined' ? globalThis : this, function (root) {
  'use strict';

  var MAX_DRAWN_NODES = 500;

  /** What to show for a trace: no card, a note that the graph is too big, or the graph. */
  function plan(w) {
    var nodes = ((w && w.graph && w.graph.nodes) || []);
    if (!nodes.length) { return { kind: 'none' }; }
    if (nodes.length > MAX_DRAWN_NODES) { return { kind: 'toolarge', nodes: nodes.length }; }
    return { kind: 'graph', nodes: nodes.length, empty: !(w.actualPath || []).length };
  }

  /** Model names by agent, read from the spend lines (the trace graph does not carry them). */
  function agentsFromSpend(w) {
    var out = {};
    (w.spend || []).forEach(function (l) { if (l.agent && l.model && !out[l.agent]) { out[l.agent] = { model: l.model }; } });
    return out;
  }

  /** Delegations that no step could be found for. */
  function unplacedCount(w) {
    return (w.events || []).filter(function (e) { return (e.type === 'delegate_start' || e.type === 'delegate_replayed') && !e.node; }).length;
  }

  function agentsOf(node) {
    var list = node.agent ? [node.agent] : [];
    var more = node.attrs && node.attrs.agents;
    (more || []).forEach(function (a) { if (list.indexOf(a) < 0) { list.push(a); } });
    return list;
  }

  /** The run's facts about one step: the events mapped to it, how long it took and what its agents spent. */
  function details(w, id) {
    var node = ((w.graph && w.graph.nodes) || []).filter(function (n) { return n.id === id; })[0];
    if (!node) { return null; }
    var events = (w.events || []).filter(function (e) { return e.node === id; })
      .map(function (e) { return { t: e.t, type: e.type, text: e.text || '' }; });
    var starts = events.filter(function (e) { return /_start$/.test(e.type); }).map(function (e) { return e.t; });
    var ends = events.filter(function (e) { return /_end$/.test(e.type); }).map(function (e) { return e.t; });
    var duration = starts.length && ends.length ? Math.max(0, Math.max.apply(null, ends) - Math.min.apply(null, starts)) : null;
    var spend = agentsOf(node).map(function (agent) {
      var lines = (w.spend || []).filter(function (l) { return l.agent === agent; });
      if (!lines.length) { return null; }
      var sum = { agent: agent, promptTokens: 0, completionTokens: 0, calls: 0, costUsd: null, estimated: false };
      lines.forEach(function (l) {
        sum.promptTokens += l.promptTokens || 0; sum.completionTokens += l.completionTokens || 0; sum.calls += l.calls || 0;
        if (l.costUsd != null) { sum.costUsd = (sum.costUsd || 0) + l.costUsd; }
        sum.estimated = sum.estimated || !!l.estimated;
      });
      return sum;
    }).filter(Boolean);
    return { node: node, events: events, duration: duration, spend: spend };
  }

  function fmtTime(t) { return (+t).toFixed(2) + ' s'; }
  function fmtUsd(x) { return x == null ? null : '$' + (x < 1 ? x.toFixed(4) : x.toFixed(2)); }

  /** The overlay for a workflow trace (see LoomGraph.overlay). */
  function overlayOf(w) { return root.LoomGraph.overlay(w.graph, w.expectedPath, w.actualPath); }

  /* ------------------------------------------------------------------ DOM */

  function plainH(tag, attrs) {
    var el = document.createElement(tag), i;
    Object.keys(attrs || {}).forEach(function (k) {
      if (attrs[k] == null || attrs[k] === false) { return; }
      if (k === 'class') { el.className = attrs[k]; } else if (k === 'text') { el.textContent = attrs[k]; } else if (k.slice(0, 2) === 'on') { el.addEventListener(k.slice(2), attrs[k]); } else { el.setAttribute(k, attrs[k] === true ? '' : attrs[k]); }
    });
    for (i = 2; i < arguments.length; i++) { append(el, arguments[i]); }
    return el;
  }
  function append(el, c) {
    if (c == null || c === false) { return; }
    if (Array.isArray(c)) { c.forEach(function (x) { append(el, x); }); return; }
    el.appendChild(typeof c === 'object' ? c : document.createTextNode(String(c)));
  }

  function legend(h) {
    var states = [['✓', 'taken as expected'], ['✗ missed', 'on the expected path, not taken'], ['! unexpected', 'taken, not expected'], ['●', 'taken (no expected path)'], ['×3', 'visited three times']];
    return h('details', { class: 'lg-legend' },
      h('summary', { text: 'Legend' }),
      h('div', { class: 'lg-legend-body' },
        root.LoomGraph.legend().map(function (e) { return h('span', { class: 'lg-key lg-' + e[0] }, h('i'), e[1]); }),
        states.map(function (e) { return h('span', { class: 'lg-key-state' }, h('b', { text: e[0] }), ' ' + e[1]); })));
  }

  function detailsView(h, d) {
    if (!d) { return h('p', { class: 'hint', text: 'Select a node to see what happened there.' }); }
    var n = d.node, chips = root.LoomGraph.chips(n);
    var rows = [h('dt', { text: 'Kind' }), h('dd', { text: n.kind }), h('dt', { text: 'Step' }), h('dd', { text: n.label || n.id })];
    if (n.agent) { rows.push(h('dt', { text: 'Agent' }), h('dd', { text: n.agent })); }
    if (chips.length) { rows.push(h('dt', { text: 'Settings' }), h('dd', null, h('span', { class: 'lg-chips' }, chips.map(function (c) { return h('span', { class: 'lg-chip ' + (c.v || ''), text: c.t }); })))); }
    if (d.duration != null) { rows.push(h('dt', { text: 'Took' }), h('dd', { text: fmtTime(d.duration) })); }
    d.spend.forEach(function (s) {
      var cost = fmtUsd(s.costUsd);
      rows.push(h('dt', { text: 'Spend' }), h('dd', { text: s.agent + ' (per agent): ' + (s.promptTokens + s.completionTokens) + ' tokens, ' + s.calls + (s.calls === 1 ? ' call' : ' calls') + (cost ? ', ' + cost : '') + (s.estimated ? ', estimated' : '') }));
    });
    return h('div', null, h('dl', { class: 'lg-dl' }, rows),
      d.events.length ? h('div', { class: 'lg-events' }, d.events.slice(0, 40).map(function (e) { return h('div', { class: 'lg-event' }, h('span', { class: 'mono num', text: fmtTime(e.t) }), h('span', { class: 'mono', text: e.type }), h('span', { text: e.text })); })) : h('p', { class: 'hint', text: 'No events were mapped to this step.' }));
  }

  /**
   * The card for one workflow trace, or null when there is no graph. `h` is the report's element helper.
   * The graph is drawn once the card is in the page and has a size.
   */
  function create(w, h) {
    h = h || plainH;
    var p = plan(w);
    if (p.kind === 'none') { return null; }
    if (p.kind === 'toolarge') { return h('p', { class: 'note', text: 'Graph too large to draw (' + p.nodes + ' nodes). The path is listed below.' }); }
    var canvas = h('div', { class: 'lg-canvas' }), side = h('div', { class: 'lg-side' }, detailsView(h, null));
    var notes = [h('p', { class: 'hint', text: 'Path is inferred from the order of delegations.' })];
    if (p.empty) { notes.push(h('p', { class: 'hint', text: 'No steps were recorded.' })); }
    var unplaced = unplacedCount(w);
    if (unplaced) { notes.push(h('p', { class: 'hint', text: unplaced + (unplaced === 1 ? ' event' : ' events') + ' could not be placed on a step.' })); }
    var view = null;
    function tool(label, text, fn) { return h('button', { class: 'iconbtn lg-tool', type: 'button', 'aria-label': label, text: text, onclick: fn }); }
    var card = h('div', { class: 'lgcard' },
      h('div', { class: 'lg-head' }, h('span', { class: 'eyebrow', text: 'Workflow graph' }),
        h('span', { class: 'lg-tools' }, tool('Zoom out', '−', function () { if (view) { view.zoomBy(1 / 1.2); } }), tool('Zoom in', '+', function () { if (view) { view.zoomBy(1.2); } }), tool('Fit to view', '⤢', function () { if (view) { view.fit(); } })),
        legend(h)),
      h('div', { class: 'lg-body' }, canvas, side), notes);
    function mount() {
      if (view) { return; }
      if (!canvas.isConnected) { root.requestAnimationFrame(mount); return; }
      view = new root.LoomGraph.View(canvas, {
        onSelect: function (id) { clear(side); side.appendChild(detailsView(h, id ? details(w, id) : null)); }
      });
      view.setOverlay(overlayOf(w));
      view.setGraph(w.graph, { agents: agentsFromSpend(w) });
      if (root.ResizeObserver) { new root.ResizeObserver(function () { if (view && !view.userMoved) { view.fit(); } }).observe(canvas); }
    }
    function clear(el) { while (el.firstChild) { el.removeChild(el.firstChild); } }
    root.requestAnimationFrame(mount);
    return card;
  }

  return { create: create, plan: plan, details: details, agentsFromSpend: agentsFromSpend, unplacedCount: unplacedCount, overlayOf: overlayOf, MAX_DRAWN_NODES: MAX_DRAWN_NODES };
});
