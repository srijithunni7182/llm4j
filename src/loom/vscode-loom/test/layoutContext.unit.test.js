'use strict';
/* R44, R45, R49 of spec 10 without a page: the layout choice and everything that must survive a change of it. */
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('fs');
const path = require('path');
const R = require('../media/review-model.js');

const FIX = path.join(__dirname, 'fixtures', 'review');
const model = (name) => R.build(JSON.parse(fs.readFileSync(path.join(FIX, name + '.review.json'), 'utf8')), JSON.parse(fs.readFileSync(path.join(FIX, name + '.graph.json'), 'utf8')));

test('R44: a workspace that has never opened the panel starts in Split', () => {
  assert.equal(R.defaultUi().layout, 'split');
  assert.equal(R.restoreUi(null).layout, 'split');
  assert.equal(R.restoreUi({}).layout, 'split');
});

test('R44: the last choice is restored, and a layout that does not exist falls back to Split', () => {
  for (const layout of R.LAYOUTS) { assert.equal(R.restoreUi({ layout }).layout, layout); }
  assert.equal(R.restoreUi({ layout: 'carousel' }).layout, 'split');
});

test('R45: everything that must survive a switch is in the saved state, and comes back exactly', () => {
  const ui = R.defaultUi();
  Object.assign(ui, { layout: 'outline', pane: 'graph', groupBy: 'lens', node: 'n1', selected: 'abc', multi: ['a', 'b'], scroll: 321, showNotEvaluated: true });
  ui.filter = { text: 'retry', lenses: ['reliability'], severities: ['medium'] };
  ui.collapsed = { '0:high': true, '1:security': true };
  const back = R.restoreUi(JSON.parse(JSON.stringify(ui)));
  assert.deepEqual(back, ui);
});

test('R45: switching layout changes the layout and nothing else', () => {
  const ui = R.defaultUi();
  Object.assign(ui, { node: 'n1', selected: 'abc', groupBy: 'rule', multi: ['x'], scroll: 40 });
  ui.filter.text = 'mail';
  ui.collapsed['0:medium'] = true;
  const before = JSON.stringify({ ...ui, layout: 'x' });
  for (const layout of ['outline', 'list', 'split', 'list', 'outline', 'split']) {
    ui.layout = layout;
    assert.equal(JSON.stringify({ ...ui, layout: 'x' }), before);
  }
});

test('saved state of the wrong shape never breaks the page: each bad key falls back by itself', () => {
  const ui = R.restoreUi({ layout: 7, pane: [], groupBy: null, filter: { text: 5, lenses: 'x', severities: ['catastrophic', 'high'] }, node: 3, selected: {}, multi: 'x', collapsed: { a: 1, b: true }, scroll: -4, showNotEvaluated: 'yes' });
  assert.equal(ui.layout, 'split');
  assert.equal(ui.pane, 'both');
  assert.equal(ui.groupBy, 'severity');
  assert.deepEqual(ui.filter, { text: '', lenses: [], severities: ['high'] });
  assert.equal(ui.node, null);
  assert.deepEqual(ui.multi, []);
  assert.deepEqual(ui.collapsed, { b: true });
  assert.equal(ui.scroll, 0);
  assert.equal(ui.showNotEvaluated, false);
});

test('R49: the view of the data does not depend on the layout: one function, no layout argument', () => {
  assert.equal(R.view.length, 2, 'view(model, ui) takes the model and the choices, nothing about layout');
  const m = model('risky');
  const ui = R.defaultUi();
  const results = R.LAYOUTS.map((layout) => { ui.layout = layout; return JSON.stringify(R.view(m, ui)); });
  assert.equal(new Set(results).size, 1, 'the same view for Split, Outline and List');
});

test('R49: the view follows the choices: filter, grouping and the chosen step', () => {
  const m = model('risky');
  const ui = R.defaultUi();
  ui.node = 'n1';
  const v = R.view(m, ui);
  assert.equal(v.shown.length, 4);
  assert.equal(v.selectedNode, 'n1');
  assert.equal(v.total, 10);
  assert.equal(v.filtered, true);
  ui.node = null; ui.filter.severities = ['high']; ui.groupBy = 'lens';
  const high = R.view(m, ui);
  assert.deepEqual(high.counts, { high: 3, medium: 0, low: 0, info: 0 });
  assert.deepEqual(high.groups.map((g) => g.key), ['security', 'validity']);
});

test('R49: the steps the Outline and List list carry the same counts as the model', () => {
  const m = model('risky');
  const steps = R.stepsOf(m, require('../media/graph-model.js').build(m.graph));
  const counted = steps.reduce((n, s) => n + s.total, 0);
  assert.equal(counted, m.findings.filter((f) => f.node).length);
  assert.ok(steps.find((s) => s.id === 'n1').total === 4);
});
