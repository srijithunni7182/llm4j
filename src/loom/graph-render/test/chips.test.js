'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const G = require('../graph-render.js');

const node = (kind, attrs, extra) => Object.assign({ id: 'n1', kind, label: kind, attrs }, extra);
const texts = (n) => G.chips(n).map((c) => c.t);

test('durations print as the shortest whole unit', () => {
  assert.equal(G.duration(500), '500ms');
  assert.equal(G.duration(2000), '2s');
  assert.equal(G.duration(180000), '3m');
  assert.equal(G.duration(3600000), '1h');
  assert.equal(G.duration(1500), '1.5s');
});

test('a delegate shows timeout, retry with backoff, budget and the variable, most important first', () => {
  const n = node('delegate', { variable: 'research', retry: 3, backoffMs: 2000, timeoutMs: 90000, budget: { tokens: 5000 }, expecting: '{score, notes}' });
  assert.deepEqual(texts(n), ['timeout 90s', 'retry 3 · 2s', 'budget 5k tok', 'expects {score, notes}', '→ research']);
  assert.equal(G.chips(n)[2].v, 'budget');
});

test('retry without backoff is just the count', () => {
  assert.deepEqual(texts(node('delegate', { retry: 2 })), ['retry 2']);
});

test('a run shows timeout, retry and its variable and never a budget', () => {
  const n = node('task', { task: 'RefundPolicy', variable: 'verdict', args: 2, retry: 2, timeoutMs: 30000, budget: { tokens: 1 } });
  assert.deepEqual(texts(n), ['timeout 30s', 'retry 2', '→ verdict']);
});

test('budget shows the first limit that is set and a plus when there are more', () => {
  assert.equal(G.budgetText({ tokens: 5000 }), 'budget 5k tok');
  assert.equal(G.budgetText({ calls: 20 }), 'budget 20 calls');
  assert.equal(G.budgetText({ cost: '0.50' }), 'budget $0.50');
  assert.equal(G.budgetText({ perCall: 2000 }), 'budget 2k/call');
  assert.equal(G.budgetText({ tokens: 1500, calls: 20, cost: '1.00' }), 'budget 1.5k tok +');
  assert.equal(G.budgetText({ tokens: 2000000 }), 'budget 2M tok');
  assert.equal(G.budgetText({}), null);
});

test('a loop shows its bound, or a warning when it has none', () => {
  assert.deepEqual(texts(node('loop', { budget: { cost: '0.50' } }, { bound: 3 })), ['max 3', 'budget $0.50']);
  const unbounded = G.chips(node('loop', {}));
  assert.deepEqual(unbounded, [{ t: 'no max', v: 'warn' }]);
});

test('a for each shows parallel and its budget; a plain one shows nothing', () => {
  assert.deepEqual(texts(node('foreach', { parallel: true, budget: { tokens: 800 } })), ['∥ parallel', 'budget 800 tok']);
  assert.deepEqual(texts(node('foreach', {})), []);
});

test('rewind shows its limit, its effects (muted when it is the default) and what it carries', () => {
  const chips = G.chips(node('rewind', { atMost: 2, effects: 'ask first', carrying: { feedback: 'x' } }));
  assert.deepEqual(chips.map((c) => c.t), ['≤ 2 times', 'ask first', 'carrying 1']);
  assert.equal(chips[1].v, 'muted');
  assert.deepEqual(texts(node('rewind', { atMost: 1, effects: 'keep' })), ['≤ 1 time', 'keep']);
  assert.equal(G.chips(node('rewind', { atMost: 1, effects: 'keep' }))[1].v, undefined);
});

test('checkpoint, call, decide, human prompt and parallel show their own few chips', () => {
  assert.deepEqual(texts(node('checkpoint', { startingWith: { a: '1', b: '2' } })), ['2 values']);
  assert.deepEqual(texts(node('checkpoint', { startingWith: { a: '1' } })), ['1 value']);
  assert.deepEqual(texts(node('call', { args: 1, variable: 'revised' })), ['1 arg', '→ revised']);
  assert.deepEqual(texts(node('decide', { level: 'suggest' })), ['SUGGEST']);
  assert.deepEqual(texts(node('human_prompt', { variable: 'approval' })), ['→ approval']);
  assert.deepEqual(texts(node('parallel', { branches: 3 })), ['3 branches']);
  assert.deepEqual(texts(node('parallel', { branches: 1 })), ['1 branch']);
});

