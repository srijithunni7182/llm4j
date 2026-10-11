'use strict';
// Read-only. Every piece of run text goes in with textContent, never as HTML.
(function () {
  var $ = function (id) { return document.getElementById(id); };
  var selected = null, agentFilter = null, view = null, viewAt = 0;

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
      if (!r.ok) { throw new Error(r.status); }
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
    runs.forEach(function (r) {
      var b = el('button', 'wt' + (r.traceId === selected ? ' on' : ''));
      b.type = 'button';
      b.appendChild(el('span', 'dot ' + tabState(r.status)));
      b.appendChild(el('span', '', r.workflow || 'run'));
      b.title = r.status + ' · ' + shortId(r.runId || r.traceId);
      b.addEventListener('click', function () { selected = r.traceId; agentFilter = null; tick(); });
      nav.appendChild(b);
    });
    nav.hidden = runs.length < 2;
  }

  function renderRuns(runs) {
    var ids = runs.map(function (r) { return r.traceId; });
    if (ids.indexOf(selected) < 0) { selected = ids.length ? ids[0] : null; }
    renderTabs(runs);
    $('empty').hidden = runs.length > 0;
    $('main').hidden = runs.length === 0;
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
      t.appendChild(document.createTextNode((e.agent && !agentFilter ? e.agent + ' · ' : '') + e.text));
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
    g.hidden = points.length === 0;
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

  function render() {
    var v = view;
    if (!v) { return; }
    $('client').textContent = v.client ? v.client + ' /' : '';
    $('wfname').textContent = v.workflow || 'run';
    var pill = $('status');
    pill.hidden = false;
    pill.className = 'pill ' + v.status + (v.status === 'running' ? ' pulse' : '');
    pill.textContent = v.status;
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
      var tr = el('tr', a.status === 'running' ? 'run' : '');
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

  function tick() {
    fetchJson('/api/runs').then(function (list) {
      renderRuns(list.runs);
      return selected ? fetchJson('/api/runs/' + selected).then(function (v) { view = v; viewAt = Date.now(); render(); }) : null;
    }).catch(function () {
      $('updated').textContent = 'cannot reach the run (it may have ended)';
    });
  }

  tick();
  setInterval(tick, 1000);
  setInterval(tickElapsed, 1000);
}());
