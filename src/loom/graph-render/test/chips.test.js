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

test('a sleep is drawn with the clock glyph and its duration, not as an unknown step', () => {
  const g = { nodes: [
    { id: 'start', kind: 'start', label: 'Start' },
    { id: 'n1', kind: 'sleep', label: 'sleep 5m', attrs: { duration: '5m' } },
    { id: 'end', kind: 'end', label: 'End' }],
    edges: [{ from: 'start', to: 'n1' }, { from: 'n1', to: 'end' }] };
  const svg = G.svgText(g, {});
  assert.ok(svg.includes('sleep 5m'), 'the label is drawn');
  assert.ok(svg.includes('>5m<'), 'the duration is the subtitle');
  assert.ok(svg.includes('M8 4.8V8l2.2 1.4'), 'the clock glyph is used');
  assert.ok(!svg.includes('M8 5.5v3M8 10.6v.2'), 'the unknown glyph is not');
  assert.deepEqual(G.chips(g.nodes[1]), [], 'a sleep has no chips');
});

test('a classify node says whether its answers are calibrated', () => {
  const jev = node('classify', { backend: 'jev', calibrated: true, samples: 3, variable: 'intent' });
  assert.deepEqual(texts(jev), ['Jev · calibrated', '3 samples', '→ intent']);
  const chat = node('classify', { backend: 'chat', uncalibrated: true });
  assert.deepEqual(texts(chat), ['chat model · uncalibrated']);
  assert.equal(G.chips(chat)[0].v, 'warn');
});

test('a step that sends files shows their kinds first, grouped, because it changes what the model is given', () => {
  assert.deepEqual(texts(node('delegate', { attaches: ['image', 'document'], retry: 1 })), ['attaches image, document', 'retry 1']);
  assert.deepEqual(texts(node('delegate', { attaches: ['image', 'image', 'document'] })), ['attaches 2 images, document']);
});

test('broadcast, handoff and extract can show it too', () => {
  assert.deepEqual(texts(node('broadcast', { attaches: ['audio'], variable: 'out' })), ['attaches audio', '→ out']);
  assert.deepEqual(texts(node('handoff', { attaches: ['video'] })), ['attaches video']);
  assert.deepEqual(texts(node('extract', { attaches: ['image'], retry: 2 })), ['attaches image', 'retry 2']);
});

test('a step that sends no files shows no such chip', () => {
  assert.deepEqual(texts(node('delegate', { retry: 1 })), ['retry 1']);
  assert.deepEqual(texts(node('delegate', { attaches: [] })), []);
});

test('a question that shows a person a file says so', () => {
  assert.deepEqual(texts(node('human_prompt', { attaches: ['image'], variable: 'answer' })), ['attaches image', '→ answer']);
});

test('a remote agent says it is outside your budgets, who answers it, and its deadline', () => {
  const n = node('remote', { remote: 'Supplier', host: 'supplier.example.com', channel: 'Desk', audience: 'staff', variable: 'quote', timeoutMs: 7200000, retry: 2, outsideBudgets: true });
  assert.deepEqual(texts(n), ['outside your budgets', 'answered by staff', 'timeout 2h', 'retry 2', '→ quote']);
  assert.equal(G.chips(n)[0].v, 'warn');
});

test('a channel nobody staffs is flagged, and a remote shows its host and channel as the subtitle', () => {
  const auto = node('ask', { channel: 'Auto', audience: 'automatic' });
  assert.equal(G.chips(auto)[0].t, 'answered by no person');
  assert.equal(G.chips(auto)[0].v, 'warn');
  assert.equal(G.subtitle(node('remote', { host: 'supplier.example.com', channel: 'Desk' })), 'supplier.example.com · via Desk');
  assert.equal(G.subtitle(auto), 'to Auto');
});

test('remote and ask have their own look: a remote is drawn dashed, apart from a local agent', () => {
  assert.equal(G.kinds.remote.dashed, true);
  assert.equal(G.kinds.ask.k, 'human');
});

test('a started workflow, its wait and the dotted handle edge are drawn, not shown as unknown steps', () => {
  const g = { nodes: [
    { id: 'start', kind: 'start', label: 'Start' },
    { id: 'n1', kind: 'child_start', label: 'start Research', attrs: { workflow: 'Research', handle: 'work' } },
    { id: 'n2', kind: 'child_wait', label: 'wait work', attrs: { handle: 'work' } },
    { id: 'end', kind: 'end', label: 'End' }],
    edges: [{ from: 'start', to: 'n1' }, { from: 'n1', to: 'n2' }, { from: 'n1', to: 'n2', label: 'handle' }, { from: 'n2', to: 'end' }] };
  const svg = G.svgText(g, {});
  assert.ok(svg.includes('start Research') && svg.includes('wait work'));
  assert.ok(svg.includes('>handle work<'), 'the handle is the subtitle of the start');
  assert.ok(svg.includes('lg-e-handle'), 'the handle edge is drawn dotted');
  assert.ok(svg.includes('Handle: from a start'), 'and the key explains it');
});
