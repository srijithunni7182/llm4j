'use strict';
/*
 * R3, R4, R5, R7, R49 of spec 10: the review model with no DOM. The fixtures are real output of `weave review` and `weave graph`
 * (scripts/regen-editor-fixtures.sh), so the model is tested against what the engine really says.
 */
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('fs');
const path = require('path');
const R = require('../media/review-model.js');

const FIX = path.join(__dirname, 'fixtures', 'review');
const load = (name) => ({ review: JSON.parse(fs.readFileSync(path.join(FIX, name + '.review.json'), 'utf8')), graph: JSON.parse(fs.readFileSync(path.join(FIX, name + '.graph.json'), 'utf8')) });
const model = (name, options) => { const { review, graph } = load(name); return R.build(review, graph, options); };

test('R49: every finding of the engine is in the model, in the engine order, with its own fields', () => {
  const { review } = load('risky');
  const m = model('risky');
  assert.equal(m.findings.length, review.findings.length);
  assert.deepEqual(m.findings.map((f) => f.rule), review.findings.map((f) => f.rule));
  const first = m.findings[0];
  assert.equal(first.id, review.findings[0].fingerprint);
  assert.equal(first.title, review.findings[0].title);
  assert.equal(first.lensName, 'Security');
});

test('R5: the counts by severity are the engine\'s own', () => {
  const { review } = load('risky');
  const m = model('risky');
  assert.deepEqual(m.counts, { high: review.summary.high, medium: review.summary.medium, low: review.summary.low, info: review.summary.info });
  assert.equal(m.summary.countsText, '3 high · 5 medium · 2 low');
  assert.equal(model('clean').summary.countsText, 'no open findings');
});

test('R3: a finding is tied to the step the engine named, or to the step that starts on its line', () => {
  const m = model('risky');
  const lr01 = m.findings.find((f) => f.rule === 'LR01');
  assert.equal(lr01.node, 'n1', 'the engine named n1');
  const la01 = m.findings.find((f) => f.rule === 'LA01');
  assert.equal(la01.node, null, 'a finding on the agent declaration has no step');
  const byLine = R.build({ findings: [{ rule: 'X1', fingerprint: 'a', lens: 'cost', severity: 'low', title: 't', location: { file: 'risky.loom', line: 10, node: null } }] }, load('risky').graph);
  assert.equal(byLine.findings[0].node, 'n1', 'line 10 is where delegate Replier starts');
});

test('R3: a node lists its findings, and counts them by severity', () => {
  const m = model('risky');
  const n1 = m.nodes.n1;
  assert.equal(n1.total, 4);
  assert.deepEqual(n1.counts, { high: 0, medium: 3, low: 1, info: 0 });
  assert.ok(n1.findings.every((id) => m.byId[id].node === 'n1'));
});

test('R4: a step carries the typical and worst cost of its call', () => {
  const m = model('priced');
  const { review } = load('priced');
  const step = review.cost.workflows[0].steps[0];
  const node = m.graph.nodes.find((n) => n.source && n.source.line === step.line);
  const cost = m.nodes[node.id].cost;
  assert.ok(cost);
  assert.equal(R.costText(cost.typical), R.formatMoney(step.typical.money));
  assert.equal(cost.label, step.label);
});

test('R4: without a price table cost is tokens, never a made-up price', () => {
  const m = model('triage');
  assert.equal(m.summary.cost.priced, false);
  assert.match(m.summary.cost.typical, /tok$/);
  assert.deepEqual(m.summary.cost.unpriced.sort(), ['claude-haiku-5-5', 'claude-opus-5-5']);
  const priced = model('priced');
  assert.equal(priced.summary.cost.priced, true);
  assert.equal(priced.summary.cost.typical, '$0.0047');
});

test('R5: the strip has cost, time, effects, lenses and the trifecta state', () => {
  const m = model('risky');
  assert.ok(m.summary.cost.typical && m.summary.cost.worst);
  assert.equal(m.summary.time.typical, '12 s');
  assert.equal(m.summary.time.worst, '40 s');
  assert.equal(m.summary.effects.text, '10 sends');
  assert.equal(m.summary.trifecta, true);
  assert.ok(m.summary.lenses.ran.includes('security'));
  assert.ok(m.summary.lenses.off.includes('governance'));
  assert.equal(model('triage').summary.trifecta, false);
  assert.equal(model('clean').summary.effects.text, 'none');
});

