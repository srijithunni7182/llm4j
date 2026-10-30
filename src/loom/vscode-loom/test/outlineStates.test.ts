import test from 'node:test';
import assert from 'node:assert/strict';
import { Debouncer, LoomFileFacts, OUTLINE_FILE_LIMIT, outlineRows, symbolsOf } from '../src/views/outlineModel';

const article: LoomFileFacts = {
    path: '/p/article.loom',
    relative: 'article.loom',
    symbols: [
        { kind: 'agent', name: 'Writer', line: 1 },
        { kind: 'workflow', name: 'Article', line: 5 },
    ],
};
const support: LoomFileFacts = { path: '/p/support/main.loom', relative: 'support/main.loom', symbols: [{ kind: 'workflow', name: 'Triage', line: 3 }] };

test('symbolsOf finds agents, workflows, schedules and routing with their lines', () => {
    const text = 'agent A { model: "m" }\n\nworkflow W(x) {\n}\nschedule S every day\nrouting R {}\n';
    assert.deepEqual(
        symbolsOf(text).map((s) => `${s.kind}:${s.name}:${s.line}`),
        ['agent:A:1', 'workflow:W:3', 'schedule:S:5', 'routing:R:6'],
    );
});

test('R2: no folder open says so and offers to open one, never an empty tree', () => {
    const rows = outlineRows({ hasFolder: false, files: [] });
    assert.equal(rows.length, 1);
    assert.match(rows[0].label, /Open a folder to see its workflows/);
    assert.equal(rows[0].command, 'vscode.openFolder');
});

test('R2: a folder with no .loom file leaves the tree empty for the welcome content to show', () => {
    assert.deepEqual(outlineRows({ hasFolder: true, files: [] }), []);
});

test('R2: workflows exist but no Loom file is open (the blank case): the folder view lists them with a hint', () => {
    const rows = outlineRows({ hasFolder: true, files: [article, support] });
    const labels = rows.map((r) => r.label);
    assert.ok(labels.some((l) => /Workflows in this folder/.test(l)), labels.join('|'));
    assert.ok(labels.some((l) => /Open a \.loom file to see its steps/.test(l)), labels.join('|'));
    const folder = rows.find((r) => /Workflows in this folder/.test(r.label))!;
    assert.deepEqual(folder.children!.map((c) => c.label), ['article.loom', 'support/main.loom']);
    assert.deepEqual(folder.children![0].children!.map((c) => `${c.label} (${c.description})`), ['Writer (agent)', 'Article (workflow)']);
    assert.equal(folder.children![0].children![1].line, 5, 'one click reveals the definition');
});

test('R2: with a Loom file active, its steps come first and the folder list is collapsed', () => {
    const rows = outlineRows({ hasFolder: true, files: [article, support], active: article });
    assert.match(rows[0].label, /This file/);
    assert.deepEqual(rows[0].children!.map((c) => c.label), ['Writer', 'Article']);
    const folder = rows.find((r) => /Workflows in this folder/.test(r.label))!;
    assert.equal(folder.collapsed, true);
    assert.ok(!rows.some((r) => /Open a \.loom file/.test(r.label)), 'the hint is for the blank case only');
});

test('R2: a file that defines nothing is still listed, and says so', () => {
    const empty: LoomFileFacts = { path: '/p/empty.loom', relative: 'empty.loom', symbols: [] };
    const folder = outlineRows({ hasFolder: true, files: [empty] }).find((r) => /Workflows in this folder/.test(r.label))!;
    assert.match(folder.children![0].children![0].label, /nothing defined yet/);
});

test('R2: more files than the limit show the first ones and a "show more" row', () => {
    const many = Array.from({ length: OUTLINE_FILE_LIMIT + 5 }, (_, i): LoomFileFacts => ({ path: `/p/f${i}.loom`, relative: `f${i}.loom`, symbols: [] }));
    const folder = outlineRows({ hasFolder: true, files: many.slice(0, OUTLINE_FILE_LIMIT), moreFiles: 5 }).find((r) => /Workflows in this folder/.test(r.label))!;
    assert.equal(folder.children!.length, OUTLINE_FILE_LIMIT + 1);
    assert.match(folder.children![OUTLINE_FILE_LIMIT].label, /5 more/);
});

test('R2: an error is one row that says what to do, never an empty tree', () => {
    const rows = outlineRows({ hasFolder: true, files: [article], error: { message: 'Java was not found', fix: 'Set loom.javaPath' } });
    assert.equal(rows[0].kind, 'error');
    assert.match(rows[0].label, /Java was not found/);
    assert.match(rows[0].description ?? '', /Set loom\.javaPath/);
});

test('refreshes after file events are batched into one', () => {
    const timers: Array<() => void> = [];
    let fired = 0;
    const d = new Debouncer(() => fired++, (fn) => { timers.push(fn); return timers.length - 1; }, (id) => { timers[id] = () => undefined; });
    d.poke(); d.poke(); d.poke();
    timers.forEach((t) => t());
    assert.equal(fired, 1);
});