test('kinds with no attributes have no chips, and unset attributes make none', () => {
  ['handoff', 'alt', 'guardrail', 'observe', 'note', 'unknown', 'start', 'end'].forEach((k) => assert.deepEqual(texts(node(k, { text: 'x' })), []));
  assert.deepEqual(texts(node('delegate', {})), []);
  assert.deepEqual(G.chips({ id: 'n', kind: 'delegate', label: 'd' }), []);
});

test('placement and settings may be read from attrs, as the report carries them', () => {
  const g = { nodes: [{ id: 'a', kind: 'alt', label: 'x?' }, { id: 'b', kind: 'note', label: 'note', attrs: { parent: 'a', branch: 'then' } }], edges: [] };
  assert.equal(G.descendantCounts(g).a, 1);
});

test('more than three chips show three and a plus count', () => {
  const lay = G.layout({ nodes: [{ id: 'd', kind: 'delegate', label: 'delegate A', agent: 'A', attrs: { retry: 3, timeoutMs: 1000, budget: { tokens: 5 }, expecting: '{x}', variable: 'v' } }], edges: [] }, {});
  const chips = lay.nodes[0].chips.map((c) => c.t);
  assert.equal(chips.length, 4);
  assert.deepEqual(chips.slice(0, 3), ['timeout 1s', 'retry 3', 'budget 5 tok']);
  assert.equal(chips[3], '+2');
});

test('subtitles follow the table in ui.md', () => {
  const agents = { Researcher: { model: 'gpt-4' } };
  assert.equal(G.subtitle(node('delegate', {}, { agent: 'Researcher' }), { agents }), 'Researcher · gpt-4');
  assert.equal(G.subtitle(node('delegate', {}, { agent: 'Ghost' }), { agents }), 'Ghost');
  assert.equal(G.subtitle(node('handoff', {}, { agent: 'Researcher' }), { agents }), 'Researcher · gpt-4');
  assert.equal(G.subtitle(node('broadcast', { agents: ['A', 'B', 'C', 'D'] })), 'A, B, C +1');
  assert.equal(G.subtitle(node('parallel', { agents: ['A', 'B', 'C', 'D', 'E'] })), 'A, B, C, D +1');
  assert.equal(G.subtitle(node('note', { text: 'x'.repeat(100) })).length, 40);
  assert.equal(G.subtitle(node('human_prompt', { text: 'Approve?' })), 'Approve?');
  assert.equal(G.subtitle(node('rewind', { condition: 'score < 7' })), 'score < 7');
  assert.equal(G.subtitle(node('task', { args: 2 })), '2 args');
  assert.equal(G.subtitle(node('call', {}, { call: { workflow: 'W', file: '/p/primitives.loom' } }), { currentFile: '/p/main.loom' }), 'primitives.loom');
  assert.equal(G.subtitle(node('call', {}, { call: { workflow: 'W', file: '/p/main.loom' } }), { currentFile: '/p/main.loom' }), null);
  assert.equal(G.subtitle(node('call', {}, { call: { workflow: 'W' }, unresolved: true })), 'not found');
  assert.equal(G.subtitle(node('alt', {})), null);
});

test('a step whose agent runs a prompt file shows it first, as id@version', () => {
  const n = node('delegate', { prompt: 'researcher@v2', variable: 'r', retry: 2 });
  assert.deepEqual(texts(n), ['researcher@v2', 'retry 2', '→ r']);
  assert.equal(G.chips(n)[0].v, 'muted');
});

test('a step with no prompt file shows no prompt chip, and a long id is cut', () => {
  assert.deepEqual(texts(node('delegate', { retry: 2 })), ['retry 2']);
  assert.ok(texts(node('delegate', { prompt: 'a-very-long-prompt-identifier@v12' }))[0].length <= 26);
});