test('R7: rules skipped for facts nobody declared are counted, not hidden', () => {
  const m = model('triage');
  const { review } = load('triage');
  assert.equal(m.summary.notEvaluated, review.notes.filter((n) => n.kind === 'not_evaluated').length);
  assert.ok(m.summary.notEvaluated >= 2);
  assert.ok(m.notEvaluated.map((n) => n.rule).includes('LC01'));
});

test('R7: filters by text (title, rule, lens, file), lens, severity, rule and step', () => {
  const m = model('risky');
  const all = m.findings;
  assert.equal(R.filterFindings(all, {}).length, all.length);
  assert.deepEqual(R.filterFindings(all, { severities: ['high'] }).map((f) => f.severity), ['high', 'high', 'high']);
  assert.ok(R.filterFindings(all, { lenses: ['validity'] }).every((f) => f.lens === 'validity'));
  assert.deepEqual(R.filterFindings(all, { rules: ['LR01'] }).map((f) => f.rule), ['LR01']);
  assert.ok(R.filterFindings(all, { node: 'n1' }).length === 4);
  assert.ok(R.filterFindings(all, { text: 'trifecta' }).some((f) => f.rule === 'LA01'));
  assert.ok(R.filterFindings(all, { text: 'LV09' }).length === 1, 'by rule id');
  assert.ok(R.filterFindings(all, { text: 'privacy' }).every((f) => /privacy/i.test(f.lensName + f.title + f.why + f.rule + f.file + f.fixHint)));
  assert.equal(R.filterFindings(all, { text: 'zzzz-no-such' }).length, 0);
  assert.deepEqual(R.filterFindings(all, { severities: ['high'], lenses: ['validity'] }).map((f) => f.rule), ['LV09', 'LV00']);
});

test('filters keep the engine order', () => {
  const m = model('risky');
  const picked = R.filterFindings(m.findings, { severities: ['medium', 'high'] });
  const indexes = picked.map((f) => f.index);
  assert.deepEqual(indexes, [...indexes].sort((a, b) => a - b));
});

test('R34: grouped by severity, then lens; counts add up; severity order high to info', () => {
  const m = model('risky');
  const groups = R.group(m.findings, 'severity');
  assert.deepEqual(groups.map((g) => g.key), ['high', 'medium', 'low']);
  assert.equal(groups.reduce((n, g) => n + g.count, 0), m.findings.length);
  const high = groups[0];
  assert.deepEqual(high.sub.map((s) => s.key), ['security', 'validity']);
  assert.equal(high.sub.reduce((n, s) => n + s.count, 0), high.count);
});

test('R34: grouped by lens, rule or file', () => {
  const m = model('risky');
  const byLens = R.group(m.findings, 'lens');
  assert.deepEqual(byLens.map((g) => g.key), ['security', 'validity', 'reliability', 'prompts']);
  assert.equal(byLens[0].sub[0].key, 'high', 'inside a lens, worst first');
  assert.equal(R.group(m.findings, 'rule').length, new Set(m.findings.map((f) => f.rule)).size);
  const byFile = R.group(m.findings, 'file');
  assert.ok(byFile.every((g) => g.label));
  assert.equal(byFile.reduce((n, g) => n + g.count, 0), m.findings.length);
});

test('R36: "Fix all safe (n of m)" counts the findings with a safe fix', () => {
  const m = model('risky');
  const c = R.safeCount(m.findings);
  assert.equal(c.total, 10);
  assert.equal(c.safe, m.findings.filter((f) => f.fix && f.fix.safety === 'safe').length);
  assert.ok(R.group(m.findings, 'severity').every((g) => g.safe <= g.count));
});

