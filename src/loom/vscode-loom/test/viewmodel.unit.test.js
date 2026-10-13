'use strict';
/* The view model of the panel (media/graph-model.js) and the Flow layout maths (media/views.js), without a page. */
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('fs');
const path = require('path');
const M = require('../media/graph-model.js');

const fixture = JSON.parse(fs.readFileSync(path.join(__dirname, 'fixtures', 'laptop-doctor.graph.json'), 'utf8'));
const golden = (name) => JSON.parse(fs.readFileSync(path.join(__dirname, '..', '..', 'ai-agent4j-loom', 'src', 'test', 'resources', 'graph', 'golden', name + '.json'), 'utf8'));
const laptop = () => M.build(fixture.result.workflows[0], { sections: fixture.sections });

test('the main path of the laptop-doctor script is the happy path the design names', () => {
    assert.deepEqual(laptop().main, ['start', 'n1', 'n2', 'n3', 'n7', 'n8', 'n10', 'n11', 'n17', 'n19', 'n21', 'n22', 'n24', 'n25', 'n26', 'n28', 'n29', 'n30', 'n31', 'end']);
});

test('notes inside an alt branch that lead only to the end are exits; they are never on the main path', () => {
    const m = laptop();
    assert.deepEqual(m.nodes.filter((n) => n.exit).map((n) => n.id).sort(), ['n18', 'n23', 'n27', 'n9']);
    m.nodes.filter((n) => n.exit).forEach((n) => { assert.equal(n.main, false); assert.equal(n.vk, 'exit'); });
});

test('steps go to the section whose comment is above them; steps in a block follow their parent', () => {
    const m = laptop();
    assert.deepEqual(m.sections.map((s) => s.n), [1, 2, 3, 4, 5, 6]);
    const sec = (id) => m.by[id].section + 1;
    assert.deepEqual(['n1', 'n7', 'n11', 'n12', 'n19', 'n22', 'n25', 'n28', 'end'].map(sec), [1, 2, 3, 3, 4, 4, 5, 6, 6]);
    assert.equal(sec('n23'), 4, 'an exit written at the end of the file stays with the branch it ends');
    assert.equal(sec('n27'), 5);
});

test('without section comments the whole workflow is one section named after it', () => {
    const m = M.build(fixture.result.workflows[0], {});
    assert.equal(m.sections.length, 1);
    assert.equal(m.sections[0].title, 'Main');
    assert.equal(m.hasSections, false);
    assert.ok(m.nodes.every((n) => n.section === 0));
});

test('depth follows the parent chain', () => {
    const m = laptop();
    assert.deepEqual(['n2', 'n3', 'n4', 'n5', 'n6'].map((id) => m.by[id].depth), [0, 1, 1, 2, 3]);
});

test('an alt is a verdict, a safety outcome, an approval or a plain branch by what it reads', () => {
    const m = laptop();
    const kinds = Object.fromEntries(['n4', 'n5', 'n8', 'n11', 'n19', 'n22', 'n26'].map((id) => [id, m.by[id].vk]));
    assert.deepEqual(kinds, { n4: 'verdict', n5: 'branch', n8: 'verdict', n11: 'check', n19: 'check', n22: 'approval', n26: 'approval' });
    assert.deepEqual(['n1', 'n2', 'n10', 'n20', 'n29', 'start', 'end'].map((id) => m.by[id].vk), ['agent', 'loop', 'task', 'human', 'note', 'term', 'term']);
});

test('an alt reading the variable of an agent that expects fields is a verdict, of a task a safety outcome, of a person an approval', () => {
    const wf = { name: 'W', nodes: [
        { id: 'start', kind: 'start', label: 'Start' },
        { id: 'a', kind: 'delegate', label: 'delegate X', attrs: { variable: 'r', expecting: '{ok}' } },
        { id: 'b', kind: 'alt', label: 'r.ok == yes?', attrs: { condition: 'r.ok == yes' } },
        { id: 'c', kind: 'task', label: 'run T', attrs: { variable: 't' } },
        { id: 'd', kind: 'alt', label: 't == bad?', attrs: { condition: 't == bad' } },
        { id: 'e', kind: 'human_prompt', label: 'ask', attrs: { variable: 'p' } },
        { id: 'f', kind: 'alt', label: 'p == yes?', attrs: { condition: 'p == yes' } },
        { id: 'end', kind: 'end', label: 'End' }], edges: [] };
    const m = M.build(wf);
    assert.deepEqual(['b', 'd', 'f'].map((id) => m.by[id].vk), ['verdict', 'check', 'approval']);
});

test('a real graph (all statements): the path runs through loops by their body and out by `done`, and ends at `end`', () => {
    const wf = golden('all_statements').workflows[0];
    const m = M.build(wf);
    assert.equal(m.main[0], 'start');
    assert.equal(m.main[m.main.length - 1], 'end');
    assert.ok(m.main.includes('n10') && m.main.includes('n12') && m.main.includes('n14'));
    assert.ok(!m.main.includes('n23'), 'the handler of an exhausted loop is off the path');
    assert.equal(new Set(m.main).size, m.main.length, 'no step twice');
    m.nodes.forEach((n) => assert.ok(M.VK[n.vk], n.id + ' has a kind: ' + n.vk));
});

