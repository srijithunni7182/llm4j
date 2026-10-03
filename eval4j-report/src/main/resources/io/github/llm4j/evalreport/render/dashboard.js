/* eval4j report. Reads the embedded model and draws every view with DOM calls: model text is never
   parsed as HTML, so a hostile test name or answer cannot inject markup. No network access. */
(function () {
  'use strict';
  var M = JSON.parse(document.getElementById('eval4j-data').textContent);
  var NS = 'http://www.w3.org/2000/svg';
  var $ = function (id) { return document.getElementById(id); };

  /* ---------- helpers ---------- */
  function h(tag, attrs) {
    var el = document.createElement(tag), i, c;
    if (attrs) for (var k in attrs) {
      if (attrs[k] == null || attrs[k] === false) continue;
      if (k === 'class') el.className = attrs[k];
      else if (k === 'text') el.textContent = attrs[k];
      else if (k.slice(0, 2) === 'on') el.addEventListener(k.slice(2), attrs[k]);
      else el.setAttribute(k, attrs[k] === true ? '' : attrs[k]);
    }
    for (i = 2; i < arguments.length; i++) { c = arguments[i]; add(el, c); }
    return el;
  }
  function add(el, c) {
    if (c == null || c === false) return;
    if (Array.isArray(c)) { c.forEach(function (x) { add(el, x); }); return; }
    el.appendChild(typeof c === 'object' ? c : document.createTextNode(String(c)));
  }
  function s(tag, attrs) {
    var el = document.createElementNS(NS, tag), i;
    if (attrs) for (var k in attrs) if (attrs[k] != null) el.setAttribute(k, attrs[k]);
    for (i = 2; i < arguments.length; i++) add(el, arguments[i]);
    return el;
  }
  function clear(el) { while (el.firstChild) el.removeChild(el.firstChild); return el; }
  function pct(x, d) { return x == null ? '–' : x.toFixed(d == null ? 1 : d) + '%'; }
  function sc(x) { return x == null ? '–' : x.toFixed(2); }
  function usd(x) { return x == null ? '–' : '$' + (x < 1 ? x.toFixed(4) : x.toFixed(2)); }
  function ms(x) { return x == null ? '–' : x >= 1000 ? (x / 1000).toFixed(1) + ' s' : Math.round(x) + ' ms'; }
  function counted(r) { return r.passed + r.failed; }
  function when(t) { try { return t ? new Date(t).toLocaleString() : ''; } catch (e) { return t || ''; } }
  function dimById(id) { return M.dimensions.filter(function (d) { return d.id === id; })[0]; }
  function famById(id) { return M.families.filter(function (d) { return d.id === id; })[0]; }
  var STATUS = {
    MEETS_GOAL: ['Meets goal', 'good'], BELOW_GOAL: ['Below goal', 'warn'],
    WELL_BELOW_GOAL: ['Well below goal', 'crit'], NO_RESULTS: ['No results', '']
  };
  var COVER = {
    COVERED: 'Covered', PARTLY_EVALUATED: 'Partly evaluated', NOT_EVALUATED: 'Declared, not evaluated',
    DECLARED_NO_SCENARIOS: 'Goal set, no scenarios', UNDECLARED: 'Evaluated, not declared', NO_DATASET: 'No dataset in this run'
  };
  var PRIO = [['CRITICAL', 'Critical', 3], ['IMPORTANT', 'Important', 2], ['NICE_TO_HAVE', 'Nice to have', 1], ['NONE', 'Not a priority', 0]];
  function pill(status) { var t = STATUS[status] || [status, '']; return h('span', { class: 'pill ' + t[1], text: t[0] }); }

  /* ---------- priorities (a view setting; never changes a result) ---------- */
  var prio = {};
  M.dimensions.forEach(function (d) { prio[d.id] = d.priority; });
  var store = 'eval4j.priorities.' + (M.meta.project || '');
  try { var saved = JSON.parse(localStorage.getItem(store) || '{}'); for (var k in saved) if (k in prio) prio[k] = saved[k]; } catch (e) { /* storage unavailable */ }
  function weight(p) { return PRIO.filter(function (x) { return x[0] === p; })[0][2]; }
  function weighted() {
    var n = 0, g = 0, d = 0, c = 0;
    M.dimensions.forEach(function (x) {
      var w = weight(prio[x.id]);
      if (x.rollup.rate == null || w <= 0) return;
      n += w * x.rollup.rate; g += w * x.goal; d += w; c++;
    });
    return d === 0 ? { rate: null, goal: null, n: 0 } : { rate: n / d, goal: g / d, n: c };
  }

  /* ---------- charts ---------- */
  function donut(r, goal, size) {
    size = size || 120;
    var R = size / 2 - 10, C = 2 * Math.PI * R, tot = counted(r);
    var svg = s('svg', { class: 'donut', width: size, height: size, viewBox: '0 0 ' + size + ' ' + size, role: 'img',
      'aria-label': tot ? pct(r.rate) + ' passed' : 'No results' });
    svg.appendChild(s('circle', { cx: size / 2, cy: size / 2, r: R, fill: 'none', stroke: 'var(--surface-2)', 'stroke-width': 12 }));
    if (tot) {
      var pf = r.passed / tot;
      var a = s('circle', { cx: size / 2, cy: size / 2, r: R, fill: 'none', stroke: r.failed ? 'var(--fail)' : 'var(--pass)', 'stroke-width': 12,
        transform: 'rotate(-90 ' + size / 2 + ' ' + size / 2 + ')' });
      svg.appendChild(a);
      if (r.failed && r.passed) {
        svg.appendChild(s('circle', { cx: size / 2, cy: size / 2, r: R, fill: 'none', stroke: 'var(--pass)', 'stroke-width': 12,
          'stroke-dasharray': (C * pf) + ' ' + C, transform: 'rotate(-90 ' + size / 2 + ' ' + size / 2 + ')' }));
      } else if (!r.failed) {
        a.setAttribute('stroke', 'var(--pass)');
      }
    }
    if (goal != null) {
      var ang = (goal / 100) * 2 * Math.PI - Math.PI / 2, x1 = size / 2 + (R - 9) * Math.cos(ang), y1 = size / 2 + (R - 9) * Math.sin(ang),
        x2 = size / 2 + (R + 9) * Math.cos(ang), y2 = size / 2 + (R + 9) * Math.sin(ang);
      svg.appendChild(s('line', { x1: x1, y1: y1, x2: x2, y2: y2, stroke: 'var(--ink)', 'stroke-width': 2.5, 'stroke-linecap': 'round' }));
    }
    svg.appendChild(s('text', { x: size / 2, y: size / 2 + 6, 'text-anchor': 'middle', 'font-size': tot ? 20 : 12, 'font-weight': 700 }, tot ? pct(r.rate, 0) : 'No results'));
    return svg;
  }
  function meter(rate, goal) {
    return h('div', { class: 'meter', role: 'img', 'aria-label': 'Reached ' + pct(rate) + ', goal ' + pct(goal) },
      h('div', { class: 'fill', style: 'width:' + (rate || 0) + '%' }), h('div', { class: 'tick', style: 'left:' + (goal || 0) + '%' }));
  }
  function histogram(r) {
    var max = 1; r.histogram.forEach(function (b) { max = Math.max(max, b[0] + b[1]); });
    var el = h('div', { class: 'hist', role: 'img', 'aria-label': 'Score distribution' });
    r.histogram.forEach(function (b, i) {
      el.appendChild(h('div', { title: (i / 10).toFixed(1) + '–' + ((i + 1) / 10).toFixed(1) + ': ' + b[0] + ' passed, ' + b[1] + ' failed' },
        h('div', { class: 'stack' }, h('i', { style: 'height:' + (b[1] / max * 100) + '%;background:var(--fail)' }), h('i', { style: 'height:' + (b[0] / max * 100) + '%;background:var(--pass)' })),
        (i / 10).toFixed(1)));
    });
    return el;
  }

  /* ---------- routing ---------- */
  var view = $('view'), side = $('side'), crumbs = $('crumbs');
  function route() {
    var parts = (location.hash || '#overview').slice(1).split('/').map(decodeURIComponent);
    closeDrawer();
    clear(view);
    var title = 'Overview', fn = viewOverview;
    switch (parts[0]) {
      case 'family': fn = function () { viewFamily(parts[1]); }; title = (famById(parts[1]) || {}).name || 'Family'; break;
      case 'dim': fn = function () { viewDim(parts[1], parts[2]); }; title = (dimById(parts[1]) || {}).name || 'Dimension'; break;
      case 'compare': fn = viewCompare; title = 'Compare runs'; break;
      case 'coverage': fn = viewCoverage; title = 'Dataset coverage'; break;
      case 'cost': fn = viewCost; title = 'Cost and evidence'; break;
      case 'models': fn = viewModels; title = 'Judges and models'; break;
      case 'traces': fn = viewTraces; title = 'Traces'; break;
      case 'optimizer': fn = viewOptim; title = 'Prompt optimizer'; break;
      case 'notes': fn = viewNotes; title = 'Data notes'; break;
      default: break;
    }
    try { fn(); } catch (e) { view.appendChild(h('div', { class: 'note', text: 'This view could not be drawn: ' + e.message })); }
    clear(crumbs).appendChild(h('span', null, (M.meta.project || 'eval4j') + ' / ', h('b', { text: title })));
    drawNav(parts[0] || 'overview', parts[1]);
    side.classList.remove('open');
    window.scrollTo(0, 0);
  }
  function go(hash) { location.hash = hash; }

  function drawNav(cur, arg) {
    clear(side);
    side.appendChild(h('a', { class: 'lock', href: '#overview', 'aria-label': 'eval4j, part of llm4j' }, logo(), h('span', { class: 'wm' }, 'eval', h('span', { text: '4j' }))));
    function item(label, hash, key, count) {
      var a = h('a', { class: 'nav', href: hash }, h('span', { text: label }), count != null ? h('small', { text: count }) : null);
      if (key === cur + (arg && cur === 'family' ? '/' + arg : '')) a.setAttribute('aria-current', 'page');
      side.appendChild(a);
    }
    item('Overview', '#overview', 'overview');
    side.appendChild(h('div', { class: 'navh', text: 'Test families' }));
    M.families.forEach(function (f) { item(f.name, '#family/' + encodeURIComponent(f.id), 'family/' + f.id, counted(f.rollup)); });
    side.appendChild(h('div', { class: 'navh', text: 'Insight' }));
    item('Compare runs', '#compare', 'compare', M.compare ? M.compare.worse + M.compare.better : null);
    item('Dataset coverage', '#coverage', 'coverage');
    item('Cost and evidence', '#cost', 'cost');
    item('Judges and models', '#models', 'models');
    if (M.traces.length) item('Traces', '#traces', 'traces', M.traces.length);
    if (M.optimizations.length) item('Prompt optimizer', '#optimizer', 'optimizer', M.optimizations.length);
    item('Data notes', '#notes', 'notes', M.notes.length || null);
  }
  function logo() {
    var svg = s('svg', { viewBox: '0 0 632 504', 'aria-hidden': 'true' });
    svg.appendChild(s('defs', null, s('linearGradient', { id: 'ig', gradientUnits: 'userSpaceOnUse', x1: 216, y1: 196, x2: 808, y2: 660 },
      s('stop', { offset: 0, 'stop-color': 'var(--b1)' }), s('stop', { offset: .5, 'stop-color': 'var(--b2)' }), s('stop', { offset: 1, 'stop-color': 'var(--b3)' }))));
    var g = s('g', { fill: 'none', stroke: 'url(#ig)', 'stroke-width': 30, 'stroke-linecap': 'round', 'stroke-linejoin': 'round', transform: 'translate(-196 -176)' });
    ['512,234 489.5,247 489.5,273 512,286 534.5,273 534.5,247', '300,434 260.2,457 260.2,503 300,526 339.8,503 339.8,457', '724,434 684.2,457 684.2,503 724,526 763.8,503 763.8,457'].forEach(function (p) { g.appendChild(s('polygon', { points: p })); });
    [[512, 286, 512, 610], [330, 340, 694, 340], [340, 340, 300, 434], [684, 340, 724, 434], [512, 610, 430, 660], [512, 610, 594, 660], [400, 660, 624, 660], [512, 234, 512, 196], [260.2, 480, 216, 480], [763.8, 480, 808, 480]]
      .forEach(function (l) { g.appendChild(s('line', { x1: l[0], y1: l[1], x2: l[2], y2: l[3] })); });
    svg.appendChild(g);
    return svg;
  }

  /* ---------- overview ---------- */
  function viewOverview() {
    var o = M.overall, w = weighted(), dur = M.meta.summary || {};
    var big = h('div', { class: 'big num', text: pct(w.rate) });
    var gaps = h('div', { class: 'gaps' });
    var wtxt = h('p');
    function refresh() {
      var cw = weighted();
      big.textContent = pct(cw.rate);
      clear(wtxt);
      wtxt.textContent = cw.rate == null
        ? 'No dimension has both a result and a priority above zero, so there is no weighted pass rate. Set a priority to see one.'
        : 'Priority-weighted pass rate over ' + cw.n + ' dimension' + (cw.n === 1 ? '' : 's') + ' with results. Goal ' + pct(cw.goal) + '. Overall unweighted: ' + pct(o.rate) + ' of ' + counted(o) + ' evaluations passed.';
      mt.firstChild.style.width = (cw.rate || 0) + '%'; mt.lastChild.style.left = (cw.goal || 0) + '%';
    }
    var mt = meter(w.rate, w.goal);
    M.dimensions.forEach(function (d) {
      var sel = h('select', { 'aria-label': 'Priority of ' + d.name, onchange: function () {
        prio[d.id] = sel.value;
        try { localStorage.setItem(store, JSON.stringify(prio)); } catch (e) { /* ignore */ }
        refresh();
      } });
      PRIO.forEach(function (p) { sel.appendChild(h('option', { value: p[0], selected: prio[d.id] === p[0], text: p[1] })); });
      var rate = d.rollup.rate;
      gaps.appendChild(h('div', { class: 'gaprow' },
        h('button', { class: 'gn', type: 'button', onclick: function () { go('#dim/' + encodeURIComponent(d.id)); }, text: d.name }),
        h('div', { class: 'bar' }, h('i', { style: 'width:' + (rate || 0) + '%' }), h('b', { style: 'left:' + d.goal + '%' })),
        h('span', { class: 'num', text: rate == null ? 'No results' : (d.gap >= 0 ? '+' : '') + d.gap.toFixed(1) + ' pts' }),
        sel));
    });
    view.appendChild(h('div', { class: 'pagehead' }, h('h1', { text: M.meta.project || 'Evaluation report' }),
      h('p', { class: 'sub' }, 'Run ' + (M.meta.runNumber != null ? '#' + M.meta.runNumber + ' ' : '') + M.meta.runId + (M.meta.branch ? ' · ' + M.meta.branch : '') + (M.meta.commit ? ' · ' + M.meta.commit : '') + ' · ' + when(M.meta.startedAt))));
    view.appendChild(h('div', { class: 'hero' },
      h('div', { class: 'card verdict' },
        h('div', { class: 'eyebrow brandy', text: 'Run summary · ' + (M.evidence.profile || 'BUILD') + ' profile' }),
        h('div', { class: 'vline' }, big, wtxt),
        h('p', { class: 'hint', text: 'This report informs the release decision. It does not make it.' }),
        h('div', null, mt, h('div', { class: 'meter-lab' }, h('span', { text: '0%' }), h('span', { text: 'goal' }), h('span', { text: '100%' }))),
        h('div', null, h('div', { class: 'eyebrow', text: 'Gap to goal · set what matters to you' }),
          h('p', { class: 'hint', text: 'Priorities re-weight this summary. They never change a result or a CI gate.' }), gaps)),
      h('div', { class: 'card' }, h('div', { class: 'eyebrow', text: 'Evidence in this run' }), evidenceBars())));
    refresh();
    view.appendChild(h('div', { class: 'sec' }, h('header', null, h('h2', { text: 'What was tested' }), h('p', { text: 'The test families in this run. Select one for its own view.' })),
      h('div', { class: 'grid' }, M.families.map(function (f) {
        return h('button', { class: 'card tile', type: 'button', onclick: function () { go('#family/' + encodeURIComponent(f.id)); } },
          h('h3', { text: f.name }), h('div', { class: 'num', text: pct(f.rollup.rate) }),
          h('div', { class: 'meta', text: f.rollup.passed + ' passed · ' + f.rollup.failed + ' failed' }));
      }))));
    view.appendChild(h('div', { class: 'sec' }, h('header', null, h('h2', { text: 'Quality dimensions' }),
      h('p', { text: 'Each ring is the share of evaluations that passed. The black tick is the goal. Select one for its metrics and tests.' })),
      h('div', { class: 'legend', style: 'margin-bottom:12px' }, h('span', null, h('i', { class: 'i-pass' }), 'Passed'), h('span', null, h('i', { class: 'i-fail' }), 'Failed')),
      dimGrid(M.dimensions)));
    if (M.trend.length > 1) view.appendChild(h('div', { class: 'sec' }, h('header', null, h('h2', { text: 'Pass rate over recent runs' }), h('p', { text: 'Same branch. A run with no results leaves a gap.' })),
      h('div', { class: 'card' }, spark(M.trend))));
  }
  function evidenceBars() {
    var e = M.evidence, tot = e.fresh + e.reused + e.carried || 1;
    var box = h('div', { style: 'display:grid;gap:8px;margin-top:10px' });
    [['Evaluated now', e.fresh, 'var(--pass)'], ['Reused from cache', e.reused, 'var(--good)'], ['Carried from earlier runs', e.carried, 'var(--warn)']].forEach(function (r) {
      box.appendChild(h('div', null, h('div', { style: 'display:flex;justify-content:space-between' }, h('span', { text: r[0] }), h('b', { class: 'num', text: r[1] })),
        h('div', { class: 'bar' }, h('i', { style: 'width:' + r[1] / tot * 100 + '%;background:' + r[2] }))));
    });
    box.appendChild(h('p', { class: 'hint', text: e.carried ? 'Carried results were not re-evaluated in this run. They are labelled in every list.' : 'Every result was evaluated in this run or reused unchanged from the judge cache.' }));
    return box;
  }
  function dimGrid(list) {
    return h('div', { class: 'grid' }, list.map(function (d) {
      var empty = d.rollup.rate == null;
      return h('button', { class: 'card tile' + (empty ? ' empty' : ''), type: 'button', onclick: function () { go('#dim/' + encodeURIComponent(d.id)); } },
        h('div', { style: 'display:flex;justify-content:space-between;gap:8px' }, h('h3', { text: d.name }), pill(d.status)),
        donut(d.rollup, d.goal),
        h('div', { class: 'meta num', text: empty ? (COVER[d.coverage.state] || '') : d.rollup.passed + ' passed · ' + d.rollup.failed + ' failed · goal ' + pct(d.goal, 0) }));
    }));
  }
  function spark(trend) {
    var W = 640, H = 90, pts = trend.map(function (t, i) { return { i: i, r: t.rate, t: t }; });
    var svg = s('svg', { class: 'sparkline', viewBox: '0 0 ' + W + ' ' + H, width: '100%', role: 'img', 'aria-label': 'Pass rate trend' });
    var n = Math.max(1, trend.length - 1), d = '';
    pts.forEach(function (p) {
      if (p.r == null) return;
      var x = 10 + p.i / n * (W - 20), y = 8 + (1 - p.r / 100) * (H - 16);
      d += (d && pts[p.i - 1] && pts[p.i - 1].r != null ? 'L' : 'M') + x + ' ' + y;
      svg.appendChild(s('circle', { cx: x, cy: y, r: 3.5, fill: 'var(--pass)' }, s('title', null, (p.t.commit || p.t.runId) + ': ' + pct(p.r))));
    });
    svg.insertBefore(s('path', { d: d, fill: 'none', stroke: 'var(--pass)', 'stroke-width': 2 }), svg.firstChild);
    return svg;
  }

  /* ---------- family and dimension ---------- */
  function viewFamily(id) {
    var f = famById(id);
    if (!f) return viewNotFound();
    view.appendChild(h('div', { class: 'card dhead' }, donut(f.rollup, null, 130), h('div', null, h('h1', { text: f.name }),
      h('p', { class: 'sub', text: counted(f.rollup) + ' evaluations · ' + f.rollup.passed + ' passed · ' + f.rollup.failed + ' failed' + (f.rollup.notEvaluated ? ' · ' + f.rollup.notEvaluated + ' not evaluated' : '') }))));
    view.appendChild(h('div', { class: 'sec' }, h('header', null, h('h2', { text: 'Areas' })), h('div', { class: 'grid' }, f.facets.map(function (x) {
      return h('div', { class: 'card tile' }, h('h3', { text: x.name }), donut(x.rollup, null, 100), h('div', { class: 'meta num', text: x.rollup.passed + ' passed · ' + x.rollup.failed + ' failed' }));
    }))));
    view.appendChild(h('div', { class: 'sec' }, h('header', null, h('h2', { text: 'Quality dimensions in this view' })),
      dimGrid(M.dimensions.filter(function (d) { return f.dimensions.indexOf(d.id) >= 0; }))));
  }
  function viewDim(id, metric) {
    var d = dimById(id);
    if (!d) return viewNotFound();
    var rollup = d.rollup, sel = metric ? d.metrics.filter(function (m) { return m.id === metric; })[0] : null;
    if (sel) rollup = sel.rollup;
    view.appendChild(h('button', { class: 'back', type: 'button', onclick: function () { go('#overview'); }, text: '‹ All dimensions' }));
    view.appendChild(h('div', { class: 'card dhead' }, donut(d.rollup, d.goal, 140),
      h('div', null, h('div', { style: 'display:flex;gap:10px;align-items:center;flex-wrap:wrap' }, h('h1', { text: d.name }), pill(d.status)),
        h('p', { class: 'sub', text: d.blurb }),
        h('p', null, d.rollup.rate == null ? 'No evaluation in this dimension has a result.' : 'Reached ' + pct(d.rollup.rate) + ' against a goal of ' + pct(d.goal) + ' (' + (d.gap >= 0 ? '+' : '') + d.gap.toFixed(1) + ' points). ' + d.rollup.passed + ' passed, ' + d.rollup.failed + ' failed.'),
        h('p', { class: 'hint', text: COVER[d.coverage.state] + (d.coverage.declared ? ' · ' + d.coverage.evaluated + ' of ' + d.coverage.declared + ' declared scenarios evaluated' : '') }))));
    if (d.rollup.rate == null) {
      view.appendChild(h('div', { class: 'sec' }, h('div', { class: 'card' }, h('h3', { text: 'Why is there no result?' }), emptyCauses(d))));
      return;
    }
    view.appendChild(h('div', { class: 'sec' }, h('header', null, h('h2', { text: 'Metrics' }), h('p', { text: 'Select one to narrow the histogram and the test list.' })),
      h('div', { class: 'mcards' }, d.metrics.map(function (m) {
        return h('button', { class: 'card tile', type: 'button', 'aria-pressed': sel && sel.id === m.id ? 'true' : 'false',
          style: sel && sel.id === m.id ? 'border-color:var(--pass)' : null,
          onclick: function () { go('#dim/' + encodeURIComponent(d.id) + (sel && sel.id === m.id ? '' : '/' + encodeURIComponent(m.id))); } },
          h('h3', { text: m.name }), h('div', { class: 'num', text: pct(m.rollup.rate) }),
          h('div', { class: 'meta', text: m.kind.toLowerCase() + (m.threshold != null ? ' · pass at ' + sc(m.threshold) : '') + (m.budget != null ? ' · budget ' + m.budget + ' ' + (m.unit || '') : '') + (m.judgeId ? ' · ' + m.judgeId : '') }),
          meter(m.rollup.rate, d.goal));
      }))));
    var rows = M.cases.filter(function (c) {
      return c.evaluations.some(function (e) { return c.dims[e.key] === d.id && (!sel || e.metric === sel.id); });
    });
    var filter = 'all', q = '', tbody = h('tbody'), count = h('p', { class: 'sub' });
    function draw() {
      clear(tbody);
      var shown = rows.filter(function (c) {
        var mine = c.evaluations.filter(function (e) { return c.dims[e.key] === d.id && (!sel || e.metric === sel.id); });
        var failed = mine.some(function (e) { return e.status === 'EVALUATED' && e.passed === false; });
        if (filter === 'failed' && !failed) return false;
        if (filter === 'passed' && failed) return false;
        return !q || (c.name + ' ' + (c.input || '')).toLowerCase().indexOf(q) >= 0;
      });
      count.textContent = shown.length + ' of ' + rows.length;
      shown.forEach(function (c) {
        var mine = c.evaluations.filter(function (e) { return c.dims[e.key] === d.id && (!sel || e.metric === sel.id); });
        var failed = mine.some(function (e) { return e.status === 'EVALUATED' && e.passed === false; });
        var best = mine.filter(function (e) { return e.score != null; }).map(function (e) { return e.score; });
        tbody.appendChild(h('tr', { class: 'row', tabindex: 0, onclick: function () { openCase(c); }, onkeydown: function (ev) { if (ev.key === 'Enter') openCase(c); } },
          h('td', null, h('b', { text: c.name })), h('td', null, h('span', { class: 'pill ' + (failed ? 'crit' : 'good'), text: failed ? 'Failed' : 'Passed' })),
          h('td', { class: 'num', text: best.length ? sc(Math.min.apply(null, best)) : '–' }),
          h('td', { text: mine.map(function (e) { return e.metric; }).filter(function (v, i, a) { return a.indexOf(v) === i; }).join(', ') })));
      });
    }
    var seg = h('div', { class: 'seg', role: 'group', 'aria-label': 'Filter tests' });
    [['all', 'All'], ['failed', 'Failed'], ['passed', 'Passed']].forEach(function (x) {
      var b = h('button', { type: 'button', 'aria-pressed': x[0] === 'all' ? 'true' : 'false', text: x[1], onclick: function () {
        filter = x[0]; Array.prototype.forEach.call(seg.children, function (c) { c.setAttribute('aria-pressed', c === b ? 'true' : 'false'); }); draw(); } });
      seg.appendChild(b);
    });
    view.appendChild(h('div', { class: 'sec cols' },
      h('div', null, h('header', { style: 'margin-bottom:14px' }, h('h2', { text: 'Where scores landed' })), h('div', { class: 'card' }, histogram(rollup))),
      h('div', null, h('header', { style: 'margin-bottom:14px;display:flex;gap:12px;align-items:baseline' }, h('h2', { text: 'Tests' }), count),
        h('div', { class: 'tools' }, seg, h('input', { type: 'search', placeholder: 'Search tests', 'aria-label': 'Search tests', oninput: function (ev) { q = ev.target.value.toLowerCase(); draw(); } })),
        h('div', { class: 'card tbl' }, h('table', null, h('thead', null, h('tr', null, h('th', { text: 'Test' }), h('th', { text: 'Result' }), h('th', { text: 'Lowest score' }), h('th', { text: 'Metrics' }))), tbody)))));
    draw();
  }
  function emptyCauses(d) {
    var c = d.coverage.state, ul = h('ul');
    if (c === 'NOT_EVALUATED') {
      ul.appendChild(h('li', { text: 'The dataset declares ' + d.coverage.declared + ' scenario(s) for this dimension, but none was evaluated in this run.' }));
      ul.appendChild(h('li', { text: 'Under the BUILD profile only changed cases are judged. Run the FULL profile (-Deval4j.profile=FULL) to evaluate everything.' }));
      ul.appendChild(h('li', { text: 'Check that a metric is classified into this dimension: EvalChecks.named(...).dimension("' + d.id + '") or a judge metric mapping.' }));
    } else if (c === 'DECLARED_NO_SCENARIOS') {
      ul.appendChild(h('li', { text: 'A goal is configured for this dimension, but no scenario in the dataset lists it. Add a "dimensions" entry to scenarios.' }));
    } else {
      ul.appendChild(h('li', { text: 'No evaluation was classified into this dimension.' }));
    }
    d.coverage.missingCases.forEach(function (n) { ul.appendChild(h('li', { text: 'Not evaluated: ' + n })); });
    return ul;
  }
  function viewNotFound() { view.appendChild(h('div', { class: 'card', text: 'Nothing here. It may not exist in this run.' })); }

  /* ---------- case drawer ---------- */
  var drawer = $('drawer'), scrim = $('scrim'), lastFocus = null;
  function openCase(c) {
    lastFocus = document.activeElement;
    clear(drawer);
    drawer.appendChild(h('button', { class: 'back', type: 'button', onclick: closeDrawer, text: '✕ Close' }));
    drawer.appendChild(h('h2', { text: c.name }));
    drawer.appendChild(h('p', { class: 'sub mono', text: c.caseId }));
    function field(label, text) {
      if (!text) return null;
      return h('div', { class: 'field' }, h('div', { class: 'eyebrow', text: label }), h('div', { class: 'text', text: text }));
    }
    var first = c.evaluations.filter(function (e) { return e.actualOutput || e.expectedOutput || e.retrievalContext; })[0] || {};
    add(drawer, field('Input', c.input));
    add(drawer, field('Expected', first.expectedOutput));
    add(drawer, field('Actual output', first.actualOutput));
    if (first.retrievalContext) drawer.appendChild(h('div', { class: 'field' }, h('div', { class: 'eyebrow', text: 'Retrieved context' }), first.retrievalContext.map(function (t) { return h('div', { class: 'text', style: 'margin-bottom:6px', text: t }); })));
    drawer.appendChild(h('div', { class: 'field' }, h('div', { class: 'eyebrow', text: 'Evaluations' }), c.evaluations.map(function (e) {
      var ok = e.status === 'EVALUATED' ? (e.passed ? ['Passed', 'good'] : ['Failed', 'crit']) : [e.status.replace('_', ' ').toLowerCase(), 'warn'];
      return h('div', { class: 'evrow' },
        h('header', null, h('span', { text: e.metric }), h('span', { class: 'pill ' + ok[1], text: ok[0] })),
        h('div', { class: 'num', text: (e.score != null ? 'score ' + sc(e.score) : '') + (e.threshold != null ? ' (pass at ' + sc(e.threshold) + ')' : '') + (e.display ? ' · ' + e.display : '') }),
        e.reason ? h('div', { text: e.reason }) : null,
        h('div', { class: 'hint', text: [e.kind.toLowerCase(), e.source ? e.source.toLowerCase() : 'fresh', e.judgeId, e.samples ? e.samples.length + ' samples: ' + e.samples.map(sc).join(', ') : null, e.durationMs != null ? ms(e.durationMs) : null, e.costUsd != null ? usd(e.costUsd) : null].filter(Boolean).join(' · ') }));
    })));
    var tr = M.traces.filter(function (t) { return t.caseId === c.caseId; })[0];
    if (tr) drawer.appendChild(h('div', { class: 'field' }, h('div', { class: 'eyebrow', text: 'Trace' }), traceView(tr)));
    drawer.removeAttribute('hidden'); scrim.removeAttribute('hidden');
    drawer.setAttribute('aria-hidden', 'false'); drawer.setAttribute('tabindex', '-1'); drawer.focus();
  }
  function closeDrawer() {
    drawer.setAttribute('hidden', ''); scrim.setAttribute('hidden', ''); drawer.setAttribute('aria-hidden', 'true');
    if (lastFocus && lastFocus.focus) { try { lastFocus.focus(); } catch (e) { /* gone */ } lastFocus = null; }
  }
  scrim.addEventListener('click', closeDrawer);
  document.addEventListener('keydown', function (e) { if (e.key === 'Escape') closeDrawer(); });

  /* ---------- compare ---------- */
  function viewCompare() {
    var c = M.compare;
    view.appendChild(h('div', { class: 'pagehead' }, h('h1', { text: 'Compare runs' }),
      h('p', { class: 'sub', text: 'What changed between two runs, and whether the change is bigger than the judge’s own noise.' })));
    if (!c) { view.appendChild(h('div', { class: 'card', text: 'No baseline yet. A comparison appears once an earlier run exists on this branch (or on the default branch).' })); return; }
    view.appendChild(h('p', { class: 'hint', text: c.baselineLabel + ' · ' + c.baselineRun + ' → ' + c.candidateRun }));
    c.notes.forEach(function (n) { view.appendChild(h('div', { class: 'note', text: n.message })); });
    var st = h('div', { class: 'stats' });
    [['Matched', c.matched], ['Worse', c.worse], ['Better', c.better], ['Same', c.same], ['Within judge noise', c.withinNoise], ['New', c.added], ['Removed', c.removed], ['Not comparable', c.notComparable]]
      .forEach(function (x) { st.appendChild(h('div', { class: 'stat' }, h('b', { class: 'num', text: x[1] }), h('span', { text: x[0] }))); });
    view.appendChild(h('div', { class: 'sec' }, h('header', null, h('h2', { text: 'Headline' }), h('p', { text: 'Pass rate ' + pct(c.overallBaseRate) + ' → ' + pct(c.overallCandRate) + ' overall. Changes are counted on cases present in both runs; a change inside the judge noise band (±' + c.noiseBand.toFixed(2) + ') is not described as a regression.' })), st));
    view.appendChild(h('div', { class: 'sec' }, h('header', null, h('h2', { text: 'What changed in the setup' }), h('p', { text: 'Differences in the setup, not the results.' })),
      h('div', { class: 'card tbl' }, h('table', null, h('thead', null, h('tr', null, h('th', { text: 'Setting' }), h('th', { text: 'Baseline' }), h('th', { text: 'Candidate' }))),
        h('tbody', null, c.env.map(function (r) { return h('tr', { style: r.changed ? 'background:var(--tint-warn)' : null }, h('td', { text: r.field }), h('td', { class: 'mono', text: r.baseline || '–' }), h('td', { class: 'mono', text: r.candidate || '–' })); }))))));
    view.appendChild(h('div', { class: 'sec' }, h('header', null, h('h2', { text: 'Movement by quality dimension' }), h('p', { text: 'Hollow dot is the baseline, filled dot is this run, black tick is the goal.' })),
      h('div', { class: 'card' }, c.dimensions.map(function (d) {
        return h('div', { class: 'gaprow', style: 'grid-template-columns:140px minmax(0,1fr) 150px;margin:8px 0' },
          h('span', { text: d.name }), dumbbell(d),
          h('span', { class: 'num', text: pct(d.baseRate, 0) + ' → ' + pct(d.candRate, 0) + (d.worse ? ' · ' + d.worse + ' worse' : '') + (d.better ? ' · ' + d.better + ' better' : '') }));
      }))));
    view.appendChild(h('div', { class: 'sec' }, h('header', null, h('h2', { text: 'Changed cases' }), h('p', { text: 'Largest score change first. Select one to read both answers.' })),
      c.changed.length ? h('div', { class: 'card tbl' }, h('table', null, h('thead', null, h('tr', null, ['Case', 'Metric', 'Change', 'Baseline', 'Now', ''].map(function (t) { return h('th', { text: t }); }))),
        h('tbody', null, c.changed.map(function (x) {
          return h('tr', { class: 'row', tabindex: 0, onclick: function () { openChange(x); }, onkeydown: function (ev) { if (ev.key === 'Enter') openChange(x); } },
            h('td', null, h('b', { text: x.caseName })), h('td', { text: x.metric }),
            h('td', null, h('span', { class: 'pill ' + (x.change === 'WORSE' ? 'crit' : 'good'), text: x.change === 'WORSE' ? 'Worse' : 'Better' }), x.withinNoise ? h('span', { class: 'pill info', style: 'margin-left:6px', text: 'within noise' }) : null),
            h('td', { class: 'num', text: sc(x.baseScore) }), h('td', { class: 'num', text: sc(x.candScore) }), h('td', { class: 'num', text: x.scoreDelta == null ? '' : (x.scoreDelta > 0 ? '+' : '') + x.scoreDelta.toFixed(2) }));
        })))) : h('div', { class: 'card', text: 'No case changed.' })));
    if (c.scatter.length) view.appendChild(h('div', { class: 'sec' }, h('header', null, h('h2', { text: 'Every judged score, before and after' }), h('p', { text: 'Points on the diagonal did not move. The band is the judge noise.' })), h('div', { class: 'card' }, scatter(c))));
  }
  function dumbbell(d) {
    var lo = Math.min(d.baseRate == null ? 100 : d.baseRate, d.candRate == null ? 100 : d.candRate), hi = Math.max(d.baseRate || 0, d.candRate || 0);
    return h('div', { class: 'dumb', role: 'img', 'aria-label': d.name + ' ' + pct(d.baseRate, 0) + ' to ' + pct(d.candRate, 0) },
      h('div', { class: 'line' }), h('div', { class: 'span', style: 'left:' + lo + '%;width:' + (hi - lo) + '%;background:' + (d.delta < 0 ? 'var(--fail)' : 'var(--pass)') }),
      d.baseRate != null ? h('div', { class: 'dot', style: 'left:' + d.baseRate + '%;border:2px solid var(--ink-3);background:var(--surface)' }) : null,
      d.candRate != null ? h('div', { class: 'dot', style: 'left:' + d.candRate + '%;background:' + (d.delta < 0 ? 'var(--fail)' : 'var(--pass)') }) : null,
      h('div', { class: 'tick', style: 'left:' + d.goal + '%' }));
  }
  function scatter(c) {
    var S = 300, P = 30, svg = s('svg', { viewBox: '0 0 ' + (S + P * 2) + ' ' + (S + P * 2), width: '100%', style: 'max-width:420px', role: 'img', 'aria-label': 'Baseline versus candidate scores' });
    var x = function (v) { return P + v * S; }, y = function (v) { return P + (1 - v) * S; };
    svg.appendChild(s('rect', { x: P, y: P, width: S, height: S, fill: 'none', stroke: 'var(--line)' }));
    svg.appendChild(s('polygon', { points: [[0, 0], [0, c.noiseBand], [1 - c.noiseBand, 1], [1, 1], [1, 1 - c.noiseBand], [c.noiseBand, 0]].map(function (q) { return x(q[0]) + ',' + y(q[1]); }).join(' '), fill: 'var(--tint-pass)' }));
    svg.appendChild(s('line', { x1: x(0), y1: y(0), x2: x(1), y2: y(1), stroke: 'var(--ink-3)', 'stroke-dasharray': '3 3' }));
    c.scatter.forEach(function (p) {
      svg.appendChild(s('circle', { cx: x(p.base), cy: y(p.cand), r: 4, fill: p.change === 'WORSE' ? 'var(--fail)' : p.change === 'BETTER' ? 'var(--good)' : 'var(--pass)', opacity: p.change === 'SAME' ? .45 : .9 }, s('title', null, p.base.toFixed(2) + ' → ' + p.cand.toFixed(2))));
    });
    svg.appendChild(s('text', { x: S / 2 + P, y: S + P * 2 - 6, 'text-anchor': 'middle', 'font-size': 11, fill: 'var(--ink-3)' }, 'baseline score'));
    return svg;
  }
  function openChange(x) {
    var c = { name: x.caseName, caseId: x.caseId, input: null, evaluations: [], dims: {} };
    lastFocus = document.activeElement;
    clear(drawer);
    drawer.appendChild(h('button', { class: 'back', type: 'button', onclick: closeDrawer, text: '✕ Close' }));
    drawer.appendChild(h('h2', { text: x.caseName }));
    drawer.appendChild(h('p', { class: 'sub', text: x.metric + ' · ' + sc(x.baseScore) + ' → ' + sc(x.candScore) + (x.withinNoise ? ' · within judge noise' : '') }));
    [['Baseline reason', x.baseReason], ['Reason now', x.candReason]].forEach(function (r) { if (r[1]) drawer.appendChild(h('div', { class: 'field' }, h('div', { class: 'eyebrow', text: r[0] }), h('div', { class: 'text', text: r[1] }))); });
    if (x.answerUnchanged) drawer.appendChild(h('div', { class: 'note', text: 'The answer did not change; only the judgement did. That points at judge variation.' }));
    else if (x.baseOutput || x.candOutput) {
      drawer.appendChild(h('div', { class: 'field' }, h('div', { class: 'eyebrow', text: 'Answer, word by word' }), h('div', { class: 'text' }, wordDiff(x.baseOutput || '', x.candOutput || ''))));
    }
    drawer.removeAttribute('hidden'); scrim.removeAttribute('hidden'); drawer.focus();
  }
  function wordDiff(a, b) {
    var A = a.split(/(\s+)/), B = b.split(/(\s+)/), out = [];
    if (A.length * B.length > 250000) return [h('div', { text: 'Before: ' + a }), h('div', { text: 'Now: ' + b })];
    var L = [], i, j;
    for (i = 0; i <= A.length; i++) { L.push(new Array(B.length + 1).fill(0)); }
    for (i = A.length - 1; i >= 0; i--) for (j = B.length - 1; j >= 0; j--) L[i][j] = A[i] === B[j] ? L[i + 1][j + 1] + 1 : Math.max(L[i + 1][j], L[i][j + 1]);
    i = 0; j = 0;
    while (i < A.length && j < B.length) {
      if (A[i] === B[j]) { out.push(A[i]); i++; j++; }
      else if (L[i + 1][j] >= L[i][j + 1]) { out.push(h('span', { class: 'del', text: A[i] })); i++; }
      else { out.push(h('span', { class: 'add', text: B[j] })); j++; }
    }
    while (i < A.length) out.push(h('span', { class: 'del', text: A[i++] }));
    while (j < B.length) out.push(h('span', { class: 'add', text: B[j++] }));
    return out;
  }

  /* ---------- insight pages ---------- */
  function viewCoverage() {
    view.appendChild(h('div', { class: 'pagehead' }, h('h1', { text: 'Golden dataset coverage' }), h('p', { class: 'sub', text: 'Every dimension the dataset declares is listed, whether or not it was evaluated.' })));
    view.appendChild(h('div', { class: 'card tbl' }, h('table', null, h('thead', null, h('tr', null, ['Dimension', 'State', 'Declared', 'Evaluated', 'Not evaluated'].map(function (t) { return h('th', { text: t }); }))),
      h('tbody', null, M.dimensions.map(function (d) {
        return h('tr', { class: 'row', onclick: function () { go('#dim/' + encodeURIComponent(d.id)); } },
          h('td', null, h('b', { text: d.name })), h('td', { text: COVER[d.coverage.state] }), h('td', { class: 'num', text: d.coverage.declared }), h('td', { class: 'num', text: d.coverage.evaluated }),
          h('td', { text: d.coverage.missingCases.join(', ') }));
      })))));
  }
  function viewCost() {
    var e = M.evidence;
    view.appendChild(h('div', { class: 'pagehead' }, h('h1', { text: 'Cost and evidence' }), h('p', { class: 'sub', text: 'LLM judging costs money, so a build run only re-judges what changed.' })));
    var st = h('div', { class: 'stats' });
    [['Profile', e.profile || '–'], ['Evaluated now', e.fresh], ['Reused from cache', e.reused], ['Carried from earlier runs', e.carried], ['Judge spend', usd(e.costUsd)], ['Judge budget', usd(e.judgeBudgetUsd)],
      ['Estimated cost of a full re-judge', usd(e.estimatedFullCostUsd)], ['Saved by reuse', usd(e.savedByReuseUsd)]]
      .forEach(function (x) { st.appendChild(h('div', { class: 'stat' }, h('b', { class: 'num', text: x[1] }), h('span', { text: x[0] }))); });
    view.appendChild(st);
    view.appendChild(h('div', { class: 'card', style: 'margin-top:16px' }, evidenceBars()));
    if (e.costUsd == null) view.appendChild(h('p', { class: 'hint', text: 'No cost was recorded. Declare pricing with -Deval4j.pricing=<file> to see spend.' }));
  }
  function viewModels() {
    var env = M.meta.env || {};
    view.appendChild(h('div', { class: 'pagehead' }, h('h1', { text: 'Judges and models' }), h('p', { class: 'sub', text: 'Who judged, and what was judged.' })));
    function table(title, list, cols) {
      if (!list || !list.length) return h('div', { class: 'card', text: 'No ' + title.toLowerCase() + ' declared in this run.' });
      return h('div', { class: 'card tbl' }, h('table', null, h('thead', null, h('tr', null, cols.map(function (c) { return h('th', { text: c[0] }); }))),
        h('tbody', null, list.map(function (it) { return h('tr', null, cols.map(function (c) { var v = c[1](it); return h('td', { class: 'mono', text: v == null ? '–' : v }); })); }))));
    }
    view.appendChild(h('div', { class: 'sec' }, h('header', null, h('h2', { text: 'Judge models' })), table('Judges', env.judges, [
      ['Judge', function (j) { return j.id; }], ['Model', function (j) { return (j.provider ? j.provider + ' / ' : '') + (j.model || ''); }],
      ['Samples', function (j) { return j.samples; }], ['Calls', function (j) { return j.stats && j.stats.calls; }], ['Cache hits', function (j) { return j.stats && j.stats.cacheHits; }],
      ['Mean latency', function (j) { return j.stats && j.stats.latencyMeanMs != null ? ms(j.stats.latencyMeanMs) : null; }],
      ['Tokens in/out', function (j) { return j.stats ? (j.stats.tokensIn || 0) + ' / ' + (j.stats.tokensOut || 0) : null; }], ['Failures', function (j) { return j.stats && j.stats.failures; }]])));
    view.appendChild(h('div', { class: 'sec' }, h('header', null, h('h2', { text: 'Agents under test' })), table('Agents', env.agents, [
      ['Agent', function (a) { return a.id; }], ['Model', function (a) { return (a.provider ? a.provider + ' / ' : '') + (a.model || ''); }], ['Prompt', function (a) { return a.promptVersion || a.promptId; }],
      ['Tools', function (a) { return (a.tools || []).join(', '); }]])));
    view.appendChild(h('div', { class: 'sec' }, h('header', null, h('h2', { text: 'Datasets' })), table('Datasets', env.datasets, [
      ['Dataset', function (d) { return d.id; }], ['Scenarios', function (d) { return d.scenarioCount; }], ['Revision', function (d) { return d.revision; }], ['Hash', function (d) { return d.hash; }]])));
    judgeIndependence(env);
  }
  function judgeIndependence(env) {
    function family(m) { return m ? String(m).toLowerCase().split(/[\d\-:\/]/)[0] : ''; }
    (env.judges || []).forEach(function (j) {
      (env.agents || []).forEach(function (a) {
        if (j.model && a.model && family(j.model) && family(j.model) === family(a.model)) {
          view.appendChild(h('div', { class: 'note', text: 'Judge ' + j.id + ' and agent ' + a.id + ' come from the same model family (' + family(j.model) + '). A model can favour its own style, so treat its scores with care. This is a heuristic.' }));
        }
      });
    });
  }
  function viewTraces() {
    view.appendChild(h('div', { class: 'pagehead' }, h('h1', { text: 'Traces' }), h('p', { class: 'sub', text: 'Agent steps and workflow paths, read from the run.' })));
    M.traces.forEach(function (t) {
      var c = M.cases.filter(function (x) { return x.caseId === t.caseId; })[0];
      view.appendChild(h('div', { class: 'card', style: 'margin-bottom:14px' }, h('h3', { text: (c ? c.name : t.traceId) + ' · ' + (t.type === 'WORKFLOW' ? 'workflow' : 'agent steps') }), traceView(t)));
    });
  }
  function traceView(t) {
    if (t.type === 'WORKFLOW' && t.workflow) {
      var w = t.workflow, exp = w.expectedPath || [], act = w.actualPath || [], label = {};
      ((w.graph || {}).nodes || []).forEach(function (n) { label[n.id] = n.label || n.id; });
      function row(ids, ref) {
        return h('div', { class: 'path' }, ids.map(function (id, i) {
          var ok = ref ? ref[i] === id : null;
          return [i ? h('span', { class: 'hint', text: '→' }) : null, h('span', { class: 'node' + (ok === true ? ' ok' : ok === false ? ' bad' : ''), text: label[id] || id })];
        }));
      }
      return h('div', null, h('p', { class: 'hint', text: w.name }), h('div', { class: 'eyebrow', text: 'Expected path' }), row(exp), h('div', { class: 'eyebrow', style: 'margin-top:10px', text: 'Path taken' }), row(act, exp));
    }
    var box = h('div');
    (t.steps || []).forEach(function (st) {
      var bad = st.outcome !== 'EXECUTED';
      box.appendChild(h('div', { class: 'step' }, h('span', { class: 'n', text: st.index }),
        h('div', null, h('div', null, h('b', { class: 'mono', text: st.action }), ' ', h('span', { class: 'pill ' + (bad ? 'crit' : 'good'), text: st.outcome.toLowerCase().replace(/_/g, ' ') }), st.durationMs != null ? h('span', { class: 'hint', text: ' ' + ms(st.durationMs) }) : null),
          st.thought ? h('div', { class: 'hint', text: st.thought }) : null, st.input ? h('div', { class: 'mono hint', text: st.input }) : null, st.observation ? h('div', { text: st.observation }) : null)));
    });
    if (t.stepBudget) box.appendChild(h('p', { class: 'hint', text: (t.steps || []).length + ' of ' + t.stepBudget + ' budgeted steps used.' }));
    return box;
  }
  function viewOptim() {
    view.appendChild(h('div', { class: 'pagehead' }, h('h1', { text: 'Prompt optimizer' }), h('p', { class: 'sub', text: 'Rewrites of a prompt, scored on a held-out validation set.' })));
    M.optimizations.forEach(function (o) {
      var pts = o.rounds || [], W = 560, H = 160, svg = s('svg', { viewBox: '0 0 ' + W + ' ' + H, width: '100%', role: 'img', 'aria-label': 'Best validation score by round' });
      var n = Math.max(1, pts.length - 1), d = '';
      pts.forEach(function (r, i) {
        var x = 20 + i / n * (W - 40), y = 10 + (1 - r.bestScore) * (H - 30); d += (i ? 'L' : 'M') + x + ' ' + y;
        svg.appendChild(s('circle', { cx: x, cy: y, r: 4, fill: r.action === 'REJECTED' ? 'var(--surface)' : 'var(--pass)', stroke: 'var(--pass)', 'stroke-width': 2 }, s('title', null, 'round ' + r.index + ' ' + r.action + ' best ' + sc(r.bestScore))));
      });
      svg.insertBefore(s('path', { d: d, fill: 'none', stroke: 'var(--pass)', 'stroke-width': 2 }), svg.firstChild);
      var diff = h('div', { class: 'text' }, (o.diff || []).map(function (l) { return h('div', { class: l.op === 'ADD' ? 'add' : l.op === 'DEL' ? 'del' : null, text: (l.op === 'ADD' ? '+ ' : l.op === 'DEL' ? '- ' : '  ') + l.text }); }));
      view.appendChild(h('div', { class: 'card', style: 'margin-bottom:14px' }, h('h3', { text: o.promptId + (o.fromVersion ? ' ' + o.fromVersion + ' → ' + o.toVersion : '') }),
        h('p', { class: 'hint', text: [o.stopReason, o.goal != null ? 'goal ' + sc(o.goal) : null, o.budget ? o.budget.calls + ' calls, ' + usd(o.budget.costUsd) : null, o.overfit ? 'train ' + sc(o.overfit.trainScore) + ' vs validation ' + sc(o.overfit.validationScore) : null].filter(Boolean).join(' · ') }),
        svg, o.diff && o.diff.length ? diff : null));
    });
  }
  function viewNotes() {
    view.appendChild(h('div', { class: 'pagehead' }, h('h1', { text: 'Data notes' }), h('p', { class: 'sub', text: 'Anything that could not be taken at face value.' })));
    if (!M.notes.length) view.appendChild(h('div', { class: 'card', text: 'No problems found in the run data.' }));
    M.notes.forEach(function (n) { view.appendChild(h('div', { class: 'note' }, h('b', { text: n.code + ': ' }), n.message)); });
  }

  /* ---------- shell ---------- */
  $('menu').addEventListener('click', function () { side.classList.toggle('open'); });
  $('theme').addEventListener('click', function () {
    var cur = document.documentElement.getAttribute('data-theme');
    var dark = cur ? cur === 'dark' : window.matchMedia('(prefers-color-scheme: dark)').matches;
    document.documentElement.setAttribute('data-theme', dark ? 'light' : 'dark');
    try { localStorage.setItem('eval4j.theme', dark ? 'light' : 'dark'); } catch (e) { /* ignore */ }
  });
  try { var th = localStorage.getItem('eval4j.theme'); if (th) document.documentElement.setAttribute('data-theme', th); } catch (e) { /* ignore */ }
  $('chip').textContent = (M.meta.branch || 'no branch') + (M.meta.commit ? ' · ' + M.meta.commit : '');
  window.addEventListener('hashchange', route);
  route();
})();
