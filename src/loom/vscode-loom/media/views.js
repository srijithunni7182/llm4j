/*
 * The three views of the Loom Graph panel (Outline, Focus, Flow), the main-path rail, the breadcrumb and the inspector.
 * Each draws from a model made by graph-model.js into plain DOM (Flow adds one SVG for its connectors), so text stays
 * crisp at 100%. Text from a script is only ever set with textContent. The page's content-security policy forbids inline
 * styles, so looks come from classes in panel.css; the few measured positions are set through the element's style object.
 */
(function (root, factory) {
  var api = factory(root.LoomModel || (typeof require === 'function' ? require('./graph-model.js') : null));
  if (typeof module === 'object' && module.exports) { module.exports = api; }
  root.LoomViews = api;
})(typeof globalThis !== 'undefined' ? globalThis : this, function (M) {
  'use strict';

  var VK = M.VK;

  /* -------------------------------------------------------------- the glyphs */

  /** Lucide-style, 24 viewBox, 1.5 stroke. These strings are ours, never from a script. */
  var GLYPHS = {
    bot: '<path d="M12 8V4H8"/><rect width="16" height="12" x="4" y="8" rx="2"/><path d="M2 14h2M20 14h2M15 13v2M9 13v2"/>',
    scale: '<path d="m16 16 3-8 3 8c-.87.65-1.92 1-3 1s-2.13-.35-3-1Z"/><path d="m2 16 3-8 3 8c-.87.65-1.92 1-3 1s-2.13-.35-3-1Z"/><path d="M7 21h10M12 3v18M3 7h2c2 0 5-1 7-2 2 1 5 2 7 2h2"/>',
    shield: '<path d="M20 13c0 5-3.5 7.5-7.66 8.95a1 1 0 0 1-.67-.01C7.5 20.5 4 18 4 13V6a1 1 0 0 1 1-1c2 0 4.5-1.2 6.24-2.72a1.17 1.17 0 0 1 1.52 0C14.51 3.81 17 5 19 5a1 1 0 0 1 1 1z"/><path d="m9 12 2 2 4-4"/>',
    shieldplain: '<path d="M20 13c0 5-3.5 7.5-7.66 8.95a1 1 0 0 1-.67-.01C7.5 20.5 4 18 4 13V6a1 1 0 0 1 1-1c2 0 4.5-1.2 6.24-2.72a1.17 1.17 0 0 1 1.52 0C14.51 3.81 17 5 19 5a1 1 0 0 1 1 1z"/>',
    usercheck: '<path d="m16 11 2 2 4-4"/><path d="M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2"/><circle cx="9" cy="7" r="4"/>',
    split: '<path d="M16 3h5v5M8 3H3v5M12 22v-8.3a4 4 0 0 0-1.17-2.87L3 3M15 9l6-6"/>',
    spiral: '<path d="M12 12a1 1 0 0 1 2 0a2 2 0 0 1-4 0a3 3 0 0 1 6 0a4 4 0 0 1-8 0a5 5 0 0 1 10 0a6 6 0 0 1-12 0"/><path d="M6 12v-2.5M6 12l2.2-1.4"/>',
    user: '<circle cx="12" cy="8" r="5"/><path d="M20 21a8 8 0 0 0-16 0"/>',
    terminal: '<path d="m7 11 2-2-2-2M11 13h4"/><rect width="18" height="18" x="3" y="3" rx="2"/>',
    note: '<path d="M15.5 3H5a2 2 0 0 0-2 2v14c0 1.1.9 2 2 2h14a2 2 0 0 0 2-2V8.5L15.5 3Z"/><path d="M15 3v6h6"/>',
    stop: '<path d="m15 9-6 6M9 9l6 6"/><path d="M2.59 16.73A2 2 0 0 1 2 15.31V8.69a2 2 0 0 1 .59-1.42l4.68-4.68A2 2 0 0 1 8.69 2h6.62a2 2 0 0 1 1.42.59l4.68 4.68A2 2 0 0 1 22 8.69v6.62a2 2 0 0 1-.59 1.42l-4.68 4.68a2 2 0 0 1-1.42.59H8.69a2 2 0 0 1-1.42-.59z"/>',
    flag: '<path d="M4 15s1-1 4-1 5 2 8 2 4-1 4-1V3s-1 1-4 1-5-2-8-2-4 1-4 1zM4 22v-7"/>',
    columns: '<rect width="18" height="18" x="3" y="3" rx="2"/><path d="M12 3v18"/>',
    callout: '<path d="M7 7h10v10"/><path d="M7 17 17 7"/>',
    rewind: '<path d="M11 19 2 12l9-7z"/><path d="M22 19l-9-7 9-7z"/>',
    eye: '<path d="M2.06 12.35a1 1 0 0 1 0-.7 10.75 10.75 0 0 1 19.88 0 1 1 0 0 1 0 .7 10.75 10.75 0 0 1-19.88 0"/><circle cx="12" cy="12" r="3"/>',
    eyeclosed: '<path d="m15 18-.722-3.25"/><path d="M2 8a10.645 10.645 0 0 0 20 0"/><path d="m20 15-1.726-2.05"/><path d="m4 15 1.726-2.05"/><path d="m9 18 .722-3.25"/>',
    clock: '<circle cx="12" cy="12" r="10"/><path d="M12 6v6l4 2"/>',
    unknown: '<circle cx="12" cy="12" r="10"/><path d="M12 8v4M12 16h.01"/>',
    download: '<path d="M12 15V3"/><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><path d="m7 10 5 5 5-5"/>'
  };

  function svg(glyph) {
    var s = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
    s.setAttribute('viewBox', '0 0 24 24'); s.setAttribute('fill', 'none'); s.setAttribute('stroke', 'currentColor');
    s.setAttribute('stroke-width', '1.5'); s.setAttribute('stroke-linecap', 'round'); s.setAttribute('stroke-linejoin', 'round');
    s.setAttribute('aria-hidden', 'true'); s.setAttribute('focusable', 'false');
    s.innerHTML = GLYPHS[glyph] || GLYPHS.unknown;
    return s;
  }

  /* ----------------------------------------------------------------- helpers */

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
  function baseName(path) { var parts = String(path || '').split(/[\\/]/); return parts[parts.length - 1]; }
  function px(n) { return Math.round(n * 10) / 10 + 'px'; }

  function badge(vk, size) {
    var k = VK[vk] || VK.unknown;
    var b = el('span', { class: 'badge k-' + vk + ' sh-' + k.shape + ' s' + size, 'aria-hidden': 'true', title: k.name });
    var inner = el('span', { class: 'glyph' });
    inner.appendChild(svg(k.glyph));
    b.appendChild(inner);
    return b;
  }

  function kindLine(n) {
    if (n.kind === 'start') { return 'Start'; }
    if (n.kind === 'end') { return 'End'; }
    var name = (VK[n.vk] || VK.unknown).name;
    return n.agent ? name + ' ' + n.agent : name;
  }

  function chipsOf(ctx, n, max) { return (ctx.chips ? ctx.chips(n.raw) : []).slice(0, max == null ? 3 : max); }
  function chipRow(ctx, n, max) {
    var chips = chipsOf(ctx, n, max);
    return chips.length ? el('span', { class: 'chips' }, chips.map(function (c) { return el('span', { class: 'chip' + (c.v ? ' ' + c.v : ''), text: c.t }); })) : null;
  }

  /** The tools of the agent a step runs, as chips: every name, or only a count when the card is small. */
  function toolChips(ctx, n, how) {
    var agent = n.agent && ctx.agents[n.agent];
    if (!agent) { return null; }
    var tools = agent.tools || [];
    if (!tools.length) { return how === 'count' ? null : el('span', { class: 'chips tools' }, el('span', { class: 'chip tool none', text: 'no tools' })); }
    if (how === 'count') { return el('span', { class: 'chips tools' }, el('span', { class: 'chip tool', text: tools.length + (tools.length === 1 ? ' tool' : ' tools'), title: tools.join(', ') })); }
    var asks = agent.approveAll ? tools : (agent.approve || []);
    return el('span', { class: 'chips tools' }, tools.map(function (t) { return el('span', { class: 'chip tool' + (asks.indexOf(t) >= 0 ? ' asks' : ''), text: t, title: asks.indexOf(t) >= 0 ? t + ' (needs approval)' : t }); }));
  }

  /** A step as a card. variant: 'stack' (Outline centre), 'compact' (Focus sides), 'flow' (Flow spine), 'drop' (Flow detours). */
  function card(ctx, n, variant) {
    var sel = n.id === ctx.selected, term = n.vk === 'term';
    var cls = 'card k-' + n.vk + ' v-' + variant + (n.main ? ' main' : ' off') + (n.exit ? ' exit' : '') + (term ? ' term' : '') + (sel ? ' sel' : '');
    var size = variant === 'compact' ? 30 : variant === 'drop' ? 28 : variant === 'flow' ? 28 : 34;
    var node = el('div', { class: cls, 'data-id': n.id, title: n.label, role: 'button', tabindex: '0', 'aria-pressed': sel ? 'true' : 'false', 'aria-label': (VK[n.vk] || VK.unknown).name + ': ' + n.label + (n.line ? ', line ' + n.line : '') });
    if (term) {
      node.appendChild(badge('term', 20));
      node.appendChild(el('span', { class: 't', text: n.kind === 'start' ? 'Start' : 'End' }));
      return node;
    }
    if (variant === 'flow') {
      node.appendChild(el('div', { class: 'kick' }, badge(n.vk, size), el('span', { class: 'kname', text: (VK[n.vk] || VK.unknown).name.toUpperCase() })));
      node.appendChild(el('div', { class: 't', text: n.label }));
      add(node, chipRow(ctx, n, 1)); add(node, toolChips(ctx, n, 'count'));
      return node;
    }
    node.appendChild(badge(n.vk, size));
    node.appendChild(el('div', { class: 'body' },
      el('div', { class: 'r1' }, el('span', { class: 't', text: n.label }), variant === 'drop' && n.exit ? el('span', { class: 'ends', text: 'ENDS' }) : null),
      el('div', { class: 'sub', text: kindLine(n) }),
      variant === 'stack' ? chipRow(ctx, n, 3) : null,
      variant === 'stack' ? toolChips(ctx, n, 'names') : null,
      variant === 'compact' ? toolChips(ctx, n, 'count') : null));
    return node;
  }

  function corners() { return ['tl', 'tr', 'bl', 'br'].map(function (c) { return el('i', { class: 'corner ' + c, 'aria-hidden': 'true' }); }); }

  function agentTable(ctx, n, cols) {
    var agent = n.agent && ctx.agents[n.agent];
    if (!agent) { return null; }
    var rows = [['Agent', agent.name]];
    if (agent.model) { rows.push(['Model', agent.model]); }
    rows.push(['Tools', (agent.tools || []).length ? agent.tools.join(', ') : 'none']);
    if (agent.temperature != null) { rows.push(['Temp', String(agent.temperature)]); }
    if (agent.prompt) { rows.push(['Prompt', agent.prompt.ref + (agent.prompt.version ? '@' + agent.prompt.version : '') + (agent.prompt.file ? '.md' : ' (not found)')]); }
    if (agent.persona) { rows.push(['Persona', agent.persona]); }
    var extra = [];
    if (agent.maxIterations) { extra.push('max ' + agent.maxIterations + ' iterations'); }
    if (agent.approveAll) { extra.push('approval for every tool'); } else if ((agent.approve || []).length) { extra.push('approval: ' + agent.approve.join(', ')); }
    if (agent.budget && Object.keys(agent.budget).length && ctx.budgetLine) { extra.push('budget ' + ctx.budgetLine(agent.budget)); }
    var table = el('dl', { class: 'agent' + (cols === 4 ? ' cols4' : '') }, rows.map(function (r) { return [el('dt', { text: r[0] }), el('dd', { text: r[1] })]; }));
    return el('div', { class: 'agentbox' }, table, extra.length ? el('p', { class: 'extra', text: extra.join(' · ') }) : null);
  }

  function actions(ctx, n) {
    var list = [];
    if (n.file && n.line != null) {
      list.push(el('button', { class: 'btn primary blueprint', type: 'button', 'data-act': 'source', onclick: function () { ctx.on.source(n); } }, 'Go to ', el('b', { class: 'mono', text: baseName(n.file) + ':' + n.line }), corners()));
    }
    var agent = n.agent && ctx.agents[n.agent];
    if (agent && agent.prompt && agent.prompt.file) {
      list.push(el('button', { class: 'btn', type: 'button', 'data-act': 'prompt', onclick: function () { ctx.on.prompt(agent); } }, 'Open prompt ' + agent.prompt.ref));
    }
    if (n.kind === 'call' && n.raw.call && !n.raw.unresolved) {
      list.push(el('button', { class: 'btn', type: 'button', 'data-act': 'drill', onclick: function () { ctx.on.drill(n); } }, 'Open ' + n.raw.call.workflow));
    }
    return list;
  }

  /* --------------------------------------------------------------- inspector */

  function inspector(ctx) {
    var n = ctx.model.by[ctx.selected], box = el('aside', { class: 'inspector', 'aria-label': 'Selected step' });
    if (!n) { box.appendChild(el('p', { class: 'muted', text: 'Click a step to see its settings and jump to its line. Double-click a call to open it.' })); return box; }
    var k = VK[n.vk] || VK.unknown;
    box.appendChild(el('div', { class: 'k-' + n.vk + ' ihead' }, badge(n.vk, 34), el('span', { class: 'kicker ink', text: 'Selected · ' + k.name })));
    box.appendChild(el('h4', { text: n.label }));
    var sub = el('div', { class: 'k-' + n.vk + ' isub' });
    add(sub, chipRow(ctx, n, 6));
    add(box, el('div', { class: 'k-' + n.vk + ' isub toolsrow' }, n.agent && ctx.agents[n.agent] ? el('span', { class: 'kicker', text: 'Tools' }) : null, toolChips(ctx, n, 'names')));
    box.appendChild(sub);
    add(box, agentTable(ctx, n, 2));
    var buttons = actions(ctx, n);
    buttons.push(el('button', { class: 'btn', type: 'button', 'data-act': 'focus', onclick: function () { ctx.on.view('focus'); } }, 'Focus on this step'));
    box.appendChild(el('div', { class: 'actions' }, buttons));
    return box;
  }

  /* ----------------------------------------------------------------- outline */

  function treeRows(ctx) {
    var m = ctx.model, rows = [], kids = Object.create(null);
    m.nodes.forEach(function (n) { if (n.parent) { kids[n.parent] = (kids[n.parent] || 0) + 1; } });
    var shown = Object.create(null); M.visibleOrder(m, ctx.collapsed).forEach(function (n) { shown[n.id] = true; });
    m.sections.forEach(function (s, i) {
      if (m.hasSections) {
        rows.push(el('div', { class: 'sechead' + (m.by[ctx.selected] && m.by[ctx.selected].section === i ? ' here' : ''), role: 'presentation' },
          el('span', { class: 'secn', text: String(s.n) }), el('span', { class: 'sect', text: s.title })));
      }
      m.nodes.forEach(function (n) {
        if (n.section !== i || !shown[n.id]) { return; }
        var sel = n.id === ctx.selected, folded = ctx.collapsed.has(n.id), count = M.descendants(m, n.id).length;
        var row = el('div', { class: 'row k-' + n.vk + (n.main ? ' main' : '') + (n.exit ? ' exit' : '') + (sel ? ' sel' : ''), 'data-id': n.id, role: 'treeitem', tabindex: '0', 'aria-selected': sel ? 'true' : 'false',
          'aria-level': String(n.depth + 1), 'aria-expanded': kids[n.id] ? String(!folded) : null });
        row.style.paddingLeft = (8 + n.depth * 16) + 'px';
        row.appendChild(badge(n.vk, 20));
        row.appendChild(el('span', { class: 'lbl', text: n.label }));
        if (kids[n.id]) {
          if (folded) { row.appendChild(el('span', { class: 'hid', text: '+' + count })); }
          row.appendChild(el('button', { class: 'tog', type: 'button', text: folded ? '+' : '−', 'aria-label': (folded ? 'Expand ' : 'Collapse ') + n.label, 'data-toggle': n.id, onclick: function (e) { e.stopPropagation(); ctx.on.toggle(n.id); } }));
        }
        rows.push(row);
      });
    });
    return rows;
  }

  function sectionCards(ctx) {
    var m = ctx.model, sel = m.by[ctx.selected], idx = sel ? sel.section : 0, section = m.sections[idx];
    var wrap = el('div', { class: 'stack', 'data-export': 'outline' });
    if (m.hasSections) {
      wrap.appendChild(el('h3', { class: 'sechd' }, el('span', { class: 'bign', text: String(section.n) }), el('span', { class: 'tt', text: section.title })));
      if (section.hint) { wrap.appendChild(el('p', { class: 'hint', text: section.hint })); }
    } else {
      wrap.appendChild(el('h3', { class: 'sechd' }, el('span', { class: 'tt', text: m.name })));
    }
    var shown = Object.create(null); M.visibleOrder(m, ctx.collapsed).forEach(function (n) { shown[n.id] = true; });
    var inSection = m.nodes.filter(function (n) { return n.section === idx && (shown[n.id] || n.kind === 'start' || n.kind === 'end'); });
    inSection.forEach(function (n, i) {
      var incoming = m.inn[n.id].filter(function (e) { return e.label && m.by[e.from].section === idx; })[0];
      var entry = el('div', { class: 'entry', 'data-depth': String(n.depth) });
      entry.style.marginLeft = (n.depth * 36) + 'px';
      if (i) {
        entry.appendChild(el('div', { class: 'conn' }, incoming ? el('span', { class: 'edgechip', text: incoming.label }) : null));
      }
      entry.appendChild(card(ctx, n, 'stack'));
      wrap.appendChild(entry);
    });
    var next = m.sections[idx + 1];
    if (next) {
      var target = (next.main && next.main[0]) || (m.nodes.filter(function (n) { return n.section === idx + 1; })[0] || {}).id;
      if (target) { wrap.appendChild(el('button', { class: 'btn continue', type: 'button', 'data-act': 'next', onclick: function () { ctx.on.select(target, true); } }, 'Continue to ' + next.n + ' · ' + next.title + ' →')); }
    }
    return wrap;
  }

  function outline(ctx) {
    var tree = el('div', { class: 'tree', role: 'tree', 'aria-label': 'Outline' });
    tree.appendChild(el('div', { class: 'treehead' }, el('span', { class: 'kicker', text: 'Outline' }),
      el('span', { class: 'spacer' }),
      el('button', { class: 'ghost', type: 'button', text: 'Expand', 'data-act': 'expand', onclick: function () { ctx.on.expandAll(); } }),
      el('button', { class: 'ghost', type: 'button', text: 'Collapse', 'data-act': 'collapse', onclick: function () { ctx.on.collapseAll(); } })));
    add(tree, treeRows(ctx));
    var centre = el('div', { class: 'centre dots' }, sectionCards(ctx));
    return el('div', { class: 'outline' }, tree, centre, inspector(ctx));
  }

  /* ------------------------------------------------------------------- focus */

  function focus(ctx) {
    var m = ctx.model, n = m.by[ctx.selected] || m.nodes[0];
    function side(edges, key, title) {
      var col = el('div', { class: 'fside ' + key });
      if (!edges.length) { return col; }
      col.appendChild(el('div', { class: 'kicker', text: title }));
      edges.forEach(function (e) {
        var other = m.by[e[key === 'from' ? 'from' : 'to']];
        col.appendChild(el('div', { class: 'lrow' }, key === 'from' ? [card(ctx, other, 'compact'), stub(e)] : [stub(e), card(ctx, other, 'compact')]));
      });
      return col;
    }
    function stub(e) { return el('div', { class: 'stub' }, el('span', { class: 'edgechip', text: M.edgeWord(e.label) })); }
    var k = VK[n.vk] || VK.unknown, section = m.sections[n.section];
    var inside = n.children.map(function (id) { return m.by[id]; });
    var centre = el('div', { class: 'bigcard k-' + n.vk + (n.main ? ' main' : ' off') + (n.exit ? ' exit' : ''), 'data-id': n.id },
      corners(),
      el('div', { class: 'bhead' }, badge(n.vk, 46),
        el('div', { class: 'bk' }, el('span', { class: 'kicker ink', text: k.name }), m.hasSections ? el('span', { class: 'secline', text: section.n + ' · ' + section.title, title: section.title }) : null)),
      el('h2', { text: n.label }),
      chipRow(ctx, n, 6),
      toolChips(ctx, n, 'names'),
      agentTable(ctx, n, 4),
      inside.length ? el('div', { class: 'inside' }, el('div', { class: 'kicker', text: 'Inside this block' }), inside.map(function (c) {
        return el('button', { class: 'insrow k-' + c.vk, type: 'button', 'data-id': c.id, onclick: function () { ctx.on.select(c.id, false); } }, badge(c.vk, 24), el('span', { class: 'lbl', text: c.label }));
      })) : null,
      el('div', { class: 'actions' }, actions(ctx, n)));
    var grid = el('div', { class: 'fgrid', 'data-export': 'focus' }, side(m.inn[n.id], 'from', 'Comes from'), centre, side(m.out[n.id], 'to', 'Goes to'));
    return el('div', { class: 'focus dots' }, grid);
  }

  /* -------------------------------------------------------------------- flow */

  var FLOW = { W: 184, G: 44, X0: 24, SY: 56, SH: 104, DH: 56, DG: 12, PILL_W: 110, PILL_H: 48 };

  /** The positions of everything in the Flow view, from the model alone (so it can be tested without a page). */
  function flowLayout(m) {
    var F = FLOW, pos = Object.create(null), drops = [], paths = [], labels = [], maxY = F.SY + F.SH;
    function edgeLabel(a, b) { var e = m.out[a].filter(function (x) { return x.to === b; })[0]; return e && e.label; }
    m.main.forEach(function (id, i) {
      var n = m.by[id], pill = n.vk === 'term';
      pos[id] = { x: F.X0 + i * (F.W + F.G), y: pill ? F.SY + (F.SH - F.PILL_H) / 2 : F.SY, w: pill ? F.PILL_W : F.W, h: pill ? F.PILL_H : F.SH };
    });
    var midY = F.SY + F.SH / 2;
    m.main.forEach(function (id, i) {
      if (!i) { return; }
      var q = pos[m.main[i - 1]], p = pos[id];
      paths.push({ d: 'M' + (q.x + q.w) + ' ' + midY + 'H' + (p.x - 2), cls: 'p-main' });
      var t = edgeLabel(m.main[i - 1], id);
      if (t) { labels.push({ t: t, x: (q.x + q.w + p.x) / 2, y: midY - 22 }); }
    });
    m.main.forEach(function (id) {
      var off = m.nodes.filter(function (n) { return !n.main && n.anchor === id && n.kind !== 'start' && n.kind !== 'end'; });
      if (!off.length) { return; }
      var p = pos[id], lx = p.x + 22, y = F.SY + F.SH + 40, anchor = m.by[id];
      var first = off[0], why = edgeLabel(id, first.id) || (first.parent && edgeLabel(first.parent, first.id)) || first.branch || 'then';
      // a step in an on_stopped handler (or inside a block in it) hangs off the step on a dotted line of its own
      function stopped(n) { for (var q = n, hops = 0; q && hops++ < 50; q = q.parent ? m.by[q.parent] : null) { if (q.branch === 'stopped') { return true; } if (q.id === id) { break; } } return false; }
      var firstStopped = null, lastStopped = null, lastPlain = null;
      off.forEach(function (n) {
        var ind = Math.max(0, Math.min(n.depth - anchor.depth - 1, 2)) * 12, isStop = stopped(n);
        pos[n.id] = { x: lx + 18 + ind, y: y, w: F.W + 30 - ind, h: F.DH };
        drops.push(n.id);
        paths.push({ d: 'M' + lx + ' ' + (y + F.DH / 2) + 'H' + (lx + 16 + ind), cls: isStop ? 'p-stopped' : 'p-detour' });
        if (isStop) { if (!firstStopped) { firstStopped = pos[n.id]; } lastStopped = pos[n.id]; } else { lastPlain = pos[n.id]; }
        y += F.DH + F.DG;
      });
      var last = pos[off[off.length - 1].id], plainEnd = lastPlain || null;
      if (firstStopped && !plainEnd) {
        paths.push({ d: 'M' + lx + ' ' + (F.SY + F.SH) + 'V' + (last.y + F.DH / 2), cls: 'p-stopped' });
        labels.push({ t: 'stopped', x: lx + 30, y: F.SY + F.SH + 10 });
      } else {
        paths.push({ d: 'M' + lx + ' ' + (F.SY + F.SH) + 'V' + (last.y + F.DH / 2), cls: 'p-detour' });
        labels.push({ t: why, x: lx + 30, y: F.SY + F.SH + 10 });
        if (firstStopped) { labels.push({ t: 'stopped', x: lx + 30, y: firstStopped.y - 12 }); }
      }
      var inGroup = Object.create(null); off.forEach(function (n) { inGroup[n.id] = true; });
      var k = 0;
      m.nodes.forEach(function (n) {
        if (!inGroup[n.id]) { return; }
        m.out[n.id].forEach(function (e) {
          var t = pos[e.to], s = pos[n.id];
          if (!t || !m.by[e.to].main || m.by[e.to].kind === 'end') { return; }
          if (e.label === 'rewind') {
            var rr = s.x + s.w + 14 + k * 8, ry = F.SY + F.SH + 18 + k * 6; k++;
            paths.push({ d: 'M' + (s.x + s.w) + ' ' + (s.y + F.DH / 2) + 'H' + rr + 'V' + ry + 'H' + (t.x + t.w - 30) + 'V' + (F.SY + F.SH + 4), cls: 'p-rewind' });
            var cap = (m.by[n.id].raw.attrs || {}).atMost;
            labels.push({ t: 'sent back' + (cap ? ' ≤' + cap : ''), x: rr - 22, y: ry - 12 });
          } else if (e.label === 'again') {
            var rx = s.x + s.w + 14 + k * 8, ty = F.SY + F.SH + 18 + k * 6; k++;
            paths.push({ d: 'M' + (s.x + s.w) + ' ' + (s.y + F.DH / 2) + 'H' + rx + 'V' + ty + 'H' + (t.x + t.w - 30) + 'V' + (F.SY + F.SH + 4), cls: 'p-back' });
            var bound = m.by[e.to].raw.bound || (m.by[e.to].raw.attrs || {}).max;
            if (k === 1) { labels.push({ t: 'again' + (bound ? ' ≤' + bound : ''), x: rx - 22, y: F.SY + F.SH + 8 }); }
          } else {
            paths.push({ d: 'M' + (s.x + s.w) + ' ' + (s.y + F.DH / 2) + 'H' + (t.x + 30) + 'V' + (F.SY + F.SH + 4), cls: 'p-rejoin' });
          }
        });
      });
      maxY = Math.max(maxY, y);
    });
    // a step on the main path that goes round again to an earlier one on it (a loop with its body on the path)
    var seenBack = Object.create(null);
    m.main.forEach(function (id) {
      m.out[id].forEach(function (e) {
        if ((e.label !== 'again' && e.label !== 'rewind') || !m.by[e.to].main || !pos[e.to] || !pos[id] || pos[e.to].x >= pos[id].x) { return; }
        var s = pos[id], t = pos[e.to], y = F.SY + F.SH + 22 + (seenBack[e.to] || 0) * 8;
        seenBack[e.to] = (seenBack[e.to] || 0) + 1;
        paths.push({ d: 'M' + (s.x + s.w / 2) + ' ' + (F.SY + F.SH) + 'V' + y + 'H' + (t.x + t.w / 2 + 12) + 'V' + (F.SY + F.SH + 4), cls: e.label === 'rewind' ? 'p-rewind' : 'p-back' });
        if (e.label === 'rewind') {
          var capMain = (m.by[id].raw.attrs || {}).atMost;
          labels.push({ t: 'sent back' + (capMain ? ' ≤' + capMain : ''), x: (s.x + t.x + t.w) / 2, y: y - 9 });
          maxY = Math.max(maxY, y + 10);
          return;
        }
        var bound = m.by[e.to].raw.bound || (m.by[e.to].raw.attrs || {}).max;
        labels.push({ t: 'again' + (bound ? ' ≤' + bound : (m.endless ? ' forever' : '')), x: (s.x + t.x + t.w) / 2, y: y - 9 });
        maxY = Math.max(maxY, y + 10);
      });
    });
    var bands = [];
    m.sections.forEach(function (s, i) {
      var ids = m.main.filter(function (id) { return m.by[id].section === i; });
      if (!ids.length) { return; }
      var a = pos[ids[0]], b = pos[ids[ids.length - 1]];
      bands.push({ index: i, n: s.n, title: s.title, x: a.x - F.G / 2 + 6, w: b.x + (m.by[ids[ids.length - 1]].vk === 'term' ? F.W : b.w) - a.x + F.G - 12 });
    });
    return { pos: pos, drops: drops, paths: paths, labels: labels, bands: bands, width: F.X0 * 2 + m.main.length * (F.W + F.G), height: maxY + 30 };
  }

  function flow(ctx) {
    var m = ctx.model, L = flowLayout(m), F = FLOW;
    var stage = el('div', { class: 'stage', 'data-export': 'flow' });
    stage.style.width = L.width + 'px'; stage.style.height = L.height + 'px';
    var ns = 'http://www.w3.org/2000/svg', sv = document.createElementNS(ns, 'svg');
    sv.setAttribute('class', 'fpaths'); sv.setAttribute('width', L.width); sv.setAttribute('height', L.height); sv.setAttribute('viewBox', '0 0 ' + L.width + ' ' + L.height); sv.setAttribute('aria-hidden', 'true');
    L.paths.forEach(function (p) { var path = document.createElementNS(ns, 'path'); path.setAttribute('d', p.d); path.setAttribute('class', p.cls); sv.appendChild(path); });
    stage.appendChild(sv);
    var sel = m.by[ctx.selected];
    L.bands.forEach(function (b) {
      var band = el('div', { class: 'band' + (sel && sel.section === b.index ? ' on' : ''), 'data-section': String(b.index) }, el('span', { class: 'bn', text: String(b.n) }), el('span', { class: 'bt', text: b.title }));
      band.style.left = px(b.x); band.style.width = px(b.w); band.style.top = '12px';
      stage.appendChild(band);
    });
    function place(n, variant) {
      var p = L.pos[n.id], c = card(ctx, n, variant);
      c.style.left = px(p.x); c.style.top = px(p.y); c.style.width = px(p.w); c.style.height = px(p.h);
      stage.appendChild(c);
    }
    m.main.forEach(function (id) { place(m.by[id], 'flow'); });
    L.drops.forEach(function (id) { place(m.by[id], 'drop'); });
    L.labels.forEach(function (l) { var chip = el('span', { class: 'edgechip abs', text: l.t }); chip.style.left = px(l.x); chip.style.top = px(l.y); stage.appendChild(chip); });
    var lines = lineLegend(M.lineKinds(m));
    if (lines) { lines.classList.add('inplace'); stage.appendChild(lines); }
    return el('div', { class: 'flow dots', tabindex: '-1' }, stage);
  }

  /* -------------------------------------------------------------- the rail */

  function rail(ctx, parts) {
    var m = ctx.model, sel = m.by[ctx.selected];
    var anchorId = sel ? sel.anchor : null, at = anchorId ? m.main.indexOf(anchorId) : -1;
    parts.note.textContent = sel ? M.railNote(m, sel.id) : '';
    var bar = parts.bar; while (bar.firstChild) { bar.removeChild(bar.firstChild); }
    m.main.forEach(function (id, i) {
      var n = m.by[id], seg = el('button', { type: 'button', class: 'seg ' + (i < at ? 'done' : i === at ? 'now' : 'todo'), 'data-id': id, title: n.label, 'aria-label': 'Step ' + (i + 1) + ': ' + n.label, onclick: function () { ctx.on.select(id, true); } });
      bar.appendChild(seg);
    });
    var secs = parts.sections; while (secs.firstChild) { secs.removeChild(secs.firstChild); }
    if (m.hasSections) {
      m.sections.forEach(function (s, i) {
        if (!s.main.length) { return; }
        var b = el('button', { type: 'button', class: 'sec' + (sel && sel.section === i ? ' on' : ''), text: s.n + ' ' + s.title, onclick: function () { ctx.on.select(s.main[0], true); } });
        b.style.flexGrow = String(s.main.length);
        secs.appendChild(b);
      });
    }
  }

  function legend(box, model) {
    while (box.firstChild) { box.removeChild(box.firstChild); }
    M.LEGEND.forEach(function (vk) { box.appendChild(el('span', { class: 'lg' }, badge(vk, 16), el('span', { text: VK[vk].name }))); });
    var lines = model ? lineLegend(M.lineKinds(model)) : null;
    if (lines) { box.appendChild(lines); }
  }

  /** The key to the intervention lines a graph has: a sample of each line's pattern, its name, and what it means. Null when it has none. */
  function lineLegend(kinds) {
    if (!kinds.length) { return null; }
    var ns = 'http://www.w3.org/2000/svg', box = el('span', { class: 'linelegend', 'aria-label': 'Lines for interventions' });
    kinds.forEach(function (k) {
      var sample = document.createElementNS(ns, 'svg');
      sample.setAttribute('width', '34'); sample.setAttribute('height', '10'); sample.setAttribute('viewBox', '0 0 34 10'); sample.setAttribute('aria-hidden', 'true');
      var line = document.createElementNS(ns, 'path'); line.setAttribute('d', 'M1 5H33'); line.setAttribute('class', 'sample p-' + k); sample.appendChild(line);
      box.appendChild(el('span', { class: 'lg line-' + k, title: M.LINES[k].means }, sample, el('span', { text: M.LINES[k].name })));
    });
    return box;
  }

  /* ------------------------------------------------------------- breadcrumb */

  /** The crumbs for a selected step: [{text, mono?, act}] where act is a descriptor the panel turns into a click. */
  function crumbs(model, selectedId, workflowLabel, fileName, stack) {
    var out = [{ text: fileName, mono: true, act: { file: true } }];
    (stack || []).forEach(function (name, i) { out.push({ text: name, act: { pop: i } }); });
    out.push({ text: workflowLabel, act: { workflow: true } });
    var sel = model.by[selectedId];
    if (!sel) { return out; }
    if (model.hasSections) {
      var s = model.sections[sel.section];
      out.push({ text: s.n + ' · ' + s.title, act: { select: (s.main[0] || sel.id) } });
    }
    var chain = [], p = sel, hops = 0;
    while (p && hops++ < 200) { chain.unshift(p); p = p.parent ? model.by[p.parent] : null; }
    chain.forEach(function (n) { out.push({ text: n.label, act: { select: n.id } }); });
    return out;
  }

  /* ------------------------------------------------------------------- export */

  var COPIED = ['color', 'background-color', 'background-image', 'background-size', 'background-position', 'border-top', 'border-right', 'border-bottom', 'border-left', 'border-radius', 'box-shadow', 'box-sizing',
    'display', 'position', 'left', 'top', 'right', 'bottom', 'width', 'height', 'min-width', 'max-width', 'min-height', 'max-height', 'margin', 'padding', 'overflow', 'text-overflow', 'white-space',
    'flex', 'flex-direction', 'flex-wrap', 'align-items', 'align-self', 'justify-content', 'gap', 'grid-template-columns', 'grid-template-rows', 'grid-column', 'place-items',
    'font-family', 'font-size', 'font-weight', 'font-style', 'letter-spacing', 'line-height', 'text-transform', 'text-align', 'text-decoration', 'opacity', 'transform', 'transform-origin',
    'fill', 'stroke', 'stroke-width', 'stroke-dasharray', 'stroke-linecap', 'stroke-linejoin', 'vertical-align', 'visibility', 'z-index', 'word-break', 'overflow-wrap'];

  function inlineStyles(src, dst) {
    var cs = window.getComputedStyle(src), css = '';
    COPIED.forEach(function (p) { css += p + ':' + cs.getPropertyValue(p) + ';'; });
    dst.style.cssText = css;
    for (var i = 0; i < src.children.length; i++) { inlineStyles(src.children[i], dst.children[i]); }
  }

  /** Draws the element as it looks now (its whole size, not only what is scrolled into view) to a PNG data URL. */
  function snapshot(element, background, scale) {
    return new Promise(function (resolve, reject) {
      var rect = element.getBoundingClientRect();
      var w = Math.ceil(Math.max(element.scrollWidth, rect.width)), h = Math.ceil(Math.max(element.scrollHeight, rect.height));
      var pad = 16, clone = element.cloneNode(true);
      inlineStyles(element, clone);
      clone.style.margin = '0';
      var xhtml = new XMLSerializer().serializeToString(clone);
      var markup = '<svg xmlns="http://www.w3.org/2000/svg" width="' + (w + pad * 2) + '" height="' + (h + pad * 2) + '"><foreignObject x="' + pad + '" y="' + pad + '" width="' + w + '" height="' + h + '">' +
        '<div xmlns="http://www.w3.org/1999/xhtml">' + xhtml + '</div></foreignObject></svg>';
      var img = new Image();
      img.onload = function () {
        try {
          var canvas = document.createElement('canvas');
          canvas.width = (w + pad * 2) * scale; canvas.height = (h + pad * 2) * scale;
          var g = canvas.getContext('2d');
          g.fillStyle = background; g.fillRect(0, 0, canvas.width, canvas.height);
          g.scale(scale, scale); g.drawImage(img, 0, 0);
          resolve(canvas.toDataURL('image/png'));
        } catch (e) { reject(e); }
      };
      img.onerror = function () { reject(new Error('the picture could not be drawn')); };
      img.src = 'data:image/svg+xml;charset=utf-8,' + encodeURIComponent(markup);
    });
  }

  /* ------------------------------------------------------------------ render */

  function render(ctx) {
    if (ctx.view === 'focus') { return focus(ctx); }
    if (ctx.view === 'flow') { return el('div', { class: 'flowwrap' }, flow(ctx), inspector(ctx)); }
    return outline(ctx);
  }

  /** Puts the selected step in view (Flow: in the middle; Outline: its row and its card). */
  function reveal(root, ctx) {
    var id = ctx.selected;
    if (!id) { return; }
    if (ctx.view === 'flow') {
      var scroller = root.querySelector('.flow'), c = scroller && root.querySelector('.stage .card[data-id="' + String(id).replace(/"/g, '') + '"]');
      if (scroller && c) {
        scroller.scrollLeft = Math.max(0, c.offsetLeft + c.offsetWidth / 2 - scroller.clientWidth / 2);
        scroller.scrollTop = Math.max(0, c.offsetTop + c.offsetHeight / 2 - scroller.clientHeight / 2);
      }
      return;
    }
    if (ctx.view === 'outline') {
      var row = root.querySelector('.row[data-id="' + String(id).replace(/"/g, '') + '"]'), tree = root.querySelector('.tree');
      if (row && tree) {
        if (row.offsetTop < tree.scrollTop + 30) { tree.scrollTop = Math.max(0, row.offsetTop - 60); }
        else if (row.offsetTop + row.offsetHeight > tree.scrollTop + tree.clientHeight) { tree.scrollTop = row.offsetTop - tree.clientHeight + row.offsetHeight + 30; }
      }
      var centre = root.querySelector('.centre'), big = centre && root.querySelector('.stack .card[data-id="' + String(id).replace(/"/g, '') + '"]');
      if (centre && big) {
        var c = centre.getBoundingClientRect(), b = big.getBoundingClientRect();
        if (b.top < c.top + 8) { centre.scrollTop += b.top - c.top - 24; }
        else if (b.bottom > c.bottom - 8) { centre.scrollTop += b.bottom - c.bottom + 24; }
      }
    }
  }

  return { render: render, reveal: reveal, rail: rail, legend: legend, lineLegend: lineLegend, crumbs: crumbs, snapshot: snapshot, flowLayout: flowLayout, FLOW: FLOW, GLYPHS: GLYPHS, svg: svg, el: el, badge: badge };
});