test('R7: the lens rail lists every lens in the engine order, on or off, with its count', () => {
  const rail = R.lensRail(model('risky'));
  assert.deepEqual(rail.map((l) => l.id), ['security', 'validity', 'cost', 'reliability', 'prompts', 'observability', 'durability', 'testability', 'privacy', 'governance', 'maintainability', 'oversight', 'blast-radius'].filter((l) => rail.some((r) => r.id === l)));
  assert.equal(rail.find((l) => l.id === 'security').count, model('risky').findings.filter((f) => f.lens === 'security').length);
  assert.equal(rail.find((l) => l.id === 'security').count, 4);
  assert.equal(rail.find((l) => l.id === 'governance').on, false);
});

test('R3: the cursor on a line finds the findings and the step', () => {
  const m = model('risky');
  assert.deepEqual(R.findingsOnLine(m, 'risky.loom', 10).map((id) => m.byId[id].rule).sort(), ['LP07', 'LR01', 'LR02', 'LV00']);
  assert.equal(R.nodeOnLine(m, 'risky.loom', 10), 'n1');
  assert.equal(R.nodeOnLine(m, 'risky.loom', 11), 'n2', 'line 11 is the return');
  assert.equal(R.nodeOnLine(m, 'risky.loom', 40), 'n2', 'after the last step starts, the cursor is still in the last one');
  assert.equal(R.nodeOnLine(m, 'risky.loom', 1), null, 'before any step');
});

test('R41: a finding is described for a screen reader with severity, lens and whether it has a fix', () => {
  const m = model('risky');
  const lr01 = m.findings.find((f) => f.rule === 'LR01');
  assert.match(R.describe(lr01), /^Medium, Reliability, (safe fix available|fix to review available): /);
  assert.match(R.describe(m.findings.find((f) => f.rule === 'LA01')), /no automatic fix/);
});

test('hostile text stays data: nothing in the model changes a title, and the model builds no markup', () => {
  const hostile = '<img src=x onerror=alert(1)>‮ RTL \u0000' + 'x'.repeat(10000);
  const m = R.build({ findings: [{ rule: 'LA01', fingerprint: 'h', lens: 'security', severity: 'high', title: hostile, why: hostile, location: { file: '<b>.loom', line: 3 } }] }, null);
  assert.equal(m.findings[0].title, hostile);
  assert.equal(m.findings[0].file, '<b>.loom');
});

test('numbers: money, tokens and time read the way a person says them', () => {
  assert.equal(R.formatMoney(0.0042), '$0.0042');
  assert.equal(R.formatMoney(0.061), '$0.061');
  assert.equal(R.formatMoney(3.5), '$3.50');
  assert.equal(R.formatMoney(null), null);
  assert.equal(R.formatTokens(455), '455 tok');
  assert.equal(R.formatTokens(1800), '1.8k tok');
  assert.equal(R.formatTokens(2718000), '2718k tok');
  assert.equal(R.formatMs(900), '900 ms');
  assert.equal(R.formatMs(1800), '1.8 s');
  assert.equal(R.formatMs(41000), '41 s');
  assert.equal(R.formatMs(720000), '12 min');
});

test('a review that has not arrived yet builds an empty model, not an error', () => {
  const m = R.build(null, null);
  assert.equal(m.findings.length, 0);
  assert.equal(m.workflow, null);
  assert.equal(m.summary.countsText, 'no open findings');
});

test('300 findings are grouped and filtered in a few milliseconds', () => {
  const m = model('many');
  const start = process.hrtime.bigint();
  for (let i = 0; i < 20; i++) { R.group(R.filterFindings(m.findings, { text: 'agent' }), 'severity'); }
  const ms = Number(process.hrtime.bigint() - start) / 1e6 / 20;
  assert.ok(m.findings.length >= 200);
  assert.ok(ms < 50, 'each pass took ' + ms.toFixed(1) + ' ms');
});

test('a workflow can be chosen by name; unknown names fall back to the first', () => {
  const { review, graph } = load('triage');
  assert.equal(R.build(review, graph, { workflow: 'Main' }).workflow, 'Main');
  assert.equal(R.build(review, graph, { workflow: 'Nope' }).workflow, 'Main');
});
