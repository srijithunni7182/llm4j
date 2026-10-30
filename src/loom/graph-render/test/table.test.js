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
  ['n3', 'classify', 'classify Intent', 'kind: refund | order_status | complaint | o…', ['Jev · calibrated', 'timeout 30s', 'retry 2', '+1'], 'rect'],
  ['n4', 'task', 'run RefundPolicy', '1 arg', ['timeout 30s', 'retry 2', '→ verdict'], 'rect'],
  ['n5', 'broadcast', 'broadcast', 'Critic, Copywriter', ['budget 3k tok', '→ reviews'], 'rect'],
  ['n6', 'parallel', 'in parallel: Copywriter', 'Copywriter', ['2 branches'], 'bar'],
  ['n7', 'human_prompt', 'ask a person', 'Approve?', ['→ approval'], 'rect'],
  ['n8', 'alt', 'approval==yes?', null, [], 'hex'],
  ['n9', 'note', 'note', 'Approved', [], 'rect'],
  ['n10', 'observe', 'observe Rejected', 'approval', [], 'rect'],
  ['n11', 'loop', 'loop until done==yes', null, ['max 3', 'budget 2k tok'], 'round'],
  ['n12', 'delegate', 'delegate Copywriter', 'Copywriter · gpt-4', ['→ draft'], 'rect'],
  ['n13', 'foreach', 'for each doc in docs', null, [], 'round'],
  ['n14', 'delegate', 'delegate Researcher', 'Researcher · gpt-4', ['→ out'], 'rect'],
  ['n15', 'foreach', 'for each doc in docs', null, ['∥ parallel', 'budget 800 tok'], 'round'],
  ['n16', 'delegate', 'delegate Researcher', 'Researcher · gpt-4', ['→ s'], 'rect'],
  ['n17', 'guardrail', 'guardrail PII', null, [], 'rect'],
  ['n18', 'note', 'note', 'inside', [], 'rect'],
  ['n19', 'decide', 'decide Refund', null, ['WATCH'], 'rect'],
  ['n20', 'call', 'call Helper', null, ['1 arg', '→ helped'], 'dbl'],
  ['n21', 'delegate', 'delegate Critic', 'Critic · gpt-4', ['expects {score, notes}', '→ review'], 'rect'],
  ['n22', 'rewind', 'rewind to collected', 'review.score<7', ['≤ 2 times', 'ask first', 'carrying 1'], 'rect'],
  ['n23', 'handoff', 'handoff Critic', 'Critic · gpt-4', [], 'rect'],
  // the two branches of the parallel block (n6) are nodes of their own since Phase 21 R5
  ['n24', 'delegate', 'delegate Copywriter', 'Copywriter · gpt-4', ['→ a'], 'rect'],
  ['n25', 'delegate', 'delegate Copywriter', 'Copywriter · gpt-4', ['→ b'], 'rect'],
  ['n26', 'note', 'note', 'gave up', [], 'rect'],
  ['n27', 'note', 'note', 'blocked', [], 'rect']
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
  const handler = wf.nodes.find((n) => n.id === 'n26'); // 'gave up': the loop's exhausted handler
  assert.equal(handler.parent, 'n11');
  assert.equal(handler.branch, 'exhausted');
  const lay = G.layout({ nodes: wf.nodes, edges: wf.edges }, ctx);
  assert.ok(lay.routes.some((r) => r.label === 'exhausted'));
  assert.ok(lay.routes.some((r) => r.label === 'violation'));
  assert.ok(lay.routes.some((r) => r.label === 'rewind' && r.back));
});
