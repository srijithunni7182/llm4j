'use strict';
const fs = require('fs');
const path = require('path');

/** Graph JSON produced by `weave graph`, committed under the Loom module's golden files. */
const GOLDEN = path.join(__dirname, '..', '..', 'ai-agent4j-loom', 'src', 'test', 'resources', 'graph', 'golden');

function golden(name) { return JSON.parse(fs.readFileSync(path.join(GOLDEN, name + '.json'), 'utf8')); }
function workflow(result, name) { return result.workflows.find((w) => w.name === name); }
function agents(result) { const map = {}; result.agents.forEach((a) => { map[a.name] = a; }); return map; }

/** A chain of `n` notes between start and end. */
function chain(n) {
  const nodes = [{ id: 'start', kind: 'start', label: 'Start' }];
  const edges = [];
  let prev = 'start';
  for (let i = 1; i <= n; i++) {
    nodes.push({ id: 'n' + i, kind: 'note', label: 'note' });
    edges.push({ from: prev, to: 'n' + i });
    prev = 'n' + i;
  }
  nodes.push({ id: 'end', kind: 'end', label: 'End' });
  edges.push({ from: prev, to: 'end' });
  return { nodes, edges };
}

/** `rounds` alts each with a loop in the then branch: the shape of a big real workflow, with parents. */
function big(rounds, perBlock) {
  const nodes = [{ id: 'start', kind: 'start', label: 'Start' }], edges = [];
  let id = 0, prevExits = [['start', null]];
  const add = (kind, label, parent, branch) => { const n = { id: 'n' + (++id), kind, label }; if (parent) { n.parent = parent; n.branch = branch; } nodes.push(n); return n; };
  const link = (from, to, label) => edges.push(label ? { from, to, label } : { from, to });
  for (let r = 0; r < rounds; r++) {
    const alt = add('alt', 'x?', null, null);
    prevExits.forEach(([f, l]) => link(f, alt.id, l));
    let cur = [[alt.id, 'then']];
    for (let i = 0; i < perBlock; i++) { const d = add('delegate', 'delegate A', alt.id, 'then'); d.agent = 'A'; cur.forEach(([f, l]) => link(f, d.id, l)); cur = [[d.id, null]]; }
    const loop = add('loop', 'loop until x', alt.id, 'then'); loop.bound = 3;
    cur.forEach(([f, l]) => link(f, loop.id, l));
    let body = [[loop.id, null]];
    for (let i = 0; i < perBlock; i++) { const n = add('note', 'inside', loop.id, 'body'); body.forEach(([f, l]) => link(f, n.id, l)); body = [[n.id, null]]; }
    link(body[0][0], loop.id, 'again');
    const thenExit = [loop.id, 'done'];
    let other = [[alt.id, 'else']];
    for (let i = 0; i < perBlock; i++) { const n = add('note', 'else', alt.id, 'else'); other.forEach(([f, l]) => link(f, n.id, l)); other = [[n.id, null]]; }
    prevExits = [thenExit, other[0]];
  }
  nodes.push({ id: 'end', kind: 'end', label: 'End' });
  prevExits.forEach(([f, l]) => link(f, 'end', l));
  return { nodes, edges };
}

module.exports = { golden, workflow, agents, chain, big };
