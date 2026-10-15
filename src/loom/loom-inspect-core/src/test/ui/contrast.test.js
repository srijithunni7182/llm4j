'use strict';
/* R17: every colour pair the stylesheet's tokens make meets WCAG 2.x: 4.5:1 for text, 3:1 for large text and for the lines that mark a control. Computed, not eyeballed. */
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('fs');
const path = require('path');

const css = fs.readFileSync(path.join(__dirname, '..', '..', 'main', 'resources', 'io', 'github', 'llm4j', 'loom', 'inspect', 'ui', 'app.css'), 'utf8');

function tokens(selector) {
  const m = new RegExp(selector.replace(/[[\]"]/g, '\\$&') + '\\s*\\{([^}]*)\\}').exec(css);
  const out = {};
  for (const t of m[1].matchAll(/--([a-z-]+):\s*(#[0-9a-f]{6})/gi)) out[t[1]] = t[2];
  return out;
}
const dark = Object.assign({}, tokens(':root, [data-theme="dark"]'));
const light = tokens('[data-theme="light"]');

function lum(hex) {
  const c = [1, 3, 5].map((i) => parseInt(hex.slice(i, i + 2), 16) / 255).map((v) => (v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4)));
  return 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2];
}
function ratio(a, b) {
  const x = lum(a); const y = lum(b);
  return (Math.max(x, y) + 0.05) / (Math.min(x, y) + 0.05);
}

const TEXT = [
  ['ink', ['ground', 'surface', 'surface-raised']],
  ['ink-muted', ['ground', 'surface', 'surface-raised']],
  ['ink-faint', ['ground', 'surface', 'surface-raised']],
  ['live', ['ground', 'surface', 'surface-raised', 'live-soft']],
  ['danger', ['ground', 'surface', 'surface-raised', 'danger-soft']],
  ['wait', ['ground', 'surface', 'surface-raised']],
  ['rung-suggest', ['ground', 'surface', 'surface-raised']],
  ['rung-act', ['ground', 'surface', 'surface-raised']],
  ['rung-watch', ['ground', 'surface']]
];
const LINES = [['line-strong', ['ground', 'surface', 'surface-raised']]];

for (const [name, theme] of [['dark', dark], ['light', light]]) {
  test('R17: text colours of the ' + name + ' theme reach 4.5:1 on every ground they are used on', () => {
    const bad = [];
    for (const [fg, grounds] of TEXT) for (const bg of grounds) if (ratio(theme[fg], theme[bg]) < 4.5) bad.push(fg + ' on ' + bg + ' ' + ratio(theme[fg], theme[bg]).toFixed(2));
    if (ratio(theme['on-live'], theme.live) < 4.5) bad.push('on-live on live ' + ratio(theme['on-live'], theme.live).toFixed(2));
    assert.deepEqual(bad, [], name + ' theme');
  });
  test('R17: the lines that mark a control in the ' + name + ' theme reach 3:1', () => {
    const bad = [];
    for (const [fg, grounds] of LINES) for (const bg of grounds) if (ratio(theme[fg], theme[bg]) < 3) bad.push(fg + ' on ' + bg + ' ' + ratio(theme[fg], theme[bg]).toFixed(2));
    assert.deepEqual(bad, [], name + ' theme');
  });
}
