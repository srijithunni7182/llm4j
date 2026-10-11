'use strict';
// Read-only. Every piece of run text goes in with textContent, never as HTML.
(function () {
  var $ = function (id) { return document.getElementById(id); };
  var selected = null;

  function fetchJson(url) {
    return fetch(url, { cache: 'no-store' }).then(function (r) {
      if (!r.ok) { throw new Error(r.status); }
      return r.json();
    });
  }

  function money(s) {
    var n = Number(s);
    return '$' + (isFinite(n) ? n.toFixed(n < 1 ? 3 : 2) : '0');
  }

  function clock(ms) {
    var s = Math.max(0, Math.floor(ms / 1000)), h = Math.floor(s / 3600), m = Math.floor((s % 3600) / 60);
    s = s % 60;
    return (h ? h + ':' + String(m).padStart(2, '0') : m) + ':' + String(s).padStart(2, '0');
  }

  function hms(iso) {
    var d = new Date(iso);
    return [d.getHours(), d.getMinutes(), d.getSeconds()].map(function (n) { return String(n).padStart(2, '0'); }).join(':');
  }

  function shortId(id) {
    var s = String(id || '');
    var i = Math.max(s.lastIndexOf('/'), s.lastIndexOf('\\'));
    return i >= 0 ? s.slice(i + 1) : s;
  }

  function el(tag, cls, text) {
    var e = document.createElement(tag);
    if (cls) { e.className = cls; }
    if (text != null) { e.textContent = text; }
    return e;
  }

  function cell(row, text, cls) { row.appendChild(el('td', cls || '', text)); }

  function renderRuns(runs) {
    var sel = $('runs'), keep = selected || (sel.value || null);
    sel.textContent = '';
    runs.forEach(function (r) {
      var o = el('option', '', (r.workflow || 'run') + ' · ' + r.status + ' · ' + shortId(r.runId || r.traceId).slice(-28));
      o.value = r.traceId;
      sel.appendChild(o);
    });
    if (runs.length) {
      var ids = runs.map(function (r) { return r.traceId; });
      selected = ids.indexOf(keep) >= 0 ? keep : ids[0];
      sel.value = selected;
    } else {
      selected = null;
    }
    $('empty').hidden = runs.length > 0;
    $('main').hidden = runs.length === 0;
    sel.hidden = runs.length === 0;
  }

  function renderRun(v) {
    $('title').textContent = (v.workflow || 'run') + (v.runId ? '  ' + shortId(v.runId) : '');
    var pill = $('status');
    pill.hidden = false;
    pill.className = 'pill ' + v.status;
    pill.textContent = v.status;
    $('tokens').textContent = Number(v.totals.tokens).toLocaleString();
    $('cost').textContent = money(v.totals.cost);
    $('calls').textContent = Number(v.totals.calls).toLocaleString();
    var running = v.agents.filter(function (a) { return a.status === 'running'; }).length;
    $('running').textContent = running;
    $('elapsed').textContent = clock(v.elapsedMillis);

    var banner = $('banner');
    banner.hidden = true;
    banner.className = 'banner';
    if (v.status === 'sleeping') {
      var wake = (v.sleeping && v.sleeping.wakeAt) || (v.suspended && v.suspended.resumeAt);
      banner.textContent = 'Sleeping' + (wake ? ' until ' + new Date(wake).toLocaleTimeString() : '') + '. Nothing is running; the run is paused and holds no thread.';
      banner.hidden = false;
    } else if (v.status === 'paused') {
      banner.textContent = 'Paused' + (v.suspended ? ' (' + v.suspended.reason + (v.suspended.resumeAt ? ', resumes ' + new Date(v.suspended.resumeAt).toLocaleTimeString() : '') + ')' : '') + '. This is not a failure.';
      banner.hidden = false;
    } else if (v.status === 'waiting') {
      banner.className = 'banner waiting';
      banner.textContent = 'Waiting for a person to answer.';
      banner.hidden = false;
    } else if (v.gaps > 0) {
      banner.textContent = v.gaps + ' event(s) are missing from this view.';
      banner.hidden = false;
    }

    var agents = $('agents');
    agents.textContent = '';
    v.agents.forEach(function (a) {
      var tr = el('tr', a.status === 'running' ? 'on' : '');
      var first = el('td');
      first.appendChild(el('span', 'dot ' + a.status));
      first.appendChild(document.createTextNode(a.name));
      tr.appendChild(first);
      cell(tr, a.status + (a.currentStep ? ' · ' + a.currentStep : ''));
      cell(tr, Number(a.tokens).toLocaleString(), 'r');
      cell(tr, a.calls, 'r');
      cell(tr, money(a.cost), 'r');
      var tools = el('td');
      a.activeTools.forEach(function (t) { tools.appendChild(el('span', 'chip', t)); });
      tr.appendChild(tools);
      agents.appendChild(tr);
    });
    $('noagents').hidden = v.agents.length > 0;

    var tools = $('tools');
    tools.textContent = '';
    v.tools.forEach(function (t) {
      var tr = el('tr', t.inFlight > 0 ? 'on' : '');
      cell(tr, t.name);
      cell(tr, t.calls, 'r');
      cell(tr, t.inFlight, 'r');
      cell(tr, t.errors, 'r' + (t.errors > 0 ? ' err' : ''));
      cell(tr, t.avgMillis + ' ms', 'r');
      tools.appendChild(tr);
    });
    $('notools').hidden = v.tools.length > 0;

    var recent = $('recent');
    recent.textContent = '';
    v.recent.slice(-30).reverse().forEach(function (e) {
      var li = el('li');
      li.appendChild(el('span', 'faint', hms(e.at)));
      li.appendChild(el('span', '', e.event.replace('loom.', '') + (e.agent ? ' · ' + e.agent : '')));
      li.appendChild(el('span', 'text' + (e.failed ? ' err' : ''), e.text));
      recent.appendChild(li);
    });
    $('updated').textContent = 'updated ' + hms(new Date().toISOString());
  }

  function tick() {
    fetchJson('/api/runs').then(function (list) {
      renderRuns(list.runs);
      return selected ? fetchJson('/api/runs/' + selected).then(renderRun) : null;
    }).catch(function () {
      $('updated').textContent = 'cannot reach the run (it may have ended)';
    });
  }

  $('runs').addEventListener('change', function (e) { selected = e.target.value; tick(); });
  tick();
  setInterval(tick, 1000);
}());
