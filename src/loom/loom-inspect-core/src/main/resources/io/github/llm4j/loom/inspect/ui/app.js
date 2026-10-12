'use strict';
// Every piece of run text goes in with textContent, never as HTML.
(function () {
  var $ = function (id) { return document.getElementById(id); };
  var selected = null, agentFilter = null, view = null, viewAt = 0;
  var fabric = false, procs = [], showProcs = false, showSecrets = false, sess = {};

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

  function renderTabs(runs) {
    var nav = $('wtabs');
    nav.textContent = '';
    if (fabric) {
      var p = el('button', 'wt proc' + (showProcs ? ' on' : ''), 'Processes');
      p.type = 'button';
      p.addEventListener('click', function () { showProcs = true; renderAll(); });
      nav.appendChild(p);
    }
    if (sess.secretsEnabled && !fabric) {
      var sb = el('button', 'wt proc' + (showSecrets ? ' on' : ''), 'Secrets');
      sb.type = 'button';
      sb.addEventListener('click', function () { showSecrets = true; showProcs = false; tick(); });
      nav.appendChild(sb);
    }
    var clients = {};
    runs.forEach(function (r) { clients[r.client] = true; });
    var grouped = fabric || Object.keys(clients).length > 1;   // one tab per process and workflow, never merged
    runs.forEach(function (r) {
      var b = el('button', 'wt' + (!showProcs && !showSecrets && r.traceId === selected ? ' on' : ''));
      b.type = 'button';
      b.appendChild(el('span', 'dot ' + tabState(r.status)));
      b.appendChild(el('span', '', (grouped && r.client ? r.client + ' / ' : '') + (r.workflow || 'run')));
      if (r.incidents > 0) { b.appendChild(el('span', 'sev ' + (r.worstIncident || 'warn') + ' inc', String(r.incidents))); }
      b.title = r.status + ' · ' + shortId(r.runId || r.traceId);
      b.addEventListener('click', function () { selected = r.traceId; agentFilter = null; showProcs = false; showSecrets = false; tick(); });
      nav.appendChild(b);
    });
    nav.hidden = !fabric && runs.length < 2 && !(sess.secretsEnabled && !fabric);
  }

  function renderRuns(runs) {
    var ids = runs.map(function (r) { return r.traceId; });
    if (ids.indexOf(selected) < 0) { selected = ids.length ? ids[0] : null; }
    renderTabs(runs);
    $('empty').hidden = runs.length > 0 || showProcs;
    $('main').hidden = runs.length === 0 || showProcs;
    $('processes').hidden = !showProcs;
    if (!runs.length) { ['status', 'meta'].forEach(function (i) { $(i).hidden = true; }); $('wfname').textContent = ''; $('banner').hidden = true; }
  }

  function renderBanner(v) {
    var banner = $('banner');
    banner.hidden = true;
    banner.className = 'banner';
    banner.textContent = '';
    var lead = null, rest = '';
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

  var KINDS = { PAUSED: 'Paused', LOOPING: 'Looping', SUSPECT_OUTPUT: 'Suspect output', FAILED: 'Failing', BUDGET: 'Budget' };
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
    list.forEach(function (i) {
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
      box.appendChild(row);
    });
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
        act(trace, action, box, function () { tick(); }).then(function () { if (box.textContent) { $('updated').textContent = box.textContent; } });
      }
    };
    dlg.returnValue = 'cancel';
    dlg.showModal();
  }

  function renderControls(v) {
    var box = $('controls'), c = v.controls;
    box.textContent = '';
    if (!c) { box.hidden = true; return; }
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

  function render() {
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

    $('toolsub').textContent = v.tools.length + ' tools';
    var tools = $('tools');
    tools.textContent = '';
    v.tools.forEach(function (t) {
      var row = el('div', 'tool-row');
      row.appendChild(el('span', 'nm', t.name));
      row.appendChild(el('span', 'chip' + (t.inFlight > 0 ? ' on' : ''), t.inFlight + ' in flight'));
      row.appendChild(el('span', 'chip' + (t.errors > 0 ? ' err' : ''), t.calls + ' calls · ' + t.errors + ' failed'));
      row.appendChild(el('span', 'mono faint', t.avgMillis + ' ms avg'));
      tools.appendChild(row);
    });
    $('notools').hidden = v.tools.length > 0;

    ensureGraph(selected);
    renderGraph(v);
    renderIncidents(v.incidents || []);
    renderLadders(v.ladders || []);
    renderSpend(v.spend || []);
    renderFollow(v);
    $('updated').textContent = 'updated ' + hms(new Date().toISOString());
  }

  // Elapsed ticks once a second on the client between polls; a finished run holds still.
  function tickElapsed() {
    if (!view) { return; }
    var live = view.status === 'running' || view.status === 'waiting';
    $('elapsed').textContent = clock(view.elapsedMillis + (live ? Date.now() - viewAt : 0));
  }

  function renderConnection() {
    var chip = $('conn'), main = $('main');
    var p = view && procs.filter(function (x) { return x.name === view.client; })[0];
    if (!fabric || !p || showProcs) { chip.hidden = true; main.classList.remove('stale'); return; }
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
    $('main').hidden = showProcs || !selected;
    $('empty').hidden = showProcs || !!selected;
    $('banner').hidden = showProcs || $('banner').hidden;
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

  function tick() {
    fetchJson('/api/session').then(function (s) { sess = s; }, function () { });
    fetchJson('/api/runs').then(function (list) {
      showLogin(false);
      // the Processes view exists only on a Fabric server; an embedded page answers 404 and never shows it
      return fetchJson('/api/processes').then(function (p) { fabric = true; procs = p.processes || []; }, function (e) { if (e.status === 401) { throw e; } fabric = false; })
        .then(function () {
          renderRuns(list.runs);
          renderProcesses();
          $('mode').textContent = fabric ? 'Fabric' : 'embedded mode';
          if (showProcs) { ['status', 'meta', 'conn'].forEach(function (i) { $(i).hidden = true; }); $('wfname').textContent = ''; $('client').textContent = ''; $('banner').hidden = true; }
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
