'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
require('../../main/resources/io/github/llm4j/evalreport/render/graph-render.js');
const Card = require('../../main/resources/io/github/llm4j/evalreport/render/graph-card.js');

const node = (id, kind, extra) => Object.assign({ id, kind, label: id }, extra);
const workflow = (extra) => Object.assign({
  name: 'W',
  graph: { nodes: [node('start', 'start'), node('n1', 'delegate', { label: 'delegate A', agent: 'A' }), node('end', 'end')], edges: [{ from: 'start', to: 'n1' }, { from: 'n1', to: 'end' }] },
  expectedPath: ['start', 'n1', 'end'], actualPath: ['start', 'n1', 'end'], events: [], spend: []
}, extra);

test('there is no card without graph nodes, a note above 500 nodes, and a graph otherwise', () => {
  assert.deepEqual(Card.plan({}), { kind: 'none' });
  assert.deepEqual(Card.plan({ graph: { nodes: [] } }), { kind: 'none' });
  const big = { graph: { nodes: Array.from({ length: 501 }, (_, i) => node('n' + i, 'note')) }, actualPath: [] };
  assert.deepEqual(Card.plan(big), { kind: 'toolarge', nodes: 501 });
  assert.equal(Card.plan({ graph: { nodes: Array.from({ length: 500 }, (_, i) => node('n' + i, 'note')) }, actualPath: ['n1'] }).kind, 'graph');
  assert.equal(Card.plan(workflow()).empty, false);
  assert.equal(Card.plan(workflow({ actualPath: [] })).empty, true);
});

test('model names come from the spend lines, first one wins', () => {
  const w = workflow({ spend: [{ agent: 'A', model: 'gpt-4' }, { agent: 'A', model: 'other' }, { agent: 'B' }] });
  assert.deepEqual(Card.agentsFromSpend(w), { A: { model: 'gpt-4' } });
});

test('delegations that no step could be found for are counted', () => {
  const w = workflow({ events: [{ t: 0, type: 'delegate_start', node: 'n1' }, { t: 1, type: 'delegate_start' }, { t: 2, type: 'delegate_replayed', node: null }, { t: 3, type: 'thought' }] });
  assert.equal(Card.unplacedCount(w), 2);
});

test('a step shows exactly the events mapped to it, how long it took, and its agent\'s spend labelled per agent', () => {
  const w = workflow({
    events: [{ t: 0, type: 'delegate_start', node: 'n1', text: 'go' }, { t: 1.5, type: 'observation', node: 'n1', text: 'saw' }, { t: 4.2, type: 'delegate_end', node: 'n1', text: 'ok' }, { t: 9, type: 'delegate_start', node: 'other' }],
    spend: [{ agent: 'A', promptTokens: 100, completionTokens: 50, calls: 2, costUsd: 0.5 }, { agent: 'A', promptTokens: 10, completionTokens: 5, calls: 1, costUsd: 0.25, estimated: true }, { agent: 'B', promptTokens: 1, completionTokens: 1, calls: 1 }]
  });
  const d = Card.details(w, 'n1');
  assert.equal(d.events.length, 3);
  assert.deepEqual(d.events.map((e) => e.type), ['delegate_start', 'observation', 'delegate_end']);
  assert.ok(Math.abs(d.duration - 4.2) < 1e-9);
  assert.equal(d.spend.length, 1);
  assert.deepEqual(d.spend[0], { agent: 'A', promptTokens: 110, completionTokens: 55, calls: 3, costUsd: 0.75, estimated: true });
});

test('duration is unknown without both a start and an end, and a step with no agent has no spend', () => {
  const only = workflow({ events: [{ t: 0, type: 'delegate_start', node: 'n1' }] });
  assert.equal(Card.details(only, 'n1').duration, null);
  assert.equal(Card.details(workflow(), 'start').spend.length, 0);
  assert.equal(Card.details(workflow(), 'missing'), null);
});

test('a parallel step lists the spend of each of its agents', () => {
  const w = workflow({
    graph: { nodes: [node('p', 'parallel', { attrs: { agents: ['A', 'B'] } })], edges: [] },
    spend: [{ agent: 'A', promptTokens: 1, completionTokens: 1, calls: 1 }, { agent: 'B', promptTokens: 2, completionTokens: 2, calls: 1 }]
  });
  assert.deepEqual(Card.details(w, 'p').spend.map((s) => s.agent), ['A', 'B']);
});

test('the overlay is built from the expected and actual path of the trace', () => {
  const w = workflow({ expectedPath: ['start', 'n1', 'end'], actualPath: ['start', 'end'] });
  const o = Card.overlayOf(w);
  assert.equal(o.states.n1, 'missed');
  assert.equal(o.states.start, 'ok');
});

test('the card module makes no network calls and has no imports', () => {
  const src = require('fs').readFileSync(require.resolve('../../main/resources/io/github/llm4j/evalreport/render/graph-card.js'), 'utf8');
  assert.ok(!/\bfetch\(|XMLHttpRequest|WebSocket|eval\(|innerHTML|insertAdjacentHTML|document\.write/.test(src), 'no network, no HTML parsing of model text');
  assert.ok(!/\brequire\(/.test(src));
});