test('every golden workflow gets a model with a main path from start', () => {
    for (const name of ['boardroom', 'content_factory', 'digest', 'handlers', 'imports_parent', 'all_statements']) {
        golden(name).workflows.forEach((wf) => {
            const m = M.build(wf);
            assert.equal(m.main[0], 'start', name);
            assert.ok(m.main.length >= 2, name + ' ' + wf.name);
        });
    }
});

test('attrs.main overrides the rule: a step marked true is taken, one marked false is avoided', () => {
    const wf = JSON.parse(JSON.stringify(fixture.result.workflows[0]));
    wf.nodes.find((n) => n.id === 'n12').attrs = { main: true };
    assert.ok(M.build(wf, { sections: fixture.sections }).main.includes('n12'));
});

test('a detour hangs under the main-path step it belongs to', () => {
    const m = laptop();
    assert.equal(m.by.n12.anchor, 'n11');
    assert.equal(m.by.n15.anchor, 'n11');
    assert.equal(m.by.n9.anchor, 'n8');
    assert.equal(m.by.n6.anchor, 'n2');
    assert.equal(m.by.n11.anchor, 'n11');
});

test('the status line says where the step is', () => {
    const m = laptop();
    assert.equal(M.railNote(m, 'n11'), 'Step 8 of 20 on the happy path');
    assert.match(M.railNote(m, 'n12'), /^Off the main path, inside step 8 \(outcome == "blocked"\)$/);
    assert.match(M.railNote(m, 'n18'), /^Off the main path: an exit that ends the run, from step 9$/);
});

test('edge words and the order the keys walk in', () => {
    assert.deepEqual(['again', 'done', 'round', 'then', 'else', ''].map(M.edgeWord), ['loops back', 'when done', 'each round', 'then', 'else', 'next']);
    const m = laptop();
    const all = M.visibleOrder(m, new Set()).map((n) => n.id);
    assert.ok(!all.includes('start') && !all.includes('end'));
    const folded = M.visibleOrder(m, new Set(['n11'])).map((n) => n.id);
    assert.ok(!folded.includes('n12') && !folded.includes('n16') && folded.includes('n11'));
});

test('every kind has a tone in panel.css with the same hue and chroma', () => {
    const css = fs.readFileSync(path.join(__dirname, '..', 'media', 'panel.css'), 'utf8');
    Object.entries(M.VK).forEach(([vk, k]) => {
        const m = new RegExp('\\.k-' + vk + ' \\{ --h: ([\\d.]+); --c: ([\\d.]+); \\}').exec(css);
        assert.ok(m, vk + ' has no tone');
        assert.equal(Number(m[1]), k.h, vk + ' hue');
        assert.equal(Number(m[2]), k.c, vk + ' chroma');
    });
});

test('Flow: cards on a row 228 apart, detours 40 below the spine, bands over their columns', () => {
    const V = (() => { global.document = {}; return require('../media/views.js'); })();
    const L = V.flowLayout(laptop());
    assert.equal(L.pos.n1.x, 24 + 1 * 228);
    assert.equal(L.pos.n1.y, 56);
    assert.equal(L.pos.start.w, 110);
    assert.equal(L.pos.n12.y, 56 + 104 + 40);
    assert.equal(L.pos.n13.y - L.pos.n12.y, 56 + 12);
    assert.equal(L.bands.length, 6);
    assert.ok(L.paths.some((p) => p.cls === 'p-back'), 'the loop has a back edge');
    assert.ok(L.paths.some((p) => p.cls === 'p-rejoin'), 'a detour rejoins the spine');
    assert.ok(L.labels.some((l) => /^again/.test(l.t)));
    assert.equal(L.drops.length, laptop().nodes.filter((n) => !n.main).length);
});

/** A workflow that never ends: a start, a loop, and in the loop a step, a sleep and the way round again. */
const endless = () => ({ name: 'Watch', nodes: [
    { id: 'start', kind: 'start', label: 'Start' },
    { id: 'n1', kind: 'loop', label: 'loop forever', source: { file: 'w.loom', line: 3 } },
    { id: 'n2', kind: 'delegate', label: 'delegate Checker', agent: 'Checker', parent: 'n1', branch: 'body', source: { file: 'w.loom', line: 4 } },
    { id: 'n3', kind: 'sleep', label: 'sleep 60s', parent: 'n1', branch: 'body', source: { file: 'w.loom', line: 5 } },
    { id: 'end', kind: 'end', label: 'End' }],
  edges: [{ from: 'start', to: 'n1' }, { from: 'n1', to: 'n2' }, { from: 'n2', to: 'n3' }, { from: 'n3', to: 'n1', label: 'again' }] });

test('a workflow with no end: the path is the loop and its body, it says so, and it has no end step', () => {
    const m = M.build(endless());
    assert.deepEqual(m.main, ['start', 'n1', 'n2', 'n3']);
    assert.equal(m.endless, true);
    assert.equal(m.by.n3.vk, 'sleep');
    assert.equal(M.VK.sleep.glyph, 'eyeclosed', 'a sleep is a shut eye');
    assert.match(M.railNote(m, 'n3'), /Step 4 of 4 on the happy path · runs until stopped/);
    assert.equal(laptop().endless, false);
});

test('Flow: the way round of an endless loop is drawn between steps on the spine and labelled', () => {
    const V = (() => { global.document = {}; return require('../media/views.js'); })();
    const L = V.flowLayout(M.build(endless()));
    assert.ok(L.paths.some((p) => p.cls === 'p-back'));
    assert.ok(L.labels.some((l) => l.t === 'again forever'));
    assert.equal(Object.keys(L.pos).length, 4);
});
