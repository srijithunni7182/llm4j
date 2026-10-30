/*
 * The view model of the Loom Graph panel: what the three views (Outline, Focus, Flow) and the main-path rail share.
 * It takes one workflow of a `weave graph` result and works out, with no DOM, which section each step belongs to, how
 * deep it sits, which steps are the main path and which are exits, and what kind of step each one is for the pictures.
 *
 * Plain JavaScript with no imports, so the panel loads it as a script and the tests load it with require.
 */
(function (root, factory) {
  var api = factory();
  if (typeof module === 'object' && module.exports) { module.exports = api; }
  root.LoomModel = api;
})(typeof globalThis !== 'undefined' ? globalThis : this, function () {
  'use strict';

  /* ------------------------------------------------------------------ kinds */

  /** Hue and chroma of each kind's tone, a glyph name (see views.js), a badge shape and the name the legend shows. */
  var VK = {
    agent: { name: 'Agent', glyph: 'bot', shape: 'square', h: 250, c: 0.09 },
    task: { name: 'Task (code)', glyph: 'terminal', shape: 'sharp', h: 185, c: 0.08 },
    verdict: { name: 'Verdict', glyph: 'scale', shape: 'rhombus', h: 80, c: 0.1 },
    check: { name: 'Safety outcome', glyph: 'shield', shape: 'rhombus', h: 80, c: 0.1 },
    approval: { name: 'Approval', glyph: 'usercheck', shape: 'rhombus', h: 80, c: 0.1 },
    branch: { name: 'Branch', glyph: 'split', shape: 'rhombus', h: 80, c: 0.1 },
    loop: { name: 'Loop', glyph: 'spiral', shape: 'circle', h: 300, c: 0.1 },
    human: { name: 'Ask a person', glyph: 'user', shape: 'circle', h: 40, c: 0.11 },
    note: { name: 'Note', glyph: 'note', shape: 'sharp', h: 250, c: 0.012 },
    exit: { name: 'Ends the run', glyph: 'stop', shape: 'sharp', h: 25, c: 0.07, dashed: true },
    term: { name: 'Start / end', glyph: 'flag', shape: 'circle', h: 250, c: 0.02 },
    parallel: { name: 'In parallel', glyph: 'columns', shape: 'square', h: 145, c: 0.09 },
    call: { name: 'Call a workflow', glyph: 'callout', shape: 'double', h: 330, c: 0.09 },
    guardrail: { name: 'Guardrail', glyph: 'shieldplain', shape: 'sharp', h: 185, c: 0.08 },
    decide: { name: 'Decision', glyph: 'scale', shape: 'square', h: 330, c: 0.09 },
    checkpoint: { name: 'Checkpoint', glyph: 'flag', shape: 'sharp', h: 250, c: 0.03 },
    rewind: { name: 'Rewind', glyph: 'rewind', shape: 'sharp', h: 300, c: 0.08 },
    outcome: { name: 'Stop outcome', glyph: 'stop', shape: 'sharp', h: 40, c: 0.08 },
    observe: { name: 'Observe', glyph: 'eye', shape: 'sharp', h: 250, c: 0.03 },
    sleep: { name: 'Sleep', glyph: 'eyeclosed', shape: 'sharp', h: 250, c: 0.03 },
    unknown: { name: 'Step', glyph: 'unknown', shape: 'sharp', h: 250, c: 0.012 }
  };

  /** The kinds the footer legend lists, in order. */
  var LEGEND = ['agent', 'task', 'verdict', 'check', 'approval', 'branch', 'loop', 'human', 'note', 'exit'];

  var BY_KIND = {
    delegate: 'agent', handoff: 'agent', broadcast: 'agent', task: 'task', loop: 'loop', foreach: 'loop',
    human_prompt: 'human', note: 'note', start: 'term', end: 'term', parallel: 'parallel', call: 'call',
    guardrail: 'guardrail', decide: 'decide', checkpoint: 'checkpoint', rewind: 'rewind', observe: 'observe', sleep: 'sleep', outcome: 'outcome',
    halt: 'exit', set: 'note', return: 'term'
  };

  var CONTAINERS = { alt: 1, loop: 1, foreach: 1, guardrail: 1, parallel: 1 };
  var LOOPS = { loop: 1, foreach: 1 };
  var BACK = { again: 1, rewind: 1 };
  var HANDLERS = { failure: 1, exhausted: 1, violation: 1, 'still fails': 1, blocked: 1, stopped: 1 };

  function has(table, key) { return Object.prototype.hasOwnProperty.call(table, key); }
  function attrsOf(node) { return node.attrs || {}; }
  function parentOf(node) { return node.parent != null ? node.parent : attrsOf(node).parent; }
  function lineOf(node) { return node.source && typeof node.source.line === 'number' ? node.source.line : null; }

  /** The variable a condition such as `outcome == "blocked"` or `approval==yes?` reads. */
  function conditionVariable(node) {
    var text = String(attrsOf(node).condition || node.label || '').replace(/^\s*(if|until|while)\s+/, '');
    var m = /^\s*([A-Za-z_][\w.]*)/.exec(text);
    return m ? m[1] : '';
  }

  /** What an `alt` tests: an agent's `expecting` field, a task's result, a person's answer, or anything else. */
  function classifyAlt(node, producers) {
    var variable = conditionVariable(node), root = variable.split('.')[0];
    var producer = producers[variable] || producers[root];
    if (producer) {
      if (producer.kind === 'human_prompt') { return 'approval'; }
      if (producer.kind === 'task') { return 'check'; }
      if (attrsOf(producer).expecting) { return 'verdict'; }
      if (producer.kind === 'delegate') { return 'branch'; }
    }
    if (/verdict|has_script/.test(variable)) { return 'verdict'; }
    if (/outcome/.test(variable)) { return 'check'; }
    if (/approval|approved/.test(variable)) { return 'approval'; }
    return 'branch';
  }

  /* ------------------------------------------------------------------ build */

  /**
   * @param {{name:string, nodes:Array, edges:Array, sections?:Array}} workflow one workflow of a GraphResult
   * @param {{sections?:Array<{n:number,title:string,hint?:string,line:number}>}} [options]
   *        sections: the numbered comments of the script; wins over workflow.sections; none means one section
   */
  function build(workflow, options) {
    options = options || {};
    var raw = workflow.nodes || [], edges = workflow.edges || [];

    // source order: by line where there is one, otherwise where the graph listed the step; start first, end last
    var order = Object.create(null);
    raw.forEach(function (n, i) { order[n.id] = i; });
    var sorted = raw.slice().sort(function (a, b) {
      if (a.kind === 'start' || b.kind === 'end') { return a.kind === b.kind ? 0 : -1; }
      if (b.kind === 'start' || a.kind === 'end') { return 1; }
      var la = lineOf(a), lb = lineOf(b);
      if (la != null && lb != null && la !== lb) { return la - lb; }
      return order[a.id] - order[b.id];
    });

    if (options.workflows) {
      var inlined = inlineCalls(sorted, workflow, options.workflows, edges);
      sorted = inlined.nodes; edges = inlined.edges;
    }

    var by = Object.create(null), nodes = sorted.map(function (n, i) {
      var vm = { id: n.id, raw: n, kind: n.kind, label: n.label || n.id, line: lineOf(n), file: n.source ? n.source.file : null, agent: n.agent || null,
        parent: parentOf(n) || null, branch: n.branch != null ? n.branch : attrsOf(n).branch, depth: 0, section: 0, main: false, exit: false, vk: 'unknown', index: i, children: [] };
      by[n.id] = vm;
      return vm;
    });
    nodes.forEach(function (n) { if (n.parent && !by[n.parent]) { n.parent = null; } });
    nodes.forEach(function (n) {
      var d = 0, p = n.parent, guard = 0;
      while (p && guard++ < 200) { d++; p = by[p] ? by[p].parent : null; }
      n.depth = d;
      if (n.parent) { by[n.parent].children.push(n.id); }
    });

    edges = detachLanes(nodes, by, edges);

    var out = Object.create(null), inn = Object.create(null);
    nodes.forEach(function (n) { out[n.id] = []; inn[n.id] = []; });
    edges.forEach(function (e) {
      if (!by[e.from] || !by[e.to]) { return; }
      var edge = { from: e.from, to: e.to, label: e.label || '' };
      out[e.from].push(edge); inn[e.to].push(edge);
    });

    // sections
    var sections = normalizeSections(options.sections || workflow.sections, workflow, nodes);
    // a step in a block sits in its parent's section, wherever its lines fall; the rest go by the section comments
    var byDepth = nodes.slice().sort(function (a, b) { return a.depth - b.depth || a.index - b.index; });
    byDepth.forEach(function (n) {
      if (typeof n.raw.section === 'number') { n.section = Math.max(0, Math.min(sections.length - 1, n.raw.section)); return; }
      if (n.parent) { n.section = by[n.parent].section; return; }
      if (n.kind === 'end') { n.section = sections.length - 1; return; }
      if (n.line == null || sections.length === 1) { return; }
      var s = 0;
      for (var i = 0; i < sections.length; i++) { if (sections[i].line != null && n.line >= sections[i].line) { s = i; } }
      n.section = s;
    });

    // exits: a note inside an alt branch that leads only to `end`
    nodes.forEach(function (n) {
      if (n.kind !== 'note' || !n.parent || by[n.parent].kind !== 'alt') { return; }
      var o = out[n.id];
      n.exit = o.length > 0 ? o.every(function (e) { return by[e.to].kind === 'end'; }) : false;
    });

    // kinds
    var producers = Object.create(null);
    nodes.forEach(function (n) { var v = attrsOf(n.raw).variable; if (v && !producers[v]) { producers[v] = n.raw; } });
    nodes.forEach(function (n) {
      if (n.exit) { n.vk = 'exit'; }
      else if (n.kind === 'alt') { n.vk = classifyAlt(n.raw, producers); }
      else { n.vk = has(BY_KIND, n.kind) ? BY_KIND[n.kind] : 'unknown'; }
    });

    var main = mainPath(nodes, by, out, inn);
    var onMain = Object.create(null);
    main.forEach(function (id) { onMain[id] = true; by[id].main = true; });
    nodes.forEach(function (n) { n.anchor = anchorOf(n, by, onMain, nodes); });

    sections.forEach(function (s, i) { s.index = i; s.main = main.filter(function (id) { return by[id].section === i; }); });
    if (options.groupSets !== false) { groupSets(main, by); }

    var endless = !main.some(function (id) { return by[id].kind === 'end'; });
    return { name: workflow.name, nodes: nodes, by: by, out: out, inn: inn, sections: sections, main: main, endless: endless, hasSections: sections.length > 1 || !!sections[0].real };
  }

  /**
   * Consecutive `set` steps on the main path (in the same block) are drawn as one card, so a line of initialisations does not bury the
   * steps that matter. This is only how they are drawn: every set keeps its node, line and place, so selection, breakpoints and links
   * to a step still address each one. The first set of a run holds `setGroup` (its members' ids); the others point at it by `inSetGroup`.
   */
  function groupSets(main, by) {
    var run = [];
    function close() {
      if (run.length > 1) {
        by[run[0]].setGroup = { ids: run.slice() };
        run.slice(1).forEach(function (id) { by[id].inSetGroup = run[0]; });
      }
      run = [];
    }
    main.forEach(function (id) {
      var n = by[id];
      if (n.kind === 'set' && (!run.length || by[run[0]].parent === n.parent)) { run.push(id); } else { close(); if (n.kind === 'set') { run.push(id); } }
    });
    close();
  }

  /**
   * A parallel block with `lanes` (an older engine has none): its branches are drawn as lanes under it, so the edges that fork into
   * them and join out of them are not the main path. The block leads straight on to what follows the lanes, a failure handler keeps
   * its edge from its step, and the block and each of its steps learn their lanes. Returns the edges the main path should read.
   */
  function detachLanes(nodes, by, edges) {
    nodes.forEach(function (owner) {
      var lanes = attrsOf(owner.raw).lanes;
      if (!Array.isArray(lanes) || !lanes.length) { return; }
      var member = Object.create(null), clean = [];
      lanes.forEach(function (lane, i) {
        var ids = (lane && Array.isArray(lane.nodes) ? lane.nodes : []).filter(function (id) { return has(by, id) && !member[id]; });
        ids.forEach(function (id) { member[id] = true; by[id].lane = { owner: owner.id, index: i }; });
        if (ids.length) { clean.push({ label: String(lane.label || by[ids[0]].label), nodes: ids }); }
      });
      if (!clean.length) { return; }
      owner.lanes = clean;
      var joins = [], kept = [];
      edges.forEach(function (e) {
        if (e.from === owner.id && member[e.to]) { return; }
        if (member[e.from] && !member[e.to] && !has(BACK, e.label)) { if (joins.indexOf(e.to) < 0) { joins.push(e.to); } return; }
        kept.push(e);
      });
      joins.forEach(function (to) { kept.push({ from: owner.id, to: to, label: '' }); });
      edges = kept;
    });
    return edges;
  }

  /**
   * Draws each call to a workflow with the called workflow's steps inside it: they become children of the call step
   * (ids prefixed with the call's, so one workflow called twice stays two copies). The callee's own start and end are
   * left out, and a call inside a callee is opened the same way, unless the workflow is already being opened (recursion).
   * Returns the nodes with each callee's steps right after their call, and the edges with the callee's added.
   */
  function inlineCalls(sorted, workflow, workflows, edges) {
    function find(call) {
      var list = workflows.filter(function (w) { return w.name === call.workflow; });
      return list.filter(function (w) { return call.file != null && w.file === call.file; })[0] || list[0] || null;
    }
    var outNodes = [], outEdges = edges.slice(), budget = 5000;
    function expand(list, stack) {
      list.forEach(function (n) {
        outNodes.push(n);
        if (n.kind !== 'call' || !n.call || n.unresolved) { return; }
        var callee = find(n.call);
        if (!callee || stack.indexOf(callee.name + '@' + callee.file) >= 0 || budget <= 0) { return; }
        var key = callee.name + '@' + callee.file, id = n.id, own = (callee.nodes || []).filter(function (c) { return c.kind !== 'start' && c.kind !== 'end'; });
        budget -= own.length;
        var kept = Object.create(null), order = Object.create(null);
        own.forEach(function (c) { kept[c.id] = true; });
        (callee.nodes || []).forEach(function (c, i) { order[c.id] = i; });
        var copies = own.map(function (c) {
          var copy = Object.assign({}, c, { id: id + '>' + c.id, parent: c.parent && kept[c.parent] ? id + '>' + c.parent : id });
          delete copy.section;
          if (copy.attrs) { copy.attrs = Object.assign({}, copy.attrs); delete copy.attrs.parent; delete copy.attrs.section; }
          return copy;
        });
        (callee.edges || []).forEach(function (e) {
          if (kept[e.from] && kept[e.to]) { outEdges.push({ from: id + '>' + e.from, to: id + '>' + e.to, label: e.label }); }
        });
        // the callee in its own source order, with the calls inside it opened in turn
        copies.sort(function (a, b) {
          var la = lineOf(a), lb = lineOf(b);
          if (la != null && lb != null && la !== lb) { return la - lb; }
          return order[a.id.slice(id.length + 1)] - order[b.id.slice(id.length + 1)];
        });
        expand(copies, stack.concat(key));
      });
    }
    expand(sorted, [workflow.name + '@' + workflow.file]);
    return { nodes: outNodes, edges: outEdges };
  }

  function normalizeSections(list, workflow, nodes) {
    var real = (list || []).filter(function (s) { return s && typeof s.title === 'string'; }).map(function (s, i) {
      return { n: typeof s.n === 'number' ? s.n : i + 1, title: s.title, hint: s.hint || '', line: typeof s.line === 'number' ? s.line : null, real: true };
    });
    if (!real.length) { return [{ n: 1, title: workflow.name || 'Workflow', hint: '', line: -Infinity, real: false }]; }
    real.sort(function (a, b) { return (a.line == null ? 0 : a.line) - (b.line == null ? 0 : b.line); });
    return real;
  }

  /* -------------------------------------------------------------- main path */

  function isBack(edge) { return has(BACK, edge.label); }

  /**
   * The happy path: from start, at each step the forward edge that does not turn into an exit and does not go round
   * again. See README "Data needed beyond today's GraphResult" for the rules this follows.
   */
  function mainPath(nodes, by, out, inn) {
    var start = nodes.filter(function (n) { return n.kind === 'start'; })[0];
    if (!start) { return []; }
    var path = [], seen = Object.create(null), cur = start, guard = 0;

    function inSubtree(id, rootId) {
      var p = by[id], hops = 0;
      while (p && hops++ < 200) { if (p.id === rootId) { return true; } p = p.parent ? by[p.parent] : null; }
      return false;
    }
    function enclosingLoop(n) {
      var p = n.parent ? by[n.parent] : null, hops = 0;
      while (p && hops++ < 200) { if (has(LOOPS, p.kind)) { return p; } p = p.parent ? by[p.parent] : null; }
      return null;
    }
    // an alt in a loop whose every way on leads back into the loop only decides whether to go round again
    function repeatDecision(n) {
      if (n.kind !== 'alt' || !enclosingLoop(n)) { return false; }
      var todo = [n.id], visited = Object.create(null), any = false;
      while (todo.length) {
        var id = todo.pop();
        if (visited[id]) { continue; }
        visited[id] = true;
        for (var i = 0; i < out[id].length; i++) {
          var e = out[id][i];
          if (isBack(e)) { continue; }
          if (!inSubtree(e.to, n.id)) { return false; }
          any = true; todo.push(e.to);
        }
      }
      return any || out[n.id].some(isBack);
    }
    function forced(n) { var m = attrsOf(n.raw).main; return m === true || m === false ? m : null; }
    function choose(n) {
      var cands = out[n.id].filter(function (e) { return !isBack(e) && !seen[e.to]; });
      if (!cands.length) { return null; }
      var plain = cands.filter(function (e) { return !by[e.to].exit && forced(by[e.to]) !== false; });
      if (plain.length) { cands = plain; }
      var yes = cands.filter(function (e) { return forced(by[e.to]) === true; });
      if (yes.length) { return yes[0].to; }
      if (has(LOOPS, n.kind)) { // a loop is entered by its body first; `done` is taken from the end of the body
        var body = cands.filter(function (e) { return e.label !== 'done'; });
        if (body.length && !n.entered) { n.entered = true; return body[0].to; }
      }
      var done = cands.filter(function (e) { return e.label === 'done'; });
      if (done.length) { return done[0].to; }
      if (n.kind === 'alt') {
        var beyond = cands.filter(function (e) { return !inSubtree(e.to, n.id) || e.to === n.id; });
        if (beyond.length) { return beyond[0].to; }
        var loop = enclosingLoop(n);
        if (loop && cands.every(function (e) { return inSubtree(e.to, n.id); })) { return null; }
        var els = cands.filter(function (e) { return e.label === 'else'; });
        return (els[0] || cands[0]).to;
      }
      var nonHandler = cands.filter(function (e) { return !has(HANDLERS, e.label); });
      var rank = { '': 0, then: 1, round: 2, else: 3 };
      var pool = (nonHandler.length ? nonHandler : cands).slice().sort(function (a, b) { return (has(rank, a.label) ? rank[a.label] : 4) - (has(rank, b.label) ? rank[b.label] : 4); });
      return pool[0].to;
    }
    function loopDone(n) {
      var loop = enclosingLoop(n);
      while (loop) {
        var d = out[loop.id].filter(function (e) { return e.label === 'done' && !seen[e.to]; })[0];
        if (d) { return d.to; }
        loop = enclosingLoop(loop);
      }
      return null;
    }

    while (cur && guard++ < nodes.length + 5) {
      path.push(cur.id); seen[cur.id] = true;
      if (cur.kind === 'end') { break; }
      var next = choose(cur);
      if (next && repeatDecision(by[next])) { next = null; }
      if (!next) { next = loopDone(cur); }
      if (next && !by[next]) { break; }
      cur = next ? by[next] : null;
    }
    // a path that never reached `end` still ends there when the workflow has one
    var last = path[path.length - 1], end = nodes.filter(function (n) { return n.kind === 'end'; })[0];
    if (end && last !== end.id && !seen[end.id] && !out[last].length) { path.push(end.id); }
    return path;
  }

  /** The main-path step a step hangs under: walk up `parent` until a main step; otherwise the nearest one before it. */
  function anchorOf(n, by, onMain, nodes) {
    if (n.main) { return n.id; }
    var p = n, hops = 0;
    while (p && hops++ < 200) {
      if (onMain[p.id]) { return p.id; }
      p = p.parent ? by[p.parent] : null;
    }
    for (var i = n.index - 1; i >= 0; i--) { if (onMain[nodes[i].id]) { return nodes[i].id; } }
    return null;
  }

  /* ---------------------------------------------------------------- helpers */

  var EDGE_WORDS = { again: 'loops back', done: 'when done', round: 'each round', rewind: 'rewinds', stopped: 'when stopped' };
  /** The words on the line between two steps in the Focus view. */
  /** The kinds of intervention line a graph draws, with how each looks and what it means. Colour is never the only signal: each has its own dash pattern and a label. */
  var LINES = {
    stopped: { name: 'Stopped', means: 'where the step goes when its agent is stopped (its on_stopped handler)', dash: '1.5 5' },
    rewind: { name: 'Sent back', means: 'a rewind to a checkpoint the script declared, carrying the reason', dash: '8 4' }
  };

  /** Which of the intervention lines this workflow has, in the order of LINES. */
  function lineKinds(model) {
    var found = {};
    model.nodes.forEach(function (n) { (model.out[n.id] || []).forEach(function (e) { if (has(LINES, e.label)) { found[e.label] = true; } }); });
    return Object.keys(LINES).filter(function (k) { return found[k]; });
  }

  function edgeWord(label) { return label ? (has(EDGE_WORDS, label) ? EDGE_WORDS[label] : label) : 'next'; }

  /** Steps in the order of a tree walk: the order the arrow keys and the Outline use. */
  function visibleOrder(model, collapsed) {
    return model.nodes.filter(function (n) {
      if (n.kind === 'start' || n.kind === 'end') { return false; }
      var p = n.parent, hops = 0;
      while (p && hops++ < 200) { if (collapsed && collapsed.has(p)) { return false; } p = model.by[p] ? model.by[p].parent : null; }
      return true;
    });
  }

  function descendants(model, id) {
    var list = [], todo = (model.by[id] ? model.by[id].children.slice() : []);
    while (todo.length) { var c = todo.shift(); list.push(c); todo.push.apply(todo, model.by[c].children); }
    return list;
  }

  /** What the status line under the section names says about where the selected step sits. */
  function railNote(model, selectedId) {
    var sel = model.by[selectedId];
    if (!sel) { return ''; }
    var anchor = sel.anchor ? model.by[sel.anchor] : null;
    var pos = anchor ? model.main.indexOf(anchor.id) + 1 : 0;
    var tail = model.endless ? ' · runs until stopped' : '';
    if (sel.main) { return 'Step ' + pos + ' of ' + model.main.length + ' on the happy path' + tail; }
    if (sel.exit) { return 'Off the main path: an exit that ends the run, from step ' + pos; }
    return 'Off the main path, inside step ' + pos + (anchor ? ' (' + anchor.label + ')' : '');
  }

  return {
    LINES: LINES, lineKinds: lineKinds,
    VK: VK, LEGEND: LEGEND, CONTAINERS: CONTAINERS, build: build, mainPath: mainPath, edgeWord: edgeWord,
    visibleOrder: visibleOrder, descendants: descendants, railNote: railNote, conditionVariable: conditionVariable
  };
});
