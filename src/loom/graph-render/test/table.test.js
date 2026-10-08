'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const G = require('../graph-render.js');
const { golden, workflow, agents } = require('./fixtures.js');

/*
 * V6.8: every primitive and attribute is drawn as ui.md section 3 says. Each row is a statement of the fixture
 * all_statements.loom (one of every kind, with attributes), and what its node must show: title, subtitle, chips
 * (three at most, then +n), and the shape the kind is drawn with.
 */
const TABLE = [
  ['n1', 'checkpoint', 'checkpoint collected', null, ['2 values'], 'flag'],
  ['n2', 'delegate', 'delegate Researcher', 'Researcher · gpt-4', ['timeout 90s', 'retry 3 · 2s', 'budget 5k tok', '+1'], 'rect'],
  ['n3', 'task', 'run RefundPolicy', '1 arg', ['timeout 30s', 'retry 2', '→ verdict'], 'rect'],
  ['n4', 'broadcast', 'broadcast', 'Critic, Copywriter', ['budget 3k tok', '→ reviews'], 'rect'],
  ['n5', 'parallel', 'in parallel: Copywriter', 'Copywriter', ['2 branches'], 'bar'],
  ['n6', 'human_prompt', 'ask a person', 'Approve?', ['→ approval'], 'rect'],
  ['n7', 'alt', 'approval==yes?', null, [], 'hex'],
  ['n8', 'note', 'note', 'Approved', [], 'rect'],
  ['n9', 'observe', 'observe Rejected', 'approval', [], 'rect'],
  ['n10', 'loop', 'loop until done==yes', null, ['max 3', 'budget 2k tok'], 'round'],
  ['n11', 'delegate', 'delegate Copywriter', 'Copywriter · gpt-4', ['→ draft'], 'rect'],
  ['n12', 'foreach', 'for each doc in docs', null, [], 'round'],
  ['n14', 'foreach', 'for each doc in docs', null, ['∥ parallel', 'budget 800 tok'], 'round'],
  ['n16', 'guardrail', 'guardrail PII', null, [], 'rect'],
  ['n18', 'decide', 'decide Refund', null, ['WATCH'], 'rect'],
  ['n19', 'call', 'call Helper', null, ['1 arg', '→ helped'], 'dbl'],
  ['n20', 'delegate', 'delegate Critic', 'Critic · gpt-4', ['expects {score, notes}', '→ review'], 'rect'],
  ['n21', 'rewind', 'rewind to collected', 'review.score<7', ['≤ 2 times', 'ask first', 'carrying 1'], 'rect'],
  ['n22', 'handoff', 'handoff Critic', 'Critic · gpt-4', [], 'rect']
];

const result = golden('all_statements');
const wf = workflow(result, 'Everything');
const ctx = { agents: agents(result), currentFile: wf.file };

for (const [id, kind, title, sub, chips, shape] of TABLE) {
  test(`${id} ${kind}: title, subtitle, chips and shape follow the table`, () => {
    const node = wf.nodes.find((n) => n.id === id);
    assert.equal(node.kind, kind);
    assert.equal(G.title(node), title);
    assert.equal(G.subtitle(node, ctx), sub);
    const drawn = G.layout({ nodes: [node], edges: [] }, ctx).nodes[0];
    assert.deepEqual(drawn.chips.map((c) => c.t), chips);
    assert.ok(drawn.chips.length <= 4, 'three chips and a +n at most');
    assert.equal(G.kinds[kind].shape, shape);
  });
}

test('every statement kind in the fixture is covered by the table', () => {
  const covered = new Set(TABLE.map((row) => row[1]));
  const present = new Set(wf.nodes.map((n) => n.kind).filter((k) => k !== 'start' && k !== 'end'));
  assert.deepEqual([...present].sort(), [...covered].sort());
});

test('the budget chip uses the warning style and an unbounded loop is flagged', () => {
  const chips = G.chips(wf.nodes.find((n) => n.id === 'n2'));
  assert.equal(chips.find((c) => c.t.startsWith('budget')).v, 'budget');
  assert.equal(G.chips({ id: 'x', kind: 'loop', label: 'loop' })[0].v, 'warn');
});

test('handlers are drawn as steps of their own, with the owner as parent', () => {
  const handler = wf.nodes.find((n) => n.id === 'n23');
  assert.equal(handler.parent, 'n10');
  assert.equal(handler.branch, 'exhausted');
  const lay = G.layout({ nodes: wf.nodes, edges: wf.edges }, ctx);
  assert.ok(lay.routes.some((r) => r.label === 'exhausted'));
  assert.ok(lay.routes.some((r) => r.label === 'violation'));
  assert.ok(lay.routes.some((r) => r.label === 'rewind' && r.back));
});
