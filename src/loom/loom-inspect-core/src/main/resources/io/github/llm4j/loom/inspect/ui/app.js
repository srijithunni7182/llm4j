'use strict';
// Every piece of run text goes in with textContent, never as HTML.
(function () {
  var $ = function (id) { return document.getElementById(id); };
  var selected = null, agentFilter = null, view = null, viewAt = 0;
  var fabric = false, procs = [], showProcs = false, showSecrets = false, showConfig = false, showFleet = '', paused = false, pausedAt = 0, pausedList = null, lastRuns = [], runSeen = {}, nextRunIdx = 0, fleetFailing = 0, fleetSecOpen = 0, clientNames = [], sess = {};

  function store(k, v) { try { if (v === undefined) { return localStorage.getItem(k); } localStorage.setItem(k, v); } catch (e) { return null; } return null; }

  function theme(t) {
    document.documentElement.setAttribute('data-theme', t);
    $('themelbl').textContent = t === 'dark' ? 'Light' : 'Dark';
  }
  theme(store('loom-inspect-theme') === 'light' ? 'light' : 'dark');
  $('theme').addEventListener('click', function () {
    var t = document.documentElement.getAttribute('data-theme') === 'dark' ? 'light' : 'dark';
    store('loom-inspect-theme', t);
    theme(t);
  });

  function fetchJson(url) {
    return fetch(url, { cache: 'no-store' }).then(function (r) {
      if (!r.ok) { var err = new Error(r.status); err.status = r.status; throw err; }
      return r.json();
    });
  }
  function money(s) { var n = Number(s); return '$' + (isFinite(n) ? n.toFixed(n < 1 ? 3 : 2) : '0'); }
  function num(n) { return Number(n).toLocaleString(); }
  function clock(ms) {
    var s = Math.max(0, Math.floor(ms / 1000)), h = Math.floor(s / 3600), m = Math.floor((s % 3600) / 60);
    s = s % 60;
    return (h ? h + ':' + String(m).padStart(2, '0') : m) + ':' + String(s).padStart(2, '0');
  }
  function hms(iso) {
    var d = new Date(iso);
    return [d.getHours(), d.getMinutes(), d.getSeconds()].map(function (n) { return String(n).padStart(2, '0'); }).join(':');
  }
  function shortId(id) { var s = String(id || ''), i = Math.max(s.lastIndexOf('/'), s.lastIndexOf('\\')); return i >= 0 ? s.slice(i + 1) : s; }
  function el(tag, cls, text) {
    var e = document.createElement(tag);
    if (cls) { e.className = cls; }
    if (text != null) { e.textContent = text; }
    return e;
  }
  function cell(row, text, cls) { var td = el('td', cls || '', text); row.appendChild(td); return td; }
  function tabState(s) { return s === 'finished' ? 'done' : s; }

  var LIVE = { running: 1, paused: 1, sleeping: 1, waiting: 1 };
  var TAB_MAX = 8, TAB_LINGER = 60000;

  /** The runs that get a tab: the selected one, the live ones, and those that finished a moment ago, in the order they were first seen, at most eight. */
  function tabRuns(runs) {
    var fresh = runs.filter(function (r) { return !runSeen[r.traceId]; }).sort(function (x, y) { return String(x.startedAt || '').localeCompare(String(y.startedAt || '')); });
    fresh.forEach(function (r) { runSeen[r.traceId] = { idx: nextRunIdx++, liveSeen: false, endedAt: null }; });
    var now = Date.now();
    runs.forEach(function (r) {
      var seen = runSeen[r.traceId];
      if (LIVE[r.status]) { seen.liveSeen = true; seen.endedAt = null; }
      else if (seen.endedAt == null) { seen.endedAt = seen.liveSeen ? now : 0; }   // finished before we ever saw it live: not news
    });
    var ordered = runs.slice().sort(function (x, y) { return runSeen[x.traceId].idx - runSeen[y.traceId].idx; });
    var shown = ordered.filter(function (r) {
      var seen = runSeen[r.traceId];
      return r.traceId === selected || LIVE[r.status] || (seen.endedAt > 0 && now - seen.endedAt < TAB_LINGER);
    });
    while (shown.length > TAB_MAX) {
      var drop = -1;
      for (var i = shown.length - 1; i >= 0; i--) { if (shown[i].traceId !== selected) { drop = i; break; } }
      if (drop < 0) { break; }
      shown.splice(drop, 1);
    }
    return shown;
  }

  function renderTabs(runs) {
    var nav = $('wtabs');
    nav.textContent = '';
    if (fabric) {
      var p = el('button', 'wt proc' + (showProcs ? ' on' : ''), 'Processes');
      p.type = 'button';
      p.addEventListener('click', function () { showProcs = true; showConfig = false; showFleet = ''; $('controls').hidden = true; renderAll(); });
      nav.appendChild(p);
    }
    if (fabric || runs.length > 1) {
      [['tools', 'Tools & tasks', fleetFailing, 'high'], ['security', 'Security', fleetSecOpen, 'warn']].forEach(function (f) {
        var fb = el('button', 'wt proc' + (showFleet === f[0] ? ' on' : ''), f[1]);
        fb.type = 'button';
        if (f[2] > 0) { fb.appendChild(el('span', 'sev ' + f[3] + ' inc', String(f[2]))); }
        fb.addEventListener('click', function () { showFleet = f[0]; showProcs = false; showSecrets = false; showConfig = false; $('controls').hidden = true; tick(true); });
        nav.appendChild(fb);
      });
    }
    if (sess.secretsEnabled && !fabric) {
      var sb = el('button', 'wt proc' + (showSecrets ? ' on' : ''), 'Secrets');
      sb.type = 'button';
      sb.addEventListener('click', function () { showSecrets = true; showProcs = false; showConfig = false; showFleet = ''; tick(true); });
      nav.appendChild(sb);
    }
    if (sess.configEnabled && fabric) {
      var cb = el('button', 'wt proc' + (showConfig ? ' on' : ''), 'Configuration');
      cb.type = 'button';
      cb.addEventListener('click', function () { showConfig = true; showProcs = false; showSecrets = false; showFleet = ''; tick(true); });
      nav.appendChild(cb);
    }
    var clients = {};
    runs.forEach(function (r) { clients[r.client] = true; });
    var grouped = fabric || Object.keys(clients).length > 1;   // one tab per process and workflow, never merged
    if (fabric || runs.length > 1) {
      var rb = el('button', 'wt proc' + (showFleet === 'runs' ? ' on' : ''), 'Runs');
      rb.type = 'button';
      rb.appendChild(el('span', 'cnt', String(runs.length)));
      rb.addEventListener('click', function () { showFleet = 'runs'; showProcs = false; showSecrets = false; showConfig = false; $('controls').hidden = true; tick(true); });
      nav.appendChild(rb);
    }
    var label = {};
    runs.forEach(function (r) { var k = (r.client || '') + '/' + (r.workflow || 'run'); label[k] = (label[k] || 0) + 1; });
    tabRuns(runs).forEach(function (r) {
      var b = el('button', 'wt run' + (!showProcs && !showSecrets && !showConfig && !showFleet && r.traceId === selected ? ' on' : ''));
      b.type = 'button';
      b.appendChild(el('span', 'dot ' + tabState(r.status)));
      var twin = label[(r.client || '') + '/' + (r.workflow || 'run')] > 1;
      b.appendChild(el('span', '', (grouped && r.client ? r.client + ' / ' : '') + (r.workflow || 'run') + (twin && r.startedAt ? ' · ' + hms(r.startedAt) : '')));
      if (r.incidents > 0) { b.appendChild(el('span', 'sev ' + (r.worstIncident || 'warn') + ' inc', String(r.incidents))); }
      b.title = r.status + ' · ' + shortId(r.runId || r.traceId);
      b.addEventListener('click', function () { selected = r.traceId; agentFilter = null; showProcs = false; showSecrets = false; showConfig = false; showFleet = ''; tick(true); });
      nav.appendChild(b);
    });
    nav.hidden = !fabric && runs.length < 2 && !(sess.secretsEnabled && !fabric);
  }

  function countChanges(runs) {
    var was = pausedList || {}, n = 0;
    runs.forEach(function (r) {
      var o = was[r.traceId];
      if (!o) { n++; return; }
      if (o.status !== r.status) { n++; }
      if ((r.incidents || 0) > o.incidents) { n += r.incidents - o.incidents; }
    });
    $('livechip').textContent = 'Paused at ' + hms(new Date(pausedAt).toISOString()) + ' · ' + (n ? plural(n, 'change') + ' since' : 'nothing new');
    $('livechip').hidden = false;
  }

  function setPaused(on) {
    paused = on;
    $('live').setAttribute('aria-pressed', String(on));
    $('livelbl').textContent = on ? 'Paused' : 'Live';
    $('live').classList.toggle('held', on);
    if (on) {
      pausedAt = Date.now();
      pausedList = {};
      lastRuns.forEach(function (r) { pausedList[r.traceId] = { status: r.status, incidents: r.incidents || 0 }; });
      countChanges(lastRuns);
    } else {
      $('livechip').hidden = true;
      tick(true);
    }
  }
  $('live').addEventListener('click', function () { setPaused(!paused); });

  function renderRuns(runs) {
    var ids = runs.map(function (r) { return r.traceId; });
    if (ids.indexOf(selected) < 0) { selected = ids.length ? ids[0] : null; }
    renderTabs(runs);
    $('empty').hidden = runs.length > 0 || showProcs || !!showFleet;
    $('main').hidden = runs.length === 0 || showProcs || !!showFleet;
    $('processes').hidden = !showProcs;
    $('fleettools').hidden = showFleet !== 'tools';
    $('fleetsec').hidden = showFleet !== 'security';
    $('runslist').hidden = showFleet !== 'runs';
    if (!runs.length) { ['status', 'meta'].forEach(function (i) { $(i).hidden = true; }); $('wfname').textContent = ''; $('banner').hidden = true; }
  }

  function renderBanner(v) {
    var banner = $('banner');
    banner.hidden = true;
    banner.className = 'banner';
    banner.textContent = '';
    var lead = null, rest = '';
    var broken = (v.incidents || []).filter(function (i) { return i.kind === 'BROKEN'; })[0];
    var diverted = (v.incidents || []).filter(function (i) { return i.kind === 'DIVERTED'; });
    if (v.status === 'failed' && broken) {
      banner.className = 'banner broken';
      var why = el('div', 'why');
      why.appendChild(el('b', '', 'The run stopped'));
      why.appendChild(document.createTextNode(': ' + String(broken.title).replace(/^The run stopped:\s*/, '')));
      var ev = (broken.evidence || [])[0];
      why.appendChild(el('span', 'sub', (ev && ev.detail ? ev.detail : '') + (broken.step ? ' · step ' + broken.step : '')));
      banner.appendChild(why);
      var show = el('button', 'btn', 'Show in workflow');
      show.type = 'button';
      show.addEventListener('click', function () { runTab = 'overview'; render(); $('graphwrap').scrollIntoView({ block: 'center' }); });
      banner.appendChild(show);
      banner.hidden = false;
      return;
    }
    if (v.status === 'sleeping') {
      var wake = (v.sleeping && v.sleeping.wakeAt) || (v.suspended && v.suspended.resumeAt);
      lead = 'Sleeping'; rest = (wake ? ' until ' + new Date(wake).toLocaleTimeString() : '') + '. Nothing is running; the run holds no thread.';
    } else if (v.status === 'paused') {
      lead = 'Paused';
      rest = (v.suspended ? ' (' + v.suspended.reason + (v.suspended.resumeAt ? ', resumes ' + new Date(v.suspended.resumeAt).toLocaleTimeString() : '') + ')' : '') + '. This is not a failure.';
    } else if (v.status === 'waiting') {
      banner.className = 'banner waiting';
      lead = 'Waiting'; rest = ' for a person to answer.';
    } else if (v.gaps > 0) {
      lead = 'Incomplete'; rest = ': ' + v.gaps + ' event(s) are missing from this view.';
    }
    if (!lead && diverted.length) {
      banner.className = 'banner diverted';
      lead = plural(diverted.length, 'step') + ' went through on_failure'; rest = ': a task or delegate failed and its on_failure block took over, so the workflow went on.';
    }
    if (lead) { banner.appendChild(el('b', '', lead)); banner.appendChild(document.createTextNode(rest)); banner.hidden = false; }
  }

  function renderFollow(v) {
    var running = v.agents.filter(function (a) { return a.status === 'running'; });
    var follow = agentFilter ? v.agents.filter(function (a) { return a.name === agentFilter; })[0] : running[0];
    var focus = $('focus');
    focus.hidden = !follow;
    $('livechip').hidden = !running.length;
    if (follow) {
      $('avatar').textContent = follow.name.charAt(0).toUpperCase();
      $('avatar').className = 'avatar' + (follow.status === 'running' ? ' run' : '');
      $('focusname').textContent = follow.name;
      $('focussub').textContent = follow.status + (follow.currentStep ? ' · ' + follow.currentStep : '') + ' · ' + num(follow.tokens) + ' tokens';
    }
    $('followsub').textContent = agentFilter ? 'filtered' : '';

    var filters = $('filters');
    filters.textContent = '';
    var names = [null].concat(v.agents.map(function (a) { return a.name; }));
    names.forEach(function (n) {
      var b = el('button', 'chip' + ((n || null) === agentFilter ? ' sel' : ''), n || 'all');
      b.type = 'button';
      b.addEventListener('click', function () { agentFilter = n; render(); });
      filters.appendChild(b);
    });

    var trace = $('trace');
    trace.textContent = '';
    var lines = v.recent.filter(function (e) { return !agentFilter || e.agent === agentFilter; }).slice(-40).reverse();
    var inflight = true;
    lines.forEach(function (e) {
      var started = e.event && /\.start$/.test(e.event) && e.kind;
      var li = el('li', (started && inflight ? 'now' : '') + (e.failed ? ' bad' : ''));
      var kind = e.kind || (e.event || '').replace('loom.', '').split('.')[0];
      li.appendChild(el('span', 'faint', hms(e.at)));
      li.appendChild(el('span', 'k ' + kind, kind === 'agent' ? 'plan' : kind));
      var t = el('span', 't');
      if (started && inflight) { t.appendChild(el('span', 'spin')); }
      t.appendChild(document.createTextNode((e.agent && !agentFilter ? e.agent + ' · ' : '') + e.text + (e.until ? ' until ' + new Date(e.until).toLocaleTimeString() : '')));
      li.appendChild(t);
      if (started) { inflight = false; }
      trace.appendChild(li);
    });
    $('notrace').hidden = lines.length > 0;
  }

  function svg(tag, attrs) {
    var e = document.createElementNS('http://www.w3.org/2000/svg', tag);
    Object.keys(attrs).forEach(function (k) { e.setAttribute(k, attrs[k]); });
    return e;
  }

  // Cumulative tokens against run time; the x axis is the run's own clock, so a sleep shows as a flat stretch.
  function renderSpend(points) {
    var g = $('spend');
    g.textContent = '';
    $('nospend').hidden = points.length > 0;
    if (points.length) { g.removeAttribute('hidden'); } else { g.setAttribute('hidden', ''); }
    if (!points.length) { $('spendsub').textContent = ''; return; }
    var W = 600, H = 140, maxT = Math.max(view.elapsedMillis, points[points.length - 1].t, 1), maxV = Math.max(points[points.length - 1].tokens, 1);
    [0.25, 0.5, 0.75].forEach(function (f) { g.appendChild(svg('line', { x1: 0, x2: W, y1: H * f, y2: H * f, 'class': 'grid' })); });
    var d = 'M0,' + H;
    var last = 0;
    points.forEach(function (p) { last = H - (p.tokens / maxV) * (H - 4); d += ' L' + ((p.t / maxT) * W).toFixed(1) + ',' + (H - (p.tokens / maxV) * (H - 4)).toFixed(1); });
    d += ' L' + W + ',' + last.toFixed(1);
    g.appendChild(svg('path', { d: d + ' L' + W + ',' + H + ' Z', 'class': 'area' }));
    g.appendChild(svg('path', { d: d, 'class': 'line' }));
    $('spendsub').textContent = num(points[points.length - 1].tokens) + ' tokens over ' + clock(maxT);
  }

  // ---- workflow graph: statements left to right; what a statement contains hangs beneath it in a dashed box ----
  var NW = 132, NH = 50, GAP = 28, VG = 30, PAD = 10, CAP = 14;
  var graphs = {};  // trace id to the graph, fetched once

  function ensureGraph(trace) {
    if (graphs[trace] !== undefined) { return; }
    graphs[trace] = null;
    fetchJson('/api/runs/' + trace + '/graph').then(function (g) { graphs[trace] = g && g.workflows ? g : false; if (graphs[trace] === false) { setTimeout(function () { delete graphs[trace]; }, 3000); } render(); })
      .catch(function () { delete graphs[trace]; });
  }

  function stateOf(path, steps) {
    if (!path) { return ''; }
    var own = steps[path], running = own && own.state === 'running', failed = own && own.state === 'failed', any = !!own;
    Object.keys(steps).forEach(function (k) {
      if (k.indexOf(path + '/') === 0 || k.indexOf(path + '>') === 0) {
        any = true;
        if (steps[k].state === 'running') { running = true; }
        if (steps[k].state === 'failed') { failed = true; }
      }
    });
    return running ? 'run' : failed ? 'bad' : any ? 'done' : '';
  }

  function truncate(t, n) { t = String(t || ''); return t.length > n ? t.slice(0, n - 1) + '…' : t; }

  function renderGraph(v) {
    var g = graphs[selected], g2 = $('graph');
    var wf = g && g.workflows ? g.workflows.filter(function (w) { return w.name === v.workflow; })[0] : null;
    $('graphwrap').hidden = !wf;
    $('nograph').hidden = !!wf || g === null || g === undefined;
    if (!wf) { return; }
    var steps = v.steps || {}, kids = {};
    wf.nodes.forEach(function (n) { var k = (n.parent || '') + '|' + (n.branch || ''); (kids[k] = kids[k] || []).push(n); });
    var children = {};
    wf.nodes.forEach(function (n) {
      var groups = Object.keys(kids).filter(function (k) { return k.indexOf(n.id + '|') === 0; });
      if (groups.length) { children[n.id] = groups.map(function (k) { return { branch: k.slice(n.id.length + 1), nodes: kids[k] }; }); }
    });

    function groupSize(list) {   // width and height of a left-to-right row of nodes with whatever hangs under each
      var w = 0, h = 0;
      list.forEach(function (n, i) { var s = nodeSize(n); w += s.w + (i ? GAP : 0); h = Math.max(h, s.h); });
      return { w: w, h: h };
    }
    function nodeSize(n) {
      var gs = children[n.id];
      if (!gs) { return { w: NW, h: NH }; }
      var w = 0, h = 0;
      gs.forEach(function (g, i) { var s = groupSize(g.nodes); w += s.w + 2 * PAD + (i ? PAD : 0); h = Math.max(h, s.h + 2 * PAD + CAP); });
      return { w: Math.max(NW, w), h: NH + VG + h };
    }

    var out = [], total = groupSize(kids['|'] || []), runningX = null;
    function place(list, x, y, edgesFrom) {
      var cx = x, sts = list.map(function (n) { return stateOf(n.path, steps); }), reached = -1;
      sts.forEach(function (st, i) { if (st) { reached = i; } });
      // statements that leave no step of their own (a note, a set) are done once the block has moved past them
      sts = sts.map(function (st, i) { return !st && i < reached ? 'done' : st; });
      list.forEach(function (n, i) {
        var size = nodeSize(n), st = sts[i], nx = cx + (size.w - NW) / 2;
        if (i) { out.push({ t: 'edge', x1: cx - GAP, y1: y + NH / 2, x2: nx, y2: y + NH / 2, hot: st === 'run', todo: !st }); }
        out.push({ t: 'node', n: n, x: nx, y: y, st: st, runs: (steps[n.path] || {}).runs });
        if (st === 'run' && runningX === null) { runningX = nx; }
        var gs = children[n.id];
        if (gs) {
          var gx = cx;
          gs.forEach(function (gr) {
            var s = groupSize(gr.nodes), bw = s.w + 2 * PAD, by = y + NH + VG / 2;
            out.push({ t: 'box', x: gx, y: by, w: bw, h: s.h + 2 * PAD + CAP, cap: gr.branch });
            out.push({ t: 'edge', x1: nx + NW / 2, y1: y + NH, x2: gx + bw / 2, y2: by, hot: false, todo: false });
            place(gr.nodes, gx + PAD, by + PAD + CAP, null);
            gx += bw + PAD;
          });
        }
        cx += size.w + GAP;
      });
    }
    place(kids['|'] || [], PAD, PAD, null);

    var W = total.w + 2 * PAD, H = total.h + 2 * PAD;
    g2.textContent = '';
    g2.setAttribute('viewBox', '0 0 ' + W + ' ' + H);
    g2.setAttribute('width', Math.max(W, 320));
    g2.setAttribute('height', H);
    out.forEach(function (o) {
      if (o.t === 'box') {
        g2.appendChild(svg('rect', { x: o.x, y: o.y, width: o.w, height: o.h, rx: 12, 'class': 'box' }));
        var cap = svg('text', { x: o.x + 10, y: o.y + 12, 'class': 'cap' });
        cap.textContent = o.cap;
        g2.appendChild(cap);
      } else if (o.t === 'edge') {
        g2.appendChild(svg('path', { d: 'M' + o.x1 + ' ' + o.y1 + ' L' + o.x2 + ' ' + o.y2, 'class': 'edge' + (o.hot ? ' hot' : o.todo ? ' todo' : '') }));
      }
    });
    out.filter(function (o) { return o.t === 'node'; }).forEach(function (o) {
      var n = o.n, grp = svg('g', { 'class': 'node' + (o.st ? ' ' + o.st : ' todo') });
      grp.appendChild(svg('rect', { x: o.x, y: o.y, width: NW, height: NH, rx: 8 }));
      if (o.st === 'run') { grp.appendChild(svg('rect', { x: o.x, y: o.y, width: NW, height: NH, rx: 8, 'class': 'ring' })); }
      var nm = svg('text', { x: o.x + 12, y: o.y + 21, 'class': 'nm' });
      nm.textContent = truncate(n.agent || n.label, 16);
      var sb = svg('text', { x: o.x + 12, y: o.y + 38, 'class': 'sb' });
      sb.textContent = truncate(n.kind + (o.runs > 1 ? ' ×' + o.runs : ''), 18);
      grp.appendChild(nm);
      grp.appendChild(sb);
      if (o.st === 'done') { grp.appendChild(svg('path', { d: 'M' + (o.x + NW - 22) + ' ' + (o.y + 16) + 'l3.5 3.5 6-7', 'class': 'ok' })); }
      var title = svg('title', {});
      title.textContent = n.label;
      grp.appendChild(title);
      g2.appendChild(grp);
    });
    var started = (v.steps ? Object.keys(v.steps).filter(function (k) { return steps[k].state !== 'running'; }).length : 0);
    var runningNow = Object.keys(steps).filter(function (k) { return steps[k].state === 'running'; }).length;
    $('graphsub').textContent = wf.nodes.length + ' steps · ' + started + ' done' + (runningNow ? ' · ' + runningNow + ' running' : '');
    var wrap = $('graphwrap'), key = selected + ':' + runningX;
    if (runningX !== null && key !== lastScroll) { lastScroll = key; wrap.scrollLeft = Math.max(0, runningX - wrap.clientWidth / 2); }
  }
  var lastScroll = null;

  var KINDS = { PAUSED: 'Paused', LOOPING: 'Looping', SUSPECT_OUTPUT: 'Suspect output', FAILED: 'Failing', BUDGET: 'Budget', CONTEXT: 'Context', TRUNCATED: 'Truncated answer',
    THRASH: 'Compaction thrash', TOOL_RATE: 'Tool success rate', SPIKE: 'Latency spike', BROKEN: 'Broken', DIVERTED: 'Diverted', TRIFECTA: 'Lethal trifecta', INJECTION: 'Injection', ACCESS: 'Access' };
  var SECURITY_KINDS = { TRIFECTA: true, INJECTION: true, ACCESS: true };
  var SEV = { high: 'High', warn: 'Warning', info: 'Info' };

  function ago(iso) {
    var s = Math.max(0, Math.floor((Date.now() - new Date(iso).getTime()) / 1000));
    return s < 60 ? s + 's ago' : s < 3600 ? Math.floor(s / 60) + 'm ago' : Math.floor(s / 3600) + 'h ago';
  }

  function renderIncidents(list) {
    var open = list.filter(function (i) { return i.state === 'open'; });
    var strip = $('attention');
    strip.textContent = '';
    strip.hidden = open.length === 0;
    var worst = open.some(function (i) { return i.severity === 'high'; }) ? 'high' : open.some(function (i) { return i.severity === 'warn'; }) ? 'warn' : 'info';
    strip.className = 'attention ' + worst;
    if (open.length) {
      strip.appendChild(el('span', 'lead', 'Needs attention'));
      open.forEach(function (i) {
        var b = el('button', 'sev ' + i.severity, (SEV[i.severity] || i.severity) + ' · ' + (KINDS[i.kind] || i.kind) + (i.agent ? ' · ' + i.agent : ''));
        b.type = 'button';
        b.title = i.title;
        b.addEventListener('click', function () { if (i.agent) { agentFilter = i.agent; render(); } $('incidents').scrollIntoView({ block: 'nearest' }); });
        strip.appendChild(b);
      });
    }
    $('incidents').hidden = list.length === 0;
    $('incsub').textContent = open.length + ' open' + (list.length > open.length ? ' · ' + (list.length - open.length) + ' closed' : '');
    var box = $('inclist');
    box.textContent = '';
    // a run that broke says so first, then the rest as the server ordered them
    var ordered = list.filter(function (i) { return i.kind === 'BROKEN'; }).concat(list.filter(function (i) { return i.kind !== 'BROKEN'; }));
    ordered.forEach(function (i) { box.appendChild(incidentRow(i)); });
  }

  function incidentRow(i) {
    var row = el('div', 'incident' + (i.state === 'closed' ? ' closed' : ''));
    var top = el('div', 'top');
    top.appendChild(el('span', 'sev ' + i.severity, SEV[i.severity] || i.severity));
    top.appendChild(el('span', 'chip', (KINDS[i.kind] || i.kind) + (i.kind === 'SUSPECT_OUTPUT' ? ' · suspect' : '')));
    top.appendChild(el('span', 'ttl', i.title));
    top.appendChild(el('span', 'when mono', (i.state === 'closed' ? 'closed ' + ago(i.closedAt) + ' · ' + i.closeReason : 'since ' + ago(i.since))));
    row.appendChild(top);
    var ev = el('div', 'evid mono');
    (i.evidence || []).forEach(function (e) {
      var line = el('div');
      line.appendChild(el('span', 'rule', e.rule + ': '));
      line.appendChild(el('b', '', String(e.measured) + (e.threshold != null ? ' (threshold ' + e.threshold + ')' : '')));
      if (e.detail) { line.appendChild(document.createTextNode(' — ' + e.detail)); }
      ev.appendChild(line);
    });
    row.appendChild(ev);
    if (i.kind === 'SUSPECT_OUTPUT') { row.appendChild(el('p', 'note', 'This means no source was found for these figures in what the step was given or fetched. It does not mean they are false.')); }
    if (i.kind === 'INJECTION') { row.appendChild(el('p', 'note', 'A heuristic: the result read like instructions. Only the pattern groups are kept, never the text. Look at what the agent did next.')); }
    return row;
  }


  // ---- run views: context, tools and tasks, security (each a function from the run's view to the page; text only, never HTML)
  var runTab = 'overview', ttFilter = 'all', ttOpen = {}, ctxAgent = null;
  var TABS = [['overview', 'Overview'], ['context', 'Context'], ['tools', 'Tools & tasks'], ['security', 'Security']];
  var HEALTH_RANK = { failing: 0, degraded: 1, healthy: 2, unused: 3 };
  var STATEMENT = 'What this view cannot tell you: that a run was attacked. A suspected injection is a pattern in a tool result, a lead to follow and not a verdict: a careful attack can pass it, and a harmless page can trip it. '
    + 'The trifecta and access signals are exact about what was declared and what was called, but they say a risky combination was used, not that anyone abused it. Neither replaces a review (weave audit).';
  var NO_WINDOW = 'no window known: set one on the Configuration page or in a .windows file';

  function pct(f) { return Math.round(f * 100); }
  function plural(n, one, many) { return n + ' ' + (n === 1 ? one : many || one + 's'); }
  function openSecurity(v) { return (v.incidents || []).filter(function (i) { return i.state === 'open' && SECURITY_KINDS[i.kind]; }).length; }

  function renderRunTabs(v) {
    var nav = $('runtabs');
    nav.textContent = '';
    TABS.forEach(function (t) {
      var b = el('button', 'rt', t[1]);
      b.type = 'button';
      b.setAttribute('role', 'tab');
      b.setAttribute('aria-selected', String(runTab === t[0]));
      if (t[0] === 'security' && openSecurity(v) > 0) { b.appendChild(el('span', 'sev warn inc', String(openSecurity(v)))); }
      b.setAttribute('data-fk', 'rt:' + t[0]);
      b.addEventListener('click', function () { runTab = t[0]; render(); });
      nav.appendChild(b);
    });
    var panels = document.querySelectorAll('#main [data-tab]');
    for (var i = 0; i < panels.length; i++) { panels[i].hidden = panels[i].getAttribute('data-tab') !== runTab; }
  }

  function contextOf(v) {
    return (v.agents || []).filter(function (a) { return a.context; }).map(function (a) { return { name: a.name, c: a.context }; });
  }

  function renderContextTile(v) {
    var list = contextOf(v), best = null;
    list.forEach(function (x) { if (x.c.fraction != null && (!best || x.c.fraction > best.c.fraction)) { best = x; } });
    var val = $('ctxval'), foot = $('ctxfoot');
    val.className = 'val';
    if (best) {
      val.textContent = pct(best.c.fraction) + '%';
      val.className = 'val' + (best.c.fraction >= (best.c.highAt || 0.95) ? ' high' : best.c.fraction >= (best.c.warnAt || 0.85) ? ' warn' : '');
      foot.textContent = best.name + ' · ' + plural(best.c.compactions || 0, 'compaction');
    } else if (list.length) {
      val.textContent = '–';
      foot.textContent = 'no window known';
    } else {
      val.textContent = '–';
      foot.textContent = 'no model call yet';
    }
    $('tile-context').onclick = function () { runTab = 'context'; render(); };
  }

  function renderTaskTile(v) {
    var calls = 0, failed = 0;
    (v.tasks || []).forEach(function (t) { calls += t.calls; failed += t.errors; });
    var diverted = (v.incidents || []).filter(function (i) { return i.kind === 'DIVERTED'; }).length;
    $('taskval').textContent = num(calls);
    $('taskfoot').textContent = failed + ' failed · ' + diverted + ' diverted';
  }

  function renderContext(v) {
    var list = contextOf(v), rows = $('ctxrows');
    rows.textContent = '';
    $('noctx').hidden = list.length > 0;
    $('ctxtimeline').hidden = list.length === 0;
    list.forEach(function (x) {
      var c = x.c, known = c.fraction != null, row = el('div', 'ctxrow' + (known ? '' : ' nowindow'));
      var head = el('div', 'head');
      head.appendChild(el('span', 'nm', x.name));
      var nums = known
        ? num(c.used) + ' of ' + num(c.usable) + ' tokens (' + pct(c.fraction) + '%)' + (c.windowSource === 'catalog' ? ' · window from this server' : '')
        : num(c.used) + ' tokens';
      head.appendChild(el('span', 'mono', nums));
      row.appendChild(head);
      if (known) {
        var m = el('div', 'meter');
        m.setAttribute('role', 'meter');
        m.setAttribute('aria-valuemin', '0');
        m.setAttribute('aria-valuemax', '100');
        m.setAttribute('aria-valuenow', String(Math.max(0, Math.min(100, pct(c.fraction)))));
        m.setAttribute('aria-label', x.name + ' context fill: ' + pct(c.fraction) + ' percent of the usable window');
        var warn = c.warnAt != null ? c.warnAt : 0.85, high = c.highAt != null ? c.highAt : 0.95;
        var fill = el('div', 'fill' + (c.fraction >= high ? ' high' : c.fraction >= warn ? ' warn' : ''));
        fill.style.width = Math.max(0, Math.min(100, c.fraction * 100)) + '%';
        m.appendChild(fill);
        var mw = el('div', 'mark warn'); mw.style.left = pct(warn) + '%'; mw.title = 'warns at ' + pct(warn) + '%'; m.appendChild(mw);
        var mh = el('div', 'mark high'); mh.style.left = pct(high) + '%'; mh.title = 'high at ' + pct(high) + '%'; m.appendChild(mh);
        row.appendChild(m);
      } else {
        row.appendChild(el('div', 'hatch'));
        row.appendChild(el('div', 'note mono', NO_WINDOW));
      }
      var last = c.lastCompaction;
      row.appendChild(el('div', 'note mono', plural(c.compactions || 0, 'compaction') + (last ? ' · last: ' + last.strategy + ' ' + String(last.outcome).toLowerCase() + (last.before > last.after ? ', freed ' + num(last.before - last.after) + ' tokens' : '') : '')));
      rows.appendChild(row);
    });
    if (!list.length) { return; }
    var names = list.map(function (x) { return x.name; });
    if (!ctxAgent || names.indexOf(ctxAgent) < 0) {
      var top = list.slice().sort(function (a, b) { return (b.c.fraction || 0) - (a.c.fraction || 0) || b.c.used - a.c.used; })[0];
      ctxAgent = top.name;
    }
    var pick = $('ctxpick');
    pick.textContent = '';
    names.forEach(function (n) {
      var b = el('button', '', n);
      b.type = 'button';
      b.setAttribute('aria-pressed', String(n === ctxAgent));
      b.setAttribute('data-fk', 'ctxp:' + n);
      b.addEventListener('click', function () { withFocus(function () { ctxAgent = n; renderContext(v); }); });
      pick.appendChild(b);
    });
    renderTimeline(list.filter(function (x) { return x.name === ctxAgent; })[0]);
  }

  function renderTimeline(x) {
    var c = x.c, pts = c.points || [], evs = c.events || [], svgEl = $('ctxline');
    var title = svgEl.querySelector('title');
    svgEl.textContent = '';
    svgEl.appendChild(title);
    var L = 44, R = 940, T = 16, B = 190;
    var fracs = pts.some(function (p) { return p.fraction != null; });
    var maxUsed = Math.max.apply(null, pts.map(function (p) { return p.used; }).concat([1]));
    var top = fracs ? Math.max(1.05, Math.max.apply(null, pts.map(function (p) { return p.fraction || 0; }))) : maxUsed;
    var n0 = pts.length ? pts[0].n : 0, n1 = pts.length ? pts[pts.length - 1].n : 1;
    function X(n) { return L + (n1 === n0 ? 0.5 : (Math.max(n0, Math.min(n1, n)) - n0) / (n1 - n0)) * (R - L); }
    function Y(p) { return B - ((fracs ? (p.fraction || 0) : p.used) / top) * (B - T); }
    svgEl.appendChild(svg('line', { x1: L, x2: R, y1: B, y2: B, 'class': 'axis' }));
    if (fracs) {
      [['warn', c.warnAt != null ? c.warnAt : 0.85], ['high', c.highAt != null ? c.highAt : 0.95]].forEach(function (t) {
        var y = B - (t[1] / top) * (B - T);
        svgEl.appendChild(svg('line', { x1: L, x2: R, y1: y, y2: y, 'class': 'threshold ' + t[0] }));
        var lbl = svg('text', { x: L + 4, y: y - 3 }); lbl.textContent = pct(t[1]) + '%'; svgEl.appendChild(lbl);
      });
    }
    if (pts.length) {
      svgEl.appendChild(svg('polyline', { points: pts.map(function (p) { return X(p.n).toFixed(1) + ',' + Y(p).toFixed(1); }).join(' ') }));
      if (!fracs || pts.length < 3) { pts.forEach(function (p) { svgEl.appendChild(svg('circle', { cx: X(p.n), cy: Y(p), r: 2.5, 'class': 'point' })); }); }
    }
    evs.forEach(function (e) {
      var after = pts.filter(function (p) { return p.n === e.n + 1; })[0] || pts.filter(function (p) { return p.n === e.n; })[0] || pts[pts.length - 1];
      if (!after) { return; }
      var label = 'Compaction after call ' + e.n + ': ' + e.strategy + ' ' + String(e.outcome).toLowerCase() + ', ' + num(e.before) + ' to ' + num(e.after) + ' tokens';
      var m = svg('circle', { cx: X(e.n + 0.5), cy: Y(after), r: 5, 'class': 'event', tabindex: 0, role: 'img', 'aria-label': label, 'data-fk': 'ev:' + e.n });
      var t = svg('title', {}); t.textContent = label; m.appendChild(t);
      svgEl.appendChild(m);
    });
    var l0 = svg('text', { x: L, y: 208 }); l0.textContent = 'call ' + n0; svgEl.appendChild(l0);
    var l1 = svg('text', { x: R - 44, y: 208 }); l1.textContent = 'call ' + n1; svgEl.appendChild(l1);
    $('ctxlegend').textContent = fracs ? 'Share of the usable window after each model call. Dashed lines: where this agent warns and where it becomes high. Dots: compactions.' : 'Tokens in the prompt after each model call. No window is known, so no fraction and no thresholds. Dots: compactions.';
    var body = $('ctxtable').querySelector('tbody');
    body.textContent = '';
    evs.forEach(function (e) {
      var tr = el('tr');
      cell(tr, e.n, 'mono'); cell(tr, e.strategy, 'mono'); cell(tr, String(e.outcome).toLowerCase(), 'mono'); cell(tr, num(e.before), 'r'); cell(tr, num(e.after), 'r');
      body.appendChild(tr);
    });
  }

  function ttRows(v) {
    var rows = [];
    (v.tools || []).forEach(function (t) { rows.push(ttRow('tool', t)); });
    (v.tasks || []).forEach(function (t) { rows.push(ttRow('task', t)); });
    rows.sort(function (a, b) { return (HEALTH_RANK[a.state] - HEALTH_RANK[b.state]) || (b.calls - a.calls) || (a.name < b.name ? -1 : a.name > b.name ? 1 : 0); });
    return rows;
  }

  function ttRow(kind, t) {
    var h = t.health || {}, ran = t.ran != null ? t.ran : (t.successes || 0) + (t.errors || 0);
    return { kind: kind, key: kind + ':' + t.name, name: t.name, calls: t.calls, ran: ran, successes: t.successes || 0, errors: t.errors || 0, state: h.state || 'unused', reason: h.reason || '',
      p50: t.p50Ms || 0, p95: t.p95Ms || 0, inFlight: t.inFlight || 0, failures: h.failures || t.failures || {}, byAgent: h.byAgent || {}, recent: t.recent || [], histogram: t.histogram || [],
      bounds: t.histogramBounds || [], last: h.lastFailure };
  }

  function renderToolsTasks(v) {
    var all = ttRows(v);
    var held = heldOrder(ttHold[v.traceId] = ttHold[v.traceId] || {}, all);
    all = held.rows;
    $('ttsortwrap').hidden = !held.changed;
    var shown = all.filter(function (r) { return ttFilter === 'all' || r.kind === (ttFilter === 'tools' ? 'tool' : 'task'); });
    var f = $('ttfilter');
    f.textContent = '';
    ['all', 'tools', 'tasks'].forEach(function (n) {
      var b = el('button', '', n);
      b.type = 'button';
      b.setAttribute('aria-pressed', String(ttFilter === n));
      b.setAttribute('data-fk', 'ttf:' + n);
      b.addEventListener('click', function () { withFocus(function () { ttFilter = n; renderToolsTasks(v); }); });
      f.appendChild(b);
    });
    var failing = all.filter(function (r) { return r.state === 'failing'; }).length;
    $('ttsub').textContent = plural(all.filter(function (r) { return r.kind === 'tool'; }).length, 'tool') + ' · ' + plural(all.filter(function (r) { return r.kind === 'task'; }).length, 'task') + (failing ? ' · ' + failing + ' failing' : '');
    var body = $('ttrows');
    body.textContent = '';
    $('nott').hidden = all.length > 0;
    shown.forEach(function (r) {
      var open = !!ttOpen[r.key];
      var tr = el('tr', 'tt ' + r.state);
      tr.tabIndex = 0;
      tr.setAttribute('aria-expanded', String(open));
      var first = cell(tr, '', 'mono');
      var nm = el('span', 'nm', r.name); nm.title = r.name; first.appendChild(nm);
      if (r.inFlight > 0) { first.appendChild(el('span', 'chip on', r.inFlight + ' in flight')); }
      cell(tr, '').appendChild(el('span', 'chip', r.kind));
      cell(tr, num(r.calls), 'r mono');
      var rate = cell(tr, '');
      var wrap = el('span', 'rate');
      var bar = el('span', 'bar'), fill = el('i', r.ran && r.successes / r.ran < 0.4 ? 'bad' : r.ran && r.successes / r.ran < 0.7 ? 'warn' : '');
      fill.style.width = (r.ran ? r.successes / r.ran * 100 : 0) + '%';
      bar.appendChild(fill);
      wrap.appendChild(bar);
      wrap.appendChild(el('span', 'mono', r.ran ? r.successes + ' of ' + r.ran : 'no call ran'));
      rate.appendChild(wrap);
      cell(tr, r.ran ? num(r.p50) + ' / ' + num(r.p95) + ' ms' : '–', 'r mono');
      var hc = cell(tr, '');
      var hw = el('span', 'hw ' + r.state, r.state);
      hw.title = r.reason;
      hc.appendChild(hw);
      cell(tr, r.last ? r.last.category + ' · ' + ago(r.last.at) : 'none', 'mono');
      function toggle() { withFocus(function () { ttOpen[r.key] = !ttOpen[r.key]; renderToolsTasks(v); }); }
      tr.setAttribute('data-fk', 'tt:' + r.key);
      tr.addEventListener('click', toggle);
      tr.addEventListener('keydown', function (e) { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); toggle(); } });
      body.appendChild(tr);
      if (open) { body.appendChild(ttDetail(r, v)); }
    });
  }

  function recentStrip(all) {
    var strip = el('span', 'recent'), recent = (all || []).slice(-10);
    strip.setAttribute('role', 'img');
    strip.setAttribute('aria-label', 'Last ' + recent.length + ' outcomes, oldest first: ' + (recent.map(function (o) { return o === '' ? 'ok' : o; }).join(', ') || 'none'));
    for (var k = 0; k < 10 - recent.length; k++) { var e0 = el('i', 'o'); e0.setAttribute('aria-hidden', 'true'); strip.appendChild(e0); }
    recent.forEach(function (o) { var e1 = el('i', o === '' ? '' : 'x'); e1.setAttribute('aria-hidden', 'true'); e1.title = o === '' ? 'ok' : o; strip.appendChild(e1); });
    return strip;
  }

  function rateCell(tr, r) {
    var rate = cell(tr, '');
    var wrap = el('span', 'rate');
    var bar = el('span', 'bar'), fill = el('i', r.ran && r.successes / r.ran < 0.4 ? 'bad' : r.ran && r.successes / r.ran < 0.7 ? 'warn' : '');
    fill.style.width = (r.ran ? r.successes / r.ran * 100 : 0) + '%';
    bar.appendChild(fill);
    wrap.appendChild(bar);
    wrap.appendChild(el('span', 'mono', r.ran ? r.successes + ' of ' + r.ran : 'no call ran'));
    rate.appendChild(wrap);
  }

  function ttDetail(r, v, span) {
    var tr = el('tr', 'ttdetail'), td = el('td');
    td.colSpan = span || 7;
    var d = el('div', 'ttd');
    var a = el('div'); a.appendChild(el('h4', '', 'Failures by category'));
    var cats = Object.keys(r.failures), total = 0;
    cats.forEach(function (k) { total += r.failures[k]; });
    var bars = el('div', 'bars mono');
    cats.forEach(function (k) {
      var line = el('div'), b = el('span', 'b'), i = el('i');
      i.style.width = (total ? r.failures[k] / total * 100 : 0) + '%';
      b.appendChild(i);
      line.appendChild(el('span', '', k)); line.appendChild(b); line.appendChild(el('span', '', String(r.failures[k])));
      bars.appendChild(line);
    });
    a.appendChild(cats.length ? bars : el('p', 'muted mono small', 'no failure'));
    d.appendChild(a);
    var b2 = el('div'); b2.appendChild(el('h4', '', 'Who calls it'));
    var agents = Object.keys(r.byAgent);
    var ul = el('ul', 'plain mono');
    agents.forEach(function (n) { var t = r.byAgent[n]; ul.appendChild(el('li', '', n + ' · ' + plural(t.calls, 'call') + ' · ' + t.successes + ' ok' + (t.failures ? ' · ' + t.failures + ' failed' : ''))); });
    b2.appendChild(agents.length ? ul : el('p', 'muted mono small', 'no agent'));
    d.appendChild(b2);
    var c = el('div'); c.appendChild(el('h4', '', 'Last ten outcomes'));
    var strip = recentStrip(r.recent);
    c.appendChild(strip);
    c.appendChild(el('h4', '', 'Latency'));
    var hist = svg('svg', { 'class': 'hist', viewBox: '0 0 240 60', role: 'img', 'aria-label': 'Latency histogram: ' + (r.histogram.join(', ') || 'no calls') });
    var maxc = Math.max.apply(null, r.histogram.concat([1]));
    r.histogram.forEach(function (n, i) {
      var w = 240 / Math.max(1, r.histogram.length), hgt = n / maxc * 56;
      hist.appendChild(svg('rect', { x: i * w + 1, y: 58 - hgt, width: Math.max(1, w - 2), height: Math.max(n ? 1 : 0, hgt) }));
    });
    c.appendChild(hist);
    c.appendChild(el('p', 'faint mono small', r.bounds.length ? 'buckets up to ' + r.bounds.map(function (b2) { return b2 >= 1000 ? (b2 / 1000) + 's' : b2 + 'ms'; }).join(' · ') + ' and slower' : ''));
    d.appendChild(c);
    var mine = (v.incidents || []).filter(function (i) { return String(i.title).indexOf(r.name) >= 0; });
    if (mine.length) {
      var e2 = el('div'); e2.appendChild(el('h4', '', 'Incidents it raised'));
      var l2 = el('ul', 'plain mono');
      mine.forEach(function (i) { l2.appendChild(el('li', '', (KINDS[i.kind] || i.kind) + ' · ' + i.title)); });
      e2.appendChild(l2);
      d.appendChild(e2);
    }
    td.appendChild(d);
    tr.appendChild(td);
    return tr;
  }

  function secCard(id, value, foot, cls) {
    var card = $(id), val = card.querySelector('[data-v]');
    val.textContent = value;
    val.className = 'val sm' + (cls ? ' ' + cls : '');
    card.querySelector('[data-f]').textContent = foot;
  }

  function legCell(tr, leg, a) {
    var td = el('td', 'legs'), used = (a.used && a.used[leg]) || [], can = (a.can && a.can[leg]) || [];
    if (used.length) { td.appendChild(el('span', 'leg used', 'used: ' + used.join(', '))); }
    else if (can.length) { td.appendChild(el('span', 'leg can', 'can: ' + can.join(', '))); }
    else { td.appendChild(el('span', 'leg', 'none')); }
    tr.appendChild(td);
  }

  function renderSecurity(v) {
    var sec = v.security;
    $('secstatement').textContent = STATEMENT;
    var agents = (sec && sec.agents) || [];
    if (!sec) {
      ['sec-trifecta', 'sec-injection', 'sec-access', 'sec-data'].forEach(function (id) { secCard(id, '–', 'this server does not report it', ''); });
    } else {
      var ex = agents.filter(function (a) { return a.verdict === 'exercised'; }).length, ac = agents.filter(function (a) { return a.verdict === 'accepted'; }).length, ar = agents.filter(function (a) { return a.verdict === 'armed'; }).length;
      if (ex) { secCard('sec-trifecta', plural(ex, 'agent') + ' exercised it', 'used untrusted content, private data and a way out in one run', 'high'); }
      else if (ac) { secCard('sec-trifecta', plural(ac, 'agent') + ' accepted', 'used all three; accepted in writing', 'warn'); }
      else if (ar) { secCard('sec-trifecta', 'none used', plural(ar, 'agent') + ' could (armed)', ''); }
      else { secCard('sec-trifecta', 'none', 'no agent used all three', ''); }
      var inj = sec.injection || {};
      secCard('sec-injection', inj.suspect ? plural(inj.suspect, 'suspect result') : 'none suspected', inj.suspect ? 'from ' + Object.keys(inj.bySource || {}).join(', ') : 'a heuristic, not a verdict', inj.suspect ? 'warn' : '');
      var acc = sec.access || {};
      secCard('sec-access', (acc.refused || 0) + ' refused', plural(acc.unknownTools || 0, 'unknown tool') + ' · ' + plural(acc.approvalsRefused || 0, 'approval') + ' refused', acc.refused ? 'warn' : '');
      var g = sec.guards || {};
      secCard('sec-data', (g.pii_masked || 0) + ' masked', (g.pii_blocked || 0) + ' blocked · ' + (g.pii_detected || 0) + ' detected', '');
    }
    var body = $('secagents');
    body.textContent = '';
    $('nosecagents').hidden = agents.length > 0;
    agents.forEach(function (a) {
      var tr = el('tr');
      cell(tr, a.agent, 'mono');
      ['untrusted', 'private', 'outward'].forEach(function (leg) { legCell(tr, leg, a); });
      var vc = cell(tr, '');
      vc.appendChild(el('span', 'verdict ' + a.verdict, a.verdict));
      if (a.assumed) { vc.appendChild(el('span', 'chip', 'assumed')); }
      if (!a.can) { vc.appendChild(el('div', 'faint mono small', 'what the script allows is not known')); }
      if (a.acceptedReason) { vc.appendChild(el('div', 'mono small', 'accepted: ' + a.acceptedReason)); }
      body.appendChild(tr);
    });
    var box = $('secfindings');
    box.textContent = '';
    var finds = (v.incidents || []).filter(function (i) { return SECURITY_KINDS[i.kind]; });
    $('nosecfind').hidden = finds.length > 0;
    finds.forEach(function (i) { box.appendChild(incidentRow(i)); });
    var guards = $('secguards');
    guards.textContent = '';
    var gd = (sec && sec.guards) || {};
    [['Personal data', 'masked ' + (gd.pii_masked || 0) + ' · blocked ' + (gd.pii_blocked || 0) + ' · detected ' + (gd.pii_detected || 0)], ['Bias checks', plural(gd.bias || 0, 'event')]].forEach(function (x) {
      var li = el('li'); li.appendChild(el('b', '', x[0] + ': ')); li.appendChild(document.createTextNode(x[1])); guards.appendChild(li);
    });
  }


  // ---- fleet pages: Tools & tasks and Security across every run (5c)
  /** Rows keep the place they had; the live order is only applied when the person asks. Returns the rows to draw and whether the live order differs. */
  function heldOrder(hold, rows) {
    var live = rows.map(function (r) { return r.key; });
    if (!hold.keys) { hold.keys = live.slice(); }
    var kept = hold.keys.filter(function (k) { return live.indexOf(k) >= 0; });
    live.forEach(function (k) { if (kept.indexOf(k) < 0) { kept.push(k); } });
    hold.keys = kept;
    var byKey = {};
    rows.forEach(function (r) { byKey[r.key] = r; });
    return { rows: kept.map(function (k) { return byKey[k]; }), changed: kept.join('|') !== live.join('|') };
  }
  var ftHold = {}, ttHold = {};
  var FLEET_ROWS = 200;   // the most rows a fleet table draws; the page says so when it holds back
  var ftHealth = 'all', ftKind = 'all', ftProc = '', ftOpen = {};

  function openRunTab(trace, tab) {
    selected = trace; agentFilter = null; runTab = tab;
    showProcs = false; showSecrets = false; showConfig = false; showFleet = '';
    tick(true);
  }

  function runLink(label, trace, tab) {
    var b = el('button', 'runlink', label);
    b.type = 'button';
    b.addEventListener('click', function (e) { e.stopPropagation(); openRunTab(trace, tab); });
    return b;
  }

  function chipRow(box, id, options, current, pick) {
    box.textContent = '';
    options.forEach(function (o) {
      var b = el('button', '', o[1]);
      b.type = 'button';
      b.setAttribute('aria-pressed', String(current === o[0]));
      b.setAttribute('data-fk', id + ':' + o[0]);
      b.addEventListener('click', function () { withFocus(function () { pick(o[0]); }); });
      box.appendChild(b);
    });
  }

  function renderFleet() {
    if (showFleet === 'runs') { withFocus(function () { renderRunsList(lastRuns); }); return null; }
    if (showFleet === 'tools') {
      return fetchJson('/api/tools' + (ftProc ? '?client=' + encodeURIComponent(ftProc) : '')).then(function (a) { withFocus(function () { renderFleetTools(a); }); });
    }
    return fetchJson('/api/security').then(function (a) { withFocus(function () { renderFleetSecurity(a); }); });
  }

  function renderFleetTools(a) {
    ['failing', 'degraded', 'healthy', 'unused'].forEach(function (k) { $('ft-' + k).querySelector('[data-v]').textContent = String((a.counts || {})[k] || 0); });
    var rows = (a.rows || []).map(function (t) { var r = ttRow(t.kind || 'tool', t); r.agents = t.agents || []; r.lastRun = t.lastRun; return r; });
    var again = function () { renderFleetTools(a); };
    chipRow($('ftf-health'), 'fth', [['all', 'all'], ['failing', 'failing'], ['degraded', 'degraded'], ['healthy', 'healthy']], ftHealth, function (v) { ftHealth = v; again(); });
    chipRow($('ftf-kind'), 'ftk', [['all', 'tools and tasks'], ['tool', 'tools'], ['task', 'tasks']], ftKind, function (v) { ftKind = v; again(); });
    chipRow($('ftf-proc'), 'ftp', [['', 'all']].concat(clientNames.map(function (n) { return [n, n]; })), ftProc, function (v) { ftProc = v; ftHold = {}; tick(true); });
    var held = heldOrder(ftHold, rows);
    rows = held.rows;
    $('ftsortwrap').hidden = !held.changed;
    var shown = rows.filter(function (r) { return (ftHealth === 'all' || r.state === ftHealth) && (ftKind === 'all' || r.kind === ftKind); });
    $('ftsub').textContent = 'every run this server knows · ' + plural(rows.filter(function (r) { return r.kind === 'tool'; }).length, 'tool') + ' · ' + plural(rows.filter(function (r) { return r.kind === 'task'; }).length, 'task') + (ftProc ? ' · ' + ftProc : '');
    var body = $('ftrows');
    body.textContent = '';
    $('noft').hidden = rows.length > 0;
    var more = $('ftmore');
    more.hidden = shown.length <= FLEET_ROWS;
    more.textContent = 'Showing the first ' + FLEET_ROWS + ' of ' + shown.length + ' rows, worst first. Narrow them with the filters above.';
    shown.slice(0, FLEET_ROWS).forEach(function (r) {
      var open = !!ftOpen[r.key];
      var tr = el('tr', 'tt ' + r.state);
      tr.tabIndex = 0;
      tr.setAttribute('aria-expanded', String(open));
      var first = cell(tr, '', 'mono nm-cell');
      var nm = el('span', 'nm', r.name); nm.title = r.name; first.appendChild(nm);
      if (r.inFlight > 0) { first.appendChild(el('span', 'chip on', r.inFlight + ' in flight')); }
      cell(tr, r.kind, 'mono');
      cell(tr, num(r.calls), 'r mono');
      rateCell(tr, r);
      cell(tr, '').appendChild(recentStrip(r.recent));
      cell(tr, r.ran ? num(r.p50) + ' / ' + num(r.p95) + ' ms' : '–', 'r mono');
      var hc = cell(tr, '');
      var hw = el('span', 'hw ' + r.state, r.state);
      hw.title = r.reason;
      hc.appendChild(hw);
      var top = Object.keys(r.failures).sort(function (x, y) { return r.failures[y] - r.failures[x]; })[0];
      cell(tr, (r.reason || '') + (top ? ' · mostly ' + top : ''), 'why small');
      function toggle() { withFocus(function () { ftOpen[r.key] = !ftOpen[r.key]; again(); }); }
      tr.setAttribute('data-fk', 'ft:' + r.key);
      tr.addEventListener('click', toggle);
      tr.addEventListener('keydown', function (e) { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); toggle(); } });
      body.appendChild(tr);
      if (open) {
        var d = ttDetail(r, { incidents: [] }, 8);
        var box = d.querySelector('.ttd');
        var extra = el('div');
        extra.appendChild(el('h4', '', 'Last failure'));
        if (r.lastRun) {
          extra.appendChild(runLink('Open the run it last failed in', r.lastRun.traceId, 'tools'));
          extra.appendChild(el('p', 'faint mono small', r.lastRun.client + ' · ' + r.lastRun.category + ' · ' + ago(r.lastRun.at)));
        } else { extra.appendChild(el('p', 'muted mono small', 'it has not failed')); }
        box.appendChild(extra);
        body.appendChild(d);
      }
    });
  }

  var rlFilter = 'all', rlHold = {};
  var RL_STATUS = [['all', 'all'], ['live', 'live'], ['finished', 'finished'], ['failed', 'failed']];
  function renderRunsList(runs) {
    var q = $('rl-search').value.trim().toLowerCase();
    chipRow($('rl-status'), 'rls', RL_STATUS, rlFilter, function (v) { rlFilter = v; renderRunsList(runs); });
    var shown = runs.filter(function (r) {
      if (rlFilter === 'live' && !LIVE[r.status]) { return false; }
      if (rlFilter === 'finished' && r.status !== 'finished') { return false; }
      if (rlFilter === 'failed' && r.status !== 'failed' && r.status !== 'stopped') { return false; }
      return !q || ((r.client || '') + ' ' + (r.workflow || '') + ' ' + r.status + ' ' + (r.runId || '')).toLowerCase().indexOf(q) >= 0;
    });
    shown.sort(function (x, y) {
      var a = (x.client || '') + '/' + (x.workflow || ''), b = (y.client || '') + '/' + (y.workflow || '');
      return a < b ? -1 : a > b ? 1 : String(y.startedAt || '').localeCompare(String(x.startedAt || ''));
    });
    $('rlsub').textContent = plural(runs.length, 'run') + ' this server knows' + (shown.length !== runs.length ? ' · ' + shown.length + ' shown' : '');
    var body = $('rlrows');
    body.textContent = '';
    $('norl').hidden = runs.length > 0;
    var group = null, perGroup = {};
    shown.forEach(function (r) { var k = (r.client || '') + ' / ' + (r.workflow || 'run'); perGroup[k] = (perGroup[k] || 0) + 1; });
    shown.forEach(function (r) {
      var g = (r.client || '') + ' / ' + (r.workflow || 'run');
      if (g !== group && perGroup[g] > 1) {   // a heading only where a workflow has several runs to tell apart
        group = g;
        var gr = el('tr', 'group'), gt = el('td', 'mono', g + ' · ' + perGroup[g] + ' runs');
        gt.colSpan = 6;
        gr.appendChild(gt);
        body.appendChild(gr);
      } else if (g !== group) {
        group = g;
        var gr = el('tr', 'group'), gt = el('td', 'mono', g);
        gt.colSpan = 6;
        gr.appendChild(gt);
        body.appendChild(gr);
      }
      var tr = el('tr', 'rl' + (r.traceId === selected ? ' sel' : ''));
      tr.tabIndex = 0;
      tr.setAttribute('data-fk', 'rl:' + r.traceId);
      cell(tr, r.client || '', 'mono');
      cell(tr, r.workflow || 'run', 'mono');
      var st = cell(tr, '', 'st');
      st.appendChild(el('span', 'dot ' + tabState(r.status)));
      st.appendChild(document.createTextNode(' ' + r.status));
      cell(tr, r.startedAt ? new Date(r.startedAt).toLocaleString() : '–', 'mono');
      var inc = cell(tr, '');
      if (r.incidents > 0) { inc.appendChild(el('span', 'sev ' + (r.worstIncident || 'warn') + ' inc', String(r.incidents))); } else { inc.textContent = '–'; }
      cell(tr, '$' + Number((r.totals && r.totals.cost) || 0).toFixed(3), 'r mono');
      function open() { openRunTab(r.traceId, runTab); }
      tr.addEventListener('click', open);
      tr.addEventListener('keydown', function (e) { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); open(); } });
      body.appendChild(tr);
    });
  }
  $('rl-search').addEventListener('input', function () { renderRunsList(lastRuns); });

  $('ftsort').addEventListener('click', function () { ftHold = {}; tick(true); });
  $('ttsort').addEventListener('click', function () { ttHold = {}; render(); });

  function renderFleetSecurity(a) {
    var c = a.counts || {}, agents = a.agents || [];
    function card(id, value, foot, cls) { var t = $(id), v = t.querySelector('[data-v]'); v.textContent = value; v.className = 'val sm' + (cls ? ' ' + cls : ''); t.querySelector('[data-f]').textContent = foot; }
    var armed = agents.filter(function (x) { return x.verdict === 'armed'; }).length;
    if (c.trifectaExercised) { card('fs-trifecta', plural(c.trifectaExercised, 'agent') + ' exercised it', 'used untrusted content, private data and a way out in one run', 'high'); }
    else if (c.trifectaAccepted) { card('fs-trifecta', plural(c.trifectaAccepted, 'agent') + ' accepted', 'used all three; accepted in writing', 'warn'); }
    else if (armed) { card('fs-trifecta', 'none used', plural(armed, 'agent') + ' could (armed)', ''); }
    else { card('fs-trifecta', 'none', 'no agent used all three', ''); }
    card('fs-injection', c.injectionSuspect ? plural(c.injectionSuspect, 'suspect result') : 'none suspected', 'a heuristic, not a verdict', c.injectionSuspect ? 'warn' : '');
    card('fs-access', (c.accessRefused || 0) + ' refused', plural(c.unknownTools || 0, 'unknown tool') + ' · ' + plural(c.approvalsRefused || 0, 'approval') + ' refused', c.accessRefused ? 'warn' : '');
    card('fs-data', (c.piiMasked || 0) + ' masked', (c.piiBlocked || 0) + ' blocked · ' + (c.piiDetected || 0) + ' detected', '');
    var body = $('fsagents');
    body.textContent = '';
    $('nofsagents').hidden = agents.length > 0;
    agents.forEach(function (x) {
      var tr = el('tr');
      var run = cell(tr, '', 'mono');
      run.appendChild(runLink((x.client || '') + ' / ' + (x.workflow || 'run'), x.traceId, 'security'));
      cell(tr, x.agent, 'mono');
      var vc = cell(tr, '');
      vc.appendChild(el('span', 'verdict ' + x.verdict, x.verdict));
      if (x.assumed) { vc.appendChild(el('span', 'chip', 'assumed')); }
      cell(tr, x.acceptedReason ? 'accepted: ' + x.acceptedReason : x.verdict === 'armed' ? 'could use all three; has not' : 'used all three in this run', 'small');
      body.appendChild(tr);
    });
    var box = $('fsfind');
    box.textContent = '';
    var finds = a.incidents || [];
    $('nofsfind').hidden = finds.length > 0;
    finds.forEach(function (i) {
      var row = incidentRow(i);
      var l = el('div', 'runlinkrow');
      l.appendChild(runLink('Open the run (' + (i.client || '') + ')', i.traceId, 'security'));
      row.appendChild(l);
      box.appendChild(row);
    });
    $('fsstatement').textContent = STATEMENT;
  }

  var RUNGS = ['watch', 'suggest', 'act'];

  function renderLadders(list) {
    $('autonomy').hidden = list.length === 0;
    var box = $('ladders');
    box.textContent = '';
    $('autosub').textContent = list.length + (list.length === 1 ? ' decision' : ' decisions');
    list.forEach(function (d) {
      var row = el('div', 'ladder'), left = el('div');
      left.appendChild(el('span', 'nm', d.name));
      left.appendChild(el('span', 'faint', (d.agent ? '  ' + d.agent : '') + (d.started ? '' : '  · not asked yet')));
      row.appendChild(left);
      var track = el('div', 'track'), at = RUNGS.indexOf(d.level), cap = RUNGS.indexOf(d.ceiling);
      RUNGS.forEach(function (r, i) {
        track.appendChild(el('span', 'rung ' + r + (i === at ? ' now' : i < at ? ' passed' : '') + (cap >= 0 && i > cap ? ' over' : ''), r));
      });
      row.appendChild(track);
      if (d.frozen || (d.scopes && d.scopes.length)) {
        var sc = el('div', 'sc');
        if (d.frozen) { sc.appendChild(el('span', 'chip err', 'frozen: act is stopped')); }
        (d.scopes || []).forEach(function (s) {
          sc.appendChild(el('span', 'chip', (s.scope && s.scope !== 'all' ? s.scope : 'all') + ' · ' + s.level + (s.forced ? ' (forced)' : '')));
        });
        row.appendChild(sc);
      }
      box.appendChild(row);
    });
  }

  var csrf = null;
  var ASKS = {
    pause: ['Pause this run?', 'The run holds before its next step and keeps its thread. Resume continues exactly where it stopped.', 'Pause'],
    resume: ['Resume this run?', 'The run carries on from the step it was holding before.', 'Resume'],
    suspend: ['Suspend this run?', 'The run stops at its next step and releases its thread. It can be resumed later from its journal (weave resume).', 'Suspend'],
    stop: ['Stop this run?', 'The run ends at its next step and cannot be resumed. A step already under way finishes first.', 'Stop']
  };

  function sessionCsrf() {
    return fetchJson('/api/session').then(function (s) { csrf = s.csrf || null; return s; });
  }

  function act(trace, action, errBox, done) {
    return sessionCsrf().then(function (s) {
      if (!s.signedIn) { throw new Error('Your session ended. Sign in again.'); }
      return fetch('/api/runs/' + trace + '/' + action, { method: 'POST', headers: { 'Content-Type': 'application/json', 'X-CSRF-Token': csrf }, body: '{}' });
    }).then(function (r) { return r.json().catch(function () { return {}; }).then(function (b) { if (!r.ok) { throw new Error(b.message || b.error || ('Refused (' + r.status + ')')); } done(b); }); })
      .catch(function (e) { errBox.textContent = e.message; errBox.hidden = false; });
  }

  function confirmAction(trace, action) {
    var a = ASKS[action], dlg = $('confirm'), err = $('cf-err');
    $('cf-title').textContent = a[0];
    $('cf-text').textContent = a[1];
    $('cf-ok').textContent = a[2];
    $('cf-ok').className = 'btn primary' + (action === 'stop' ? ' danger' : '');
    err.hidden = true;
    dlg.onclose = function () {
      if (dlg.returnValue === 'ok') {
        var box = el('span');
        act(trace, action, box, function () { tick(true); }).then(function () { if (box.textContent) { $('updated').textContent = box.textContent; } });
      }
    };
    dlg.returnValue = 'cancel';
    dlg.showModal();
  }

  function renderControls(v) {
    var box = $('controls'), c = v.controls;
    box.textContent = '';
    if (!c || showProcs || showSecrets || showConfig || showFleet) { box.hidden = true; return; } // a run's controls belong to the run's own page
    box.hidden = false;
    var ended = ['finished', 'failed', 'stopped'].indexOf(v.status) >= 0;
    if (ended) { box.hidden = true; return; }
    if (v.control && v.control.pending) {
      box.appendChild(el('span', 'chip', v.control.pending + ' requested' + (v.control.by ? ' by ' + v.control.by : '')));
    } else if (v.control && v.control.held) {
      box.appendChild(el('span', 'chip', 'held by operator'));
    }
    if (!c.enabled) { box.appendChild(el('span', 'why', 'controls off'));  box.title = c.reason || ''; return; }
    if (!c.signedIn) {
      var si = el('button', 'btn', 'Sign in to control');
      si.type = 'button';
      si.addEventListener('click', function () { $('si-err').hidden = true; $('signin').showModal(); $('sipw').focus(); });
      box.appendChild(si);
      return;
    }
    ['pause', 'resume', 'suspend', 'stop'].forEach(function (a) {
      if ((c.allowed || []).indexOf(a) < 0) { return; }
      var b = el('button', 'btn' + (a === 'stop' ? ' danger' : ''), ASKS[a][2]);
      b.type = 'button';
      b.addEventListener('click', function () { confirmAction(v.traceId, a); });
      box.appendChild(b);
    });
  }

  // ---- secrets (embedded only): names, fingerprints, and one write that replaces a value
  var rotating = null;
  function renderSecrets() {
    var on = showSecrets;
    $('secrets').hidden = !on;
    if (!on) { return; }
    ['main', 'empty', 'processes', 'banner'].forEach(function (i) { $(i).hidden = true; });
    ['status', 'meta', 'conn'].forEach(function (i) { $(i).hidden = true; });
    $('wfname').textContent = ''; $('client').textContent = '';
    $('controls').hidden = true;
    $('secsignin').hidden = !!sess.signedIn;
    $('sectablewrap').hidden = !sess.signedIn;
    $('secnote').hidden = !sess.signedIn;
    if (!sess.signedIn) { return; }
    fetchJson('/api/secrets').then(function (d) {
      var body = $('sectable');
      body.textContent = '';
      $('secsub').textContent = d.secrets.length + ' secret' + (d.secrets.length === 1 ? '' : 's');
      d.secrets.forEach(function (s) {
        var tr = el('tr');
        cell(tr, s.name, 'mono');
        cell(tr, s.present ? (s.fingerprint || '–') : 'not in the store', 'mono' + (s.present ? '' : ' err'));
        cell(tr, s.present ? ((s.allowedHosts || []).join(', ') || 'any host') : '–', 'mono');
        cell(tr, s.updatedAt ? new Date(s.updatedAt).toLocaleString() : '–', 'mono');
        cell(tr, (s.usedBy || []).join('; ') || '–');
        var td = el('td');
        if (s.present) {
          var b = el('button', 'btn', 'Replace');
          b.type = 'button';
          b.addEventListener('click', function () {
            rotating = s.name;
            $('ro-text').textContent = 'New value for ' + s.name + '. It is stored in the app\'s secret store and is never shown again.';
            $('ro-err').hidden = true;
            $('rotate').showModal();
            $('ro-value').focus();
          });
          td.appendChild(b);
        }
        tr.appendChild(td);
        body.appendChild(tr);
      });
    }).catch(function () { });
  }
  $('secsigninbtn').addEventListener('click', function () { $('si-err').hidden = true; $('signin').showModal(); $('sipw').focus(); });
  $('ro-cancel').addEventListener('click', function () { $('rotate').close(); });
  $('rotateform').addEventListener('submit', function (e) {
    e.preventDefault();
    var value = $('ro-value').value, pw = $('ro-pw').value;
    sessionCsrf().then(function (s) {
      if (!s.signedIn) { throw new Error('Your session ended. Sign in again.'); }
      return fetch('/api/secrets/' + encodeURIComponent(rotating), { method: 'PUT', headers: { 'Content-Type': 'application/json', 'X-CSRF-Token': csrf }, body: JSON.stringify({ value: value, password: pw }) });
    }).then(function (r) {
      return r.json().catch(function () { return {}; }).then(function (b) {
        if (!r.ok) { throw new Error(b.message || b.error || ('Refused (' + r.status + ')')); }
        $('ro-value').value = ''; $('ro-pw').value = '';
        $('rotate').close();
        $('secmsg').textContent = rotating + ': ' + b.message;
        $('secmsg').hidden = false;
        renderSecrets();
      });
    }).catch(function (err) { $('ro-err').textContent = err.message; $('ro-err').hidden = false; $('ro-pw').value = ''; });
  });

  // ---- configuration (Fabric): inspect rules and the price table, chosen from what the runs show, kept on the server
  // Fabric carries its own name and mark; the embedded page keeps loom-inspect and the Loom mark
  var branded = null;
  function brand() {
    if (branded === fabric) { return; }
    branded = fabric;
    var name = $('brandname'), logo = $('logo');
    name.textContent = '';
    if (fabric) { name.textContent = 'Fabric'; } else { name.appendChild(document.createTextNode('loom')); var b = document.createElement('b'); b.textContent = '\u00b7'; name.appendChild(b); name.appendChild(document.createTextNode('inspect')); }
    logo.classList.toggle('fabric', fabric);
    logo.setAttribute('aria-label', fabric ? 'Fabric' : 'Loom');
    $('favicon').setAttribute('href', fabric ? '/fabric-logo.svg' : '/logo.png');
    $('favicon').setAttribute('type', fabric ? 'image/svg+xml' : 'image/png');
    document.title = fabric ? 'Fabric' : 'loom-inspect';
  }
  var cfg = { catalog: { agents: [], models: [] }, defaults: {}, rules: {}, prices: [], loaded: false, rulesDirty: false, pricesDirty: false, picked: null, windows: [], windowsDirty: false, wpicked: null };
  var BEHAVIOURS = [
    { key: 'same_action', label: 'Same tool call repeated', hint: 'the same tool called with the same input this many times in one step' },
    { key: 'same_output', label: 'Same answer repeated', hint: 'the same answer from the same step this many times in a row' },
    { key: 'tool_failures', label: 'Tool keeps failing', hint: 'a tool failing this many times in a row' },
    { key: 'ungrounded_figures', label: 'Figures with no source', hint: 'figures in an answer found in nothing the step saw: warn at one count, high at a larger one' },
    { key: 'paused_longer_than', label: 'Paused for too long', hint: 'a pause (rate limit, budget window) longer than this becomes a warning' },
    { key: 'context_nearly_full', label: 'Context nearly full', full: true, hint: 'An agent whose prompt fills this share of its usable window: a warning at the first, high at the second.' },
    { key: 'truncated_answers', label: 'Truncated answers', full: true, hint: 'Replies cut off at the token limit: a warning after this many.' },
    { key: 'compaction_thrash', label: 'Compaction thrash', full: true, hint: 'Compaction that runs this often yet frees little, within a window of model calls.' },
    { key: 'tool_success_rate', label: 'Tool success rate', full: true, hint: 'A tool or task whose recent success rate drops below this share: a warning, high at 40% of it. Needs at least 5 calls.' },
    { key: 'latency_spike', label: 'Latency spike', full: true, hint: 'A call that takes this many times the tool\'s usual (median) time.' },
    { key: 'access_denied', label: 'Access refused', full: true, hint: 'A tool refusing an agent this many times within the span.' },
    { key: 'injection_scan', label: 'Injection scan', full: true, hint: 'Whether results from tools that read content you do not control are scanned for text that reads like instructions.' },
    { key: 'trifecta_accepted', label: 'Accepted trifecta', full: true, hint: 'Accept, in writing, that this agent uses all three legs of the lethal trifecta. The reason is required; the alert drops from high to info for this agent.' },
    { key: 'no_progress', label: 'No progress across loop rounds', hint: 'a workflow variable unchanged at the end of this many loop rounds in a row. One agent only. It applies to runs whose process already watches this variable in its own rules; the rounds set here replace that count' }
  ];
  function behaviour(key) { return BEHAVIOURS.filter(function (b) { return b.key === key; })[0]; }
  function sectionUi(name) { return { text: $(name + '-text'), summary: $(name + '-summary'), problems: $(name + '-problems'), msg: $(name + '-msg') }; }
  function showProblems(u, list) {
    u.problems.textContent = '';
    (list || []).forEach(function (p) { u.problems.appendChild(el('li', /^warning/.test(p) ? 'note' : '', p)); });
    u.problems.hidden = !(list && list.length);
  }
  function opt(sel, value, label) { var o = el('option', '', label); o.value = value; sel.appendChild(o); }
  function describe(key, v) {
    if (key === 'ungrounded_figures') { return [v.warn != null ? 'warn at ' + v.warn : null, v.high != null ? 'high at ' + v.high : null].filter(Boolean).join(', '); }
    if (key === 'paused_longer_than') { return span(v); }
    if (key === 'no_progress') { return v.rounds + ' rounds on ' + v.variable; }
    if (key === 'context_nearly_full') { return [v.warn != null ? 'warn ' + v.warn + '%' : null, v.high != null ? 'high ' + v.high + '%' : null].filter(Boolean).join(', '); }
    if (key === 'truncated_answers') { return plural(v, 'time'); }
    if (key === 'compaction_thrash') { return v.count + ' in ' + plural(v.of_calls, 'call'); }
    if (key === 'tool_success_rate') { return 'below ' + v.below + '% over ' + plural(v.over_calls, 'call'); }
    if (key === 'latency_spike') { return v + '\u00d7 the median'; }
    if (key === 'access_denied') { return v.count + ' in ' + v.seconds + ' s'; }
    if (key === 'injection_scan') { return v ? 'on' : 'off'; }
    if (key === 'trifecta_accepted') { return 'accepted: ' + v; }
    return v + ' times';
  }
  function span(s) { return s % 86400 === 0 ? s / 86400 + 'd' : s % 3600 === 0 ? s / 3600 + 'h' : s % 60 === 0 ? s / 60 + 'm' : s + 's'; }
  function defaultOf(key) {
    var d = cfg.defaults[key];
    if (key === 'trifecta_accepted') { return 'not accepted'; }
    if (d == null) { return '–'; }
    return describe(key, d);
  }

  // ---- rules: agent, behaviour, threshold
  function buildFields() {
    var key = $('rb-rule').value, box = $('rb-fields'), d = cfg.defaults;
    box.textContent = '';
    function num(id, label, value) {
      var l = el('label', '', label + ' '), i = el('input'); i.type = 'number'; i.min = '1'; i.id = id; i.value = value == null ? '' : value;
      l.appendChild(i); box.appendChild(l);
    }
    if (key === 'same_action' || key === 'same_output' || key === 'tool_failures') { num('rf-n', 'Flag at', d[key]); box.appendChild(el('span', 'muted', 'times')); }
    if (key === 'ungrounded_figures') { num('rf-warn', 'Warn at', d.ungrounded_figures && d.ungrounded_figures.warn); num('rf-high', 'High at', d.ungrounded_figures && d.ungrounded_figures.high); }
    if (key === 'paused_longer_than') {
      num('rf-n', 'Longer than', 60);
      var u = el('select'); u.id = 'rf-unit'; opt(u, 's', 'seconds'); opt(u, 'm', 'minutes'); opt(u, 'h', 'hours'); opt(u, 'd', 'days'); u.value = 'm'; $('rf-n').value = 1; box.appendChild(u);
    }
    if (key === 'no_progress') {
      num('rf-n', 'Flag after', 3); box.appendChild(el('span', 'muted', 'rounds on'));
      var v = el('input'); v.type = 'text'; v.id = 'rf-var'; v.placeholder = 'review.score'; v.spellcheck = false; box.appendChild(v);
    }
    var dd = d[key] || {};
    function fld(id, label, value, unit) { num(id, label, value); if (unit) { box.appendChild(el('span', 'muted', unit)); } }
    if (key === 'context_nearly_full') { fld('rf-warn', 'Warn at', dd.warn, '%'); fld('rf-high', 'High at', dd.high, '%'); }
    if (key === 'truncated_answers') { fld('rf-n', 'Warn after', d[key], 'times'); }
    if (key === 'compaction_thrash') { fld('rf-count', 'Compactions', dd.count, 'in'); fld('rf-of', 'Within', dd.of_calls, 'model calls'); }
    if (key === 'tool_success_rate') { fld('rf-below', 'Below', dd.below, '% over the last'); fld('rf-over', '', dd.over_calls, 'calls'); }
    if (key === 'latency_spike') { fld('rf-factor', 'Flag at', d[key], '\u00d7 the median'); }
    if (key === 'access_denied') { fld('rf-count', 'Refusals', dd.count, 'within'); fld('rf-seconds', '', dd.seconds, 'seconds'); }
    if (key === 'injection_scan') {
      var sw = el('label', '', 'Scan results '), on = el('select'); on.id = 'rf-onoff'; opt(on, 'on', 'on'); opt(on, 'off', 'off'); on.value = d[key] === false ? 'off' : 'on'; sw.appendChild(on); box.appendChild(sw);
    }
    if (key === 'trifecta_accepted') {
      var rl = el('label', '', 'Reason '), ri = el('input'); ri.type = 'text'; ri.id = 'rf-reason'; ri.size = 48; ri.placeholder = 'who reviewed it, and why it is safe'; ri.spellcheck = false; rl.appendChild(ri); box.appendChild(rl);
    }
    if ((behaviour(key) || {}).full) { $('rb-hint').textContent = behaviour(key).hint + ' Default: ' + defaultOf(key) + '.'; return; }
    $('rb-hint').textContent = (behaviour(key) || {}).hint ? 'Flags ' + behaviour(key).hint + '. Default: ' + defaultOf(key) + '.' : '';
  }
  function agentChosen() { return $('rb-agent').value === '__other' ? $('rb-other').value.trim() : $('rb-agent').value; }
  function renderBuilder() {
    var sel = $('rb-agent'), keep = sel.value;
    sel.textContent = '';
    opt(sel, '*', 'All agents (*)');
    cfg.catalog.agents.forEach(function (a) { opt(sel, a, a); });
    Object.keys(cfg.rules).forEach(function (a) { if (a !== '*' && cfg.catalog.agents.indexOf(a) < 0) { opt(sel, a, a); } });
    opt(sel, '__other', 'Another agent…');
    if (keep) { sel.value = keep; }
    if (!$('rb-rule').options.length) { BEHAVIOURS.forEach(function (b) { opt($('rb-rule'), b.key, b.label); }); buildFields(); }
    $('rb-other-wrap').hidden = sel.value !== '__other';
  }
  function addRule() {
    var u = sectionUi('rules'), agent = agentChosen(), key = $('rb-rule').value, problem = null, value;
    function whole(id) { var n = Number($(id).value); return isFinite(n) && n >= 1 && Math.floor(n) === n ? n : null; }
    if (!agent) { problem = 'Name the agent.'; }
    else if (!/^(\*|[A-Za-z_][A-Za-z0-9_]*)$/.test(agent)) { problem = 'An agent name is letters, digits and _.'; }
    if (!problem) {
      if (key === 'ungrounded_figures') {
        var w = whole('rf-warn'), hh = whole('rf-high');
        if (w == null && hh == null) { problem = 'Give a whole number for warn, for high, or both.'; }
        else if (w != null && hh != null && w >= hh) { problem = 'Warn must be lower than high.'; }
        else { value = {}; if (w != null) { value.warn = w; } if (hh != null) { value.high = hh; } }
      } else if (key === 'paused_longer_than') {
        var n = whole('rf-n'); if (n == null) { problem = 'Give a whole number.'; } else { value = n * { s: 1, m: 60, h: 3600, d: 86400 }[$('rf-unit').value]; }
      } else if (key === 'no_progress') {
        var r = whole('rf-n'), name = $('rf-var').value.trim();
        if (agent === '*') { problem = 'A loop variable belongs to one agent: pick that agent.'; }
        else if (r == null) { problem = 'Give a whole number of rounds.'; }
        else if (!/^[A-Za-z_][A-Za-z0-9_.]*$/.test(name)) { problem = 'Name the workflow variable, like review.score.'; }
        else { value = { rounds: r, variable: name }; }
      } else if (key === 'context_nearly_full') {
        var cw = whole('rf-warn'), ch = whole('rf-high');   // the server checks warn below high and the range; its words are shown as they come
        if (cw == null && ch == null) { problem = 'Give a whole number of percent for warn, for high, or both.'; }
        else { value = {}; if (cw != null) { value.warn = cw; } if (ch != null) { value.high = ch; } }
      } else if (key === 'compaction_thrash') {
        var tc = whole('rf-count'), to = whole('rf-of');
        if (tc == null || to == null) { problem = 'Give whole numbers for the compactions and the calls.'; } else { value = { count: tc, of_calls: to }; }
      } else if (key === 'tool_success_rate') {
        var tb = whole('rf-below'), tv = whole('rf-over');
        if (tb == null || tv == null) { problem = 'Give whole numbers for the percent and the calls.'; } else { value = { below: tb, over_calls: tv }; }
      } else if (key === 'access_denied') {
        var ac = whole('rf-count'), as = whole('rf-seconds');
        if (ac == null || as == null) { problem = 'Give whole numbers for the refusals and the seconds.'; } else { value = { count: ac, seconds: as }; }
      } else if (key === 'latency_spike') {
        var lf = Number($('rf-factor').value);
        if (!isFinite(lf) || lf <= 0) { problem = 'Give a number above zero.'; } else { value = lf; }
      } else if (key === 'injection_scan') {
        value = $('rf-onoff').value === 'on';
      } else if (key === 'trifecta_accepted') {
        var why = $('rf-reason').value.trim();
        if (!why) { problem = 'An accepted trifecta needs a reason: who reviewed it and why it is safe. It is never accepted silently.'; } else { value = why; }
      } else {
        var c = whole('rf-n'); if (c == null) { problem = 'Give a whole number.'; } else { value = c; }
      }
    }
    if (problem) { showProblems(u, [problem]); return; }
    showProblems(u, []);
    (cfg.rules[agent] = cfg.rules[agent] || {})[key] = value;
    cfg.rulesDirty = true;
    renderRules();
    u.msg.textContent = 'unsaved changes';
  }
  function renderRules() {
    var body = $('rules-table'), rows = 0;
    body.textContent = '';
    Object.keys(cfg.rules).sort(function (a, b) { return a === '*' ? -1 : b === '*' ? 1 : a < b ? -1 : 1; }).forEach(function (agent) {
      BEHAVIOURS.forEach(function (b) {
        var v = cfg.rules[agent][b.key];
        if (v == null) { return; }
        rows++;
        var tr = el('tr');
        cell(tr, agent === '*' ? 'All agents' : agent, 'mono');
        cell(tr, b.label);
        cell(tr, describe(b.key, v), 'mono');
        cell(tr, defaultOf(b.key), 'mono faint');
        var td = el('td'), rm = el('button', 'btn', 'Remove');
        rm.type = 'button';
        rm.addEventListener('click', function () {
          delete cfg.rules[agent][b.key];
          if (!Object.keys(cfg.rules[agent]).length) { delete cfg.rules[agent]; }
          cfg.rulesDirty = true; sectionUi('rules').msg.textContent = 'unsaved changes'; renderRules();
        });
        td.appendChild(rm); tr.appendChild(td); body.appendChild(tr);
      });
    });
    $('rules-none').hidden = rows > 0;
    renderBuilder();
  }

  // ---- prices: the models in use, input and output set apart
  function pricedIndex(model) { for (var i = 0; i < cfg.prices.length; i++) { if (cfg.prices[i].model === model) { return i; } } return -1; }
  function renderPrices() {
    var body = $('prices-table'), models = {}, order = [];
    body.textContent = '';
    cfg.catalog.models.forEach(function (m) { models[m.model] = m.agents; order.push(m.model); });
    cfg.prices.forEach(function (p) { if (!(p.model in models)) { models[p.model] = []; order.push(p.model); } });
    if (cfg.picked && !(cfg.picked in models)) { models[cfg.picked] = []; order.push(cfg.picked); }
    order.sort();
    order.forEach(function (model) {
      var i = pricedIndex(model), p = i >= 0 ? cfg.prices[i] : null, tr = el('tr', cfg.picked === model ? 'sel' : '');
      cell(tr, model, 'mono');
      cell(tr, models[model].join(', ') || '–', 'mono faint');
      cell(tr, p ? p.input : 'not priced', 'r mono' + (p ? '' : ' unpriced'));
      cell(tr, p ? p.output : 'not priced', 'r mono' + (p ? '' : ' unpriced'));
      var td = el('td'), b = el('button', 'btn', p ? 'Edit' : 'Set price');
      b.type = 'button';
      b.addEventListener('click', function () { pickModel(model); });
      td.appendChild(b); tr.appendChild(td); body.appendChild(tr);
    });
    $('prices-none').hidden = order.length > 0;
    $('pb').hidden = !cfg.picked;
  }
  function pickModel(model) {
    cfg.picked = model;
    var i = pricedIndex(model);
    $('pb-model').textContent = model;
    $('pb-in').value = i >= 0 ? cfg.prices[i].input : '';
    $('pb-out').value = i >= 0 ? cfg.prices[i].output : '';
    renderPrices();
    $('pb-in').focus();
  }
  function setPrice() {
    var u = sectionUi('prices'), a = $('pb-in').value.trim(), b = $('pb-out').value.trim();
    if (!/^\d+(\.\d+)?$/.test(a) || !/^\d+(\.\d+)?$/.test(b)) { showProblems(u, ['Give the input and the output price as numbers, in dollars per million tokens.']); return; }
    showProblems(u, []);
    var i = pricedIndex(cfg.picked), entry = { model: cfg.picked, input: String(Number(a)), output: String(Number(b)) };
    if (i >= 0) { cfg.prices[i] = entry; } else { cfg.prices.push(entry); }
    cfg.pricesDirty = true; cfg.picked = null; u.msg.textContent = 'unsaved changes'; renderPrices();
  }
  function removePrice() {
    var i = pricedIndex(cfg.picked);
    if (i >= 0) { cfg.prices.splice(i, 1); cfg.pricesDirty = true; sectionUi('prices').msg.textContent = 'unsaved changes'; }
    cfg.picked = null; renderPrices();
  }

  // ---- context windows: the usable window of a model, for agents whose process did not report one
  function windowIndex(model) { for (var i = 0; i < cfg.windows.length; i++) { if (cfg.windows[i].model === model) { return i; } } return -1; }
  function renderWindows() {
    var body = $('windows-table'), models = {}, order = [];
    body.textContent = '';
    cfg.catalog.models.forEach(function (m) { models[m.model] = m.agents; order.push(m.model); });
    cfg.windows.forEach(function (w) { if (!(w.model in models)) { models[w.model] = []; order.push(w.model); } });
    if (cfg.wpicked && !(cfg.wpicked in models)) { models[cfg.wpicked] = []; order.push(cfg.wpicked); }
    order.sort();
    order.forEach(function (model) {
      var i = windowIndex(model), w = i >= 0 ? cfg.windows[i] : null, tr = el('tr', cfg.wpicked === model ? 'sel' : '');
      cell(tr, model, 'mono');
      cell(tr, models[model].join(', ') || '–', 'mono faint');
      cell(tr, w ? num(w.window) : 'no window', 'r mono' + (w ? '' : ' unpriced'));
      var td = el('td'), b = el('button', 'btn', w ? 'Edit' : 'Set window');
      b.type = 'button';
      b.addEventListener('click', function () { pickWindow(model); });
      td.appendChild(b); tr.appendChild(td); body.appendChild(tr);
    });
    $('windows-none').hidden = order.length > 0;
    $('wb').hidden = !cfg.wpicked;
  }
  function pickWindow(model) {
    cfg.wpicked = model;
    var i = windowIndex(model);
    $('wb-model').textContent = model;
    $('wb-window').value = i >= 0 ? cfg.windows[i].window : '';
    renderWindows();
    $('wb-window').focus();
  }
  function setWindow() {
    var u = sectionUi('windows'), n = $('wb-window').value.trim();
    if (!/^\d+$/.test(n)) { showProblems(u, ['Give the usable window as a whole number of tokens.']); return; }
    showProblems(u, []);
    var i = windowIndex(cfg.wpicked), entry = { model: cfg.wpicked, window: Number(n) };
    if (i >= 0) { cfg.windows[i] = entry; } else { cfg.windows.push(entry); }
    cfg.windowsDirty = true; cfg.wpicked = null; u.msg.textContent = 'unsaved changes'; renderWindows();
  }
  function removeWindow() {
    var i = windowIndex(cfg.wpicked);
    if (i >= 0) { cfg.windows.splice(i, 1); cfg.windowsDirty = true; sectionUi('windows').msg.textContent = 'unsaved changes'; }
    cfg.wpicked = null; renderWindows();
  }

  // ---- load and save
  function loadConfig(force) {
    return fetchJson('/api/config').then(function (d) {
      cfg.catalog = d.catalog && d.catalog.agents ? d.catalog : { agents: [], models: [] };
      var r = d.sections.rules, p = d.sections.prices, wn = d.sections.windows || { text: '', summary: '', warnings: [], structured: [] };
      d.sections.windows = wn;
      cfg.defaults = r.defaults || {};
      ['rules', 'windows', 'prices'].forEach(function (name) {
        var u = sectionUi(name), s = d.sections[name];
        u.summary.textContent = s.summary;
        if (force || !cfg.loaded) { u.text.value = s.text; }
        if (!u.problems.dataset.err) { showProblems(u, s.warnings); }
      });
      if (force || !cfg.loaded || !cfg.rulesDirty) { cfg.rules = JSON.parse(JSON.stringify(r.structured || {})); cfg.rulesDirty = false; }
      if (force || !cfg.loaded || !cfg.pricesDirty) { cfg.prices = JSON.parse(JSON.stringify(p.structured || [])); cfg.pricesDirty = false; }
      if (force || !cfg.loaded || !cfg.windowsDirty) { cfg.windows = JSON.parse(JSON.stringify(wn.structured || [])); cfg.windowsDirty = false; }
      cfg.loaded = true;
      renderRules();
      renderWindows();
      renderPrices();
    });
  }
  function renderConfig() {
    var on = showConfig;
    $('config').hidden = !on;
    if (!on) { return; }
    ['main', 'empty', 'processes', 'banner', 'secrets'].forEach(function (i) { $(i).hidden = true; });
    ['status', 'meta', 'conn'].forEach(function (i) { $(i).hidden = true; });
    $('wfname').textContent = ''; $('client').textContent = ''; $('controls').hidden = true;
    $('cfgsignin').hidden = !!sess.signedIn;
    $('cfgbody').hidden = !sess.signedIn;
    if (sess.signedIn) { loadConfig(false).catch(function () { }); }
  }
  function put(name, payload) {
    var u = sectionUi(name);
    u.msg.textContent = 'saving…';
    sessionCsrf().then(function (s) {
      if (!s.signedIn) { throw new Error('Your session ended. Sign in again.'); }
      return fetch('/api/config/' + name, { method: 'PUT', headers: { 'Content-Type': 'application/json', 'X-CSRF-Token': csrf }, body: JSON.stringify(payload) });
    }).then(function (r) {
      return r.json().catch(function () { return {}; }).then(function (b) {
        if (!r.ok) {
          u.problems.dataset.err = '1';
          showProblems(u, b.problems && b.problems.length ? b.problems : [b.message || b.error || ('Refused (' + r.status + ')')]);
          u.msg.textContent = b.message || 'not saved';
          return;
        }
        delete u.problems.dataset.err;
        u.msg.textContent = b.message;
        showProblems(u, b.problems);
        return loadConfig(true);
      });
    }).catch(function (err) { u.msg.textContent = err.message; });
  }
  ['rules', 'windows', 'prices'].forEach(function (name) {
    $(name + '-save').addEventListener('click', function () { put(name, { structured: name === 'rules' ? cfg.rules : name === 'windows' ? cfg.windows : cfg.prices }); });
    $(name + '-savetext').addEventListener('click', function () { put(name, { text: sectionUi(name).text.value }); });
    $(name + '-reset').addEventListener('click', function () { var u = sectionUi(name); delete u.problems.dataset.err; u.msg.textContent = ''; cfg.picked = null; cfg.wpicked = null; loadConfig(true); });
  });
  $('rb-agent').addEventListener('change', function () { $('rb-other-wrap').hidden = $('rb-agent').value !== '__other'; });
  $('rb-rule').addEventListener('change', buildFields);
  $('rb-add').addEventListener('click', addRule);
  $('wb-set').addEventListener('click', setWindow);
  $('wb-remove').addEventListener('click', removeWindow);
  $('wb-cancel').addEventListener('click', function () { cfg.wpicked = null; renderWindows(); });
  $('wb-add').addEventListener('click', function () {
    var m = $('wb-new').value.trim();
    if (!m) { return; }
    $('wb-new').value = '';
    pickWindow(m);
  });
  $('pb-set').addEventListener('click', setPrice);
  $('pb-remove').addEventListener('click', removePrice);
  $('pb-cancel').addEventListener('click', function () { cfg.picked = null; renderPrices(); });
  $('pb-add').addEventListener('click', function () {
    var m = $('pb-new').value.trim();
    if (!m) { return; }
    $('pb-new').value = '';
    pickModel(m);
  });
  $('cfgsigninbtn').addEventListener('click', function () { $('si-err').hidden = true; $('signin').showModal(); $('sipw').focus(); });

  $('si-cancel').addEventListener('click', function () { $('signin').close(); });
  $('signinform').addEventListener('submit', function (e) {
    e.preventDefault();
    fetch('/api/login', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ password: $('sipw').value }) })
      .then(function (r) {
        $('sipw').value = '';
        if (r.ok) { $('signin').close(); tick(); return; }
        $('si-err').textContent = r.status === 429 ? 'Too many attempts. Wait a moment.' : r.status === 503 ? 'No admin password is set.' : 'Wrong password.';
        $('si-err').hidden = false;
      });
  });

  /** Runs a redraw and puts the keyboard focus back on the element that had it (the page redraws every second, and a redraw must not take the focus away). */
  function withFocus(draw) {
    var a = document.activeElement, key = a && a.getAttribute ? a.getAttribute('data-fk') : null;
    draw();
    if (!key) { return; }
    var all = document.querySelectorAll('[data-fk]');
    for (var i = 0; i < all.length; i++) {
      if (all[i].getAttribute('data-fk') === key) { if (all[i] !== document.activeElement) { all[i].focus(); } return; }
    }
  }

  function render() { withFocus(renderNow); }

  function renderNow() {
    var v = view;
    if (!v) { return; }
    $('client').textContent = v.client ? v.client + ' /' : '';
    $('wfname').textContent = v.workflow || 'run';
    var pill = $('status');
    pill.hidden = false;
    pill.className = 'pill ' + v.status + (v.status === 'running' ? ' pulse' : '');
    pill.textContent = v.status;
    renderControls(v);
    $('meta').hidden = false;
    $('started').textContent = v.startedAt ? hms(v.startedAt) : '–';
    tickElapsed();

    $('tokens').textContent = num(v.totals.tokens);
    $('tokensfoot').textContent = num(v.totals.calls) + ' model calls';
    $('cost').textContent = money(v.totals.cost);
    $('costfoot').textContent = v.totals.calls ? money(Number(v.totals.cost) / v.totals.calls) + ' per call' : '';
    var running = v.agents.filter(function (a) { return a.status === 'running'; });
    $('running').textContent = running.length;
    $('runningof').textContent = 'of ' + v.agents.length + ' agents';
    $('runningfoot').textContent = running.map(function (a) { return a.name; }).join(' · ');
    var calls = 0, errors = 0, inFlight = 0;
    v.tools.forEach(function (t) { calls += t.calls; errors += t.errors; inFlight += t.inFlight; });
    $('toolcalls').textContent = num(calls);
    $('toolfoot').textContent = inFlight + ' in flight · ' + errors + ' failed';
    $('state').textContent = v.status;
    $('statefoot').textContent = shortId(v.runId || v.traceId).slice(-32);
    renderBanner(v);
    renderRunTabs(v);
    renderContextTile(v);
    renderTaskTile(v);

    $('agentsub').textContent = v.agents.length + ' agents';
    var agents = $('agents');
    agents.textContent = '';
    v.agents.forEach(function (a) {
      var flagged = (v.incidents || []).filter(function (i) { return i.state === 'open' && i.agent === a.name; });
      var tr = el('tr', (a.status === 'running' ? 'run' : '') + (flagged.length ? ' flag ' + (flagged.some(function (i) { return i.severity === 'high'; }) ? 'high' : '') : ''));
      var first = cell(tr, '');
      var ag = el('div', 'ag');
      ag.appendChild(el('span', 'dot ' + a.status));
      ag.appendChild(el('span', 'nm', a.name));
      first.appendChild(ag);
      cell(tr, a.status === 'running' ? (a.currentStep || 'running') : a.status, 'task');
      cell(tr, num(a.tokens), 'r');
      cell(tr, a.calls, 'r');
      cell(tr, money(a.cost), 'r');
      var tools = el('td');
      a.activeTools.forEach(function (t) { tools.appendChild(el('span', 'chip on', t)); });
      tr.appendChild(tools);
      agents.appendChild(tr);
    });
    $('noagents').hidden = v.agents.length > 0;

    ensureGraph(selected);
    renderGraph(v);
    renderIncidents(v.incidents || []);
    renderLadders(v.ladders || []);
    renderSpend(v.spend || []);
    renderFollow(v);
    renderContext(v);
    renderToolsTasks(v);
    renderSecurity(v);
    $('updated').textContent = 'updated ' + hms(new Date().toISOString());
  }

  // Elapsed ticks once a second on the client between polls; a finished run holds still.
  function tickElapsed() {
    if (!view || paused) { return; }
    var live = view.status === 'running' || view.status === 'waiting';
    $('elapsed').textContent = clock(view.elapsedMillis + (live ? Date.now() - viewAt : 0));
  }

  function renderConnection() {
    var chip = $('conn'), main = $('main');
    var p = view && procs.filter(function (x) { return x.name === view.client; })[0];
    if (!fabric || !p || showProcs || showFleet) { chip.hidden = true; main.classList.remove('stale'); return; }
    chip.hidden = false;
    var cls = 'conn', text;
    if (p.state === 'online') { cls += ' live'; text = 'Live'; }
    else if (p.state === 'catching up') { cls += ' catching'; text = 'Catching up · ' + num(p.backlog); }
    else if (p.state === 'finished') { text = 'Process ended'; }
    else if (p.state === 'unsupported') { cls += ' stale'; text = 'Unsupported version'; }
    else { cls += ' stale'; text = 'Stale · last seen ' + (p.lastSeen ? hms(p.lastSeen) : '?'); }
    chip.className = cls;
    chip.textContent = text;
    main.classList.toggle('stale', p.state === 'offline' || p.state === 'unsupported');
  }

  function renderProcesses() {
    var body = $('proctable');
    body.textContent = '';
    $('procsub').textContent = procs.length + (procs.length === 1 ? ' process' : ' processes');
    $('noproc').hidden = procs.length > 0;
    procs.forEach(function (p) {
      var tr = el('tr');
      cell(tr, p.name);
      var st = cell(tr, '');
      st.appendChild(el('span', 'conn ' + (p.state === 'online' ? 'live' : p.state === 'catching up' ? 'catching' : p.state === 'finished' ? '' : 'stale'), p.state));
      cell(tr, (p.host || '–') + (p.pid ? ' · ' + p.pid : ''), 'mono');
      cell(tr, (p.workflows || []).join(', ') || '–');
      cell(tr, p.liveRuns, 'r');
      cell(tr, p.eventsPerSecond, 'r');
      cell(tr, num(p.backlog), 'r');
      cell(tr, num(p.lost), 'r' + (p.lost > 0 ? ' err' : ''));
      cell(tr, p.lastSeen ? hms(p.lastSeen) : '–', 'mono');
      body.appendChild(tr);
    });
  }

  function renderAll() {
    renderProcesses();
    if (view) { render(); }
    renderConnection();
    $('processes').hidden = !showProcs;
    $('main').hidden = showProcs || !!showFleet || !selected;
    $('empty').hidden = showProcs || !!showFleet || !!selected;
    $('banner').hidden = showProcs || !!showFleet || $('banner').hidden;
  }

  function showLogin(on, message) {
    $('login').hidden = !on;
    if (on) {
      ['main', 'empty', 'processes', 'wtabs'].forEach(function (i) { $(i).hidden = true; });
      $('loginerr').hidden = !message;
      $('loginerr').textContent = message || '';
    }
  }

  $('loginform').addEventListener('submit', function (e) {
    e.preventDefault();
    fetch('/api/login', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ password: $('pw').value }) })
      .then(function (r) {
        $('pw').value = '';
        if (r.ok) { showLogin(false); tick(); return; }
        showLogin(true, r.status === 429 ? 'Too many attempts. Wait a moment.' : r.status === 503 ? 'No admin password is set on the server.' : 'Wrong password.');
      })
      .catch(function () { showLogin(true, 'Cannot reach the server.'); });
  });

  function tick(force) {
    fetchJson('/api/session').then(function (s) { sess = s; }, function () { });
    fetchJson('/api/runs').then(function (list) {
      showLogin(false);
      lastRuns = list.runs;
      if (paused) { countChanges(list.runs); if (!force) { return null; } }
      fleetFailing = (list.fleet && list.fleet.toolsFailing) || 0;
      fleetSecOpen = list.runs.reduce(function (n, r) { return n + (r.security || 0); }, 0);
      clientNames = Object.keys(list.runs.reduce(function (m, r) { if (r.client) { m[r.client] = true; } return m; }, {})).sort();
      // the Processes view exists only on a Fabric server; an embedded page answers 404 and never shows it
      return fetchJson('/api/processes').then(function (p) { fabric = true; procs = p.processes || []; }, function (e) { if (e.status === 401) { throw e; } fabric = false; })
        .then(function () {
          renderRuns(list.runs);
          renderProcesses();
          $('mode').textContent = fabric ? 'Fabric' : 'embedded mode';
          brand();
          if (!showFleet) { $('fleettools').hidden = true; $('fleetsec').hidden = true; $('runslist').hidden = true; }
          if (showProcs || showFleet) { ['status', 'meta', 'conn', 'controls'].forEach(function (i) { $(i).hidden = true; }); $('wfname').textContent = ''; $('client').textContent = ''; $('banner').hidden = true; }
          if (showFleet) { $('secrets').hidden = true; $('config').hidden = true; return renderFleet(); }
          if (showConfig) { $('secrets').hidden = true; renderConfig(); return null; }
          $('config').hidden = true;
          if (showSecrets) { renderSecrets(); return null; }
          $('secrets').hidden = true;
          return selected && !showProcs ? fetchJson('/api/runs/' + selected).then(function (v) { view = v; viewAt = Date.now(); render(); renderConnection(); }) : null;
        });
    }).catch(function (e) {
      if (e && e.status === 401) { showLogin(true); return; }
      $('updated').textContent = 'cannot reach the run (it may have ended)';
    });
  }

  tick();
  setInterval(tick, 1000);
  setInterval(tickElapsed, 1000);
}());
