(function () {
  var root = document.documentElement;
  root.className += ' js';
  function store(k, v) { try { if (v === undefined) { return localStorage.getItem(k); } localStorage.setItem(k, v); } catch (e) { return null; } }
  var saved = store('eval4j-theme');
  if (saved) { root.setAttribute('data-theme', saved); }
  var themeBtn = document.getElementById('theme');
  if (themeBtn) {
    themeBtn.addEventListener('click', function () {
      var dark = root.getAttribute('data-theme') === 'dark' ||
        (!root.getAttribute('data-theme') && window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches);
      var next = dark ? 'light' : 'dark';
      root.setAttribute('data-theme', next);
      store('eval4j-theme', next);
    });
  }

  var list = document.getElementById('evlist');
  if (list) {
    var items = Array.prototype.slice.call(list.children);
    var q = document.getElementById('q'), st = document.getElementById('st'),
        mt = document.getElementById('mt'), so = document.getElementById('so'),
        count = document.getElementById('count');
    items.forEach(function (el, i) { el.dataset.i = i; });
    function apply() {
      var needle = q.value.trim().toLowerCase(), status = st.value, metric = mt.value, shown = 0;
      items.forEach(function (el) {
        if (needle && el.dataset.hay === undefined) { el.dataset.hay = el.textContent.toLowerCase(); }
        var ok = (status === 'all' || el.dataset.status === status) &&
                 (metric === 'all' || el.dataset.metric === metric) &&
                 (!needle || el.dataset.hay.indexOf(needle) >= 0);
        el.hidden = !ok;
        if (ok) { shown++; }
      });
      count.textContent = shown + ' of ' + items.length + ' shown';
    }
    function sortItems() {
      var how = so.value, sorted = items.slice();
      sorted.sort(function (a, b) {
        if (how === 'asc') { return a.dataset.score - b.dataset.score || a.dataset.i - b.dataset.i; }
        if (how === 'desc') { return b.dataset.score - a.dataset.score || a.dataset.i - b.dataset.i; }
        if (how === 'gap') { return a.dataset.gap - b.dataset.gap || a.dataset.i - b.dataset.i; }
        if (how === 'slow') { return b.dataset.ms - a.dataset.ms || a.dataset.i - b.dataset.i; }
        return a.dataset.i - b.dataset.i;
      });
      sorted.forEach(function (el) { list.appendChild(el); });
    }
    [q, st, mt].forEach(function (c) { c.addEventListener('input', apply); });
    so.addEventListener('input', sortItems);
    document.getElementById('open-all').addEventListener('click', function () {
      items.forEach(function (el) { if (!el.hidden) { el.open = true; } });
    });
    document.getElementById('close-all').addEventListener('click', function () {
      items.forEach(function (el) { el.open = false; });
    });
    document.getElementById('csv').addEventListener('click', function () {
      function cell(v) {
        v = String(v == null ? '' : v);
        if (/^[=+\-@]/.test(v)) { v = "'" + v; }
        return '"' + v.replace(/"/g, '""') + '"';
      }
      var rows = [['status', 'suite', 'test', 'metric', 'score', 'threshold', 'reason']];
      items.forEach(function (el) {
        if (el.hidden) { return; }
        var d = el.dataset;
        rows.push([d.status, d.suite, d.test, d.metric, d.score, d.threshold, d.reason]);
      });
      var blob = new Blob([rows.map(function (r) { return r.map(cell).join(','); }).join('\n')], { type: 'text/csv' });
      var a = document.createElement('a');
      a.href = URL.createObjectURL(blob);
      a.download = 'eval4j-filtered.csv';
      a.click();
      setTimeout(function () { URL.revokeObjectURL(a.href); }, 1000);
    });
    document.addEventListener('keydown', function (e) {
      if (e.key === '/' && document.activeElement !== q && !/input|select|textarea/i.test(document.activeElement.tagName)) {
        e.preventDefault(); q.focus();
      }
    });
    apply();
  }

  function openTarget() {
    var id = location.hash.slice(1), el = id && document.getElementById(id);
    if (el && el.tagName === 'DETAILS') { el.hidden = false; el.open = true; el.scrollIntoView({ block: 'center' }); }
  }
  window.addEventListener('hashchange', openTarget);
  openTarget();

  Array.prototype.forEach.call(document.querySelectorAll('table.sortable th'), function (th) {
    th.addEventListener('click', function () {
      var t = th.closest('table'), b = t.tBodies[0],
          i = Array.prototype.indexOf.call(th.parentNode.children, th),
          rows = Array.prototype.slice.call(b.rows), asc = th.dataset.asc !== '1';
      th.dataset.asc = asc ? '1' : '0';
      rows.sort(function (x, y) {
        var a = x.cells[i].textContent.trim(), c = y.cells[i].textContent.trim(), n = parseFloat(a), m = parseFloat(c);
        var r = (!isNaN(n) && !isNaN(m)) ? n - m : a.localeCompare(c);
        return asc ? r : -r;
      });
      rows.forEach(function (r) { b.appendChild(r); });
    });
  });
})();
