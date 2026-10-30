import test from 'node:test';
import assert from 'node:assert/strict';
import { outlineRows, LoomFileFacts } from '../src/views/outlineModel';
import { ProgressSource, parseJourney, progressRows, progressStatus, isProgressFile } from '../src/views/progressModel';

const file: LoomFileFacts = { path: '/p/main.loom', relative: 'main.loom', symbols: [{ kind: 'agent', name: 'Writer', line: 1 }] };

const answer = {
    stages: [
        { number: 1, id: 'decide-agents', title: 'Decide your agents', state: 'done', detail: '3 agent(s) in the script', command: null },
        { number: 2, id: 'design-evaluation', title: 'Design the evaluation', state: 'done', detail: 'EVAL_DESIGN.md', command: null },
        { number: 3, id: 'golden-dataset', title: 'Golden dataset', state: 'skipped', detail: 'README says: Evaluation: skipped', command: null },
        { number: 4, id: 'script', title: 'The script checks clean', state: 'failing', detail: 'line 4: unexpected }', command: 'weave check main.loom --no-env' },
        { number: 5, id: 'audit-and-review', title: 'Audit and review', state: 'done', detail: 'no high finding', command: 'weave review main.loom' },
        { number: 6, id: 'scripted-tests', title: 'Scripted tests', state: 'not-started', detail: 'no tests yet', command: 'weave test main.loom --coverage' },
        { number: 7, id: 'preflight', title: 'Preflight', state: 'stale', detail: 'the script changed', command: 'weave preflight main.loom' },
        { number: 8, id: 'real-run-read', title: 'Read one real answer', state: 'not-started', detail: 'needs a passing preflight first', command: 'weave next --confirm real-run' },
        { number: 9, id: 'real-evaluation', title: 'Real evaluation', state: 'not-started', detail: 'not run yet', command: 'weave eval main.loom --max-tokens 200000' },
        { number: 10, id: 'deploy', title: 'Deploy and watch', state: 'not-started', detail: 'no deployment files yet', command: 'weave deploy main.loom --dry-run' },
    ],
    primary: { number: 4, id: 'script', title: 'The script checks clean', state: 'failing', detail: 'line 4: unexpected }', command: 'weave check main.loom --no-env' },
};

test('R3: the engine\'s stages become rows with an icon and a word for each state, and the primary step is marked', () => {
    const journey = parseJourney({ result: answer })!;
    const rows = progressRows(journey, '/p');
    assert.equal(rows.label, 'Project progress: 4 of 10 done or skipped');
    const byId = Object.fromEntries(rows.children!.map((r) => [r.stageId, r]));
    assert.equal(byId['decide-agents'].icon, 'pass');
    assert.equal(byId['golden-dataset'].icon, 'debug-step-over');
    assert.equal(byId['script'].icon, 'error');
    assert.equal(byId['preflight'].icon, 'history');
    assert.equal(byId['deploy'].icon, 'circle-outline');
    assert.match(byId['script'].description!, /^next · failing: line 4/);
    assert.match(byId['preflight'].description!, /^stale: the script changed/);
    assert.ok(!/^next/.test(byId['deploy'].description!));
    assert.equal(rows.children!.filter((r) => /^next/.test(r.description ?? '')).length, 1);
});

test('R3: every row says its state in words as well as an icon, for a screen reader', () => {
    const rows = progressRows(parseJourney({ result: answer })!, '/p');
    for (const r of rows.children!) assert.match(r.accessibleLabel!, /(done|failing|not started|skipped|stale)/);
});

test('R3: a click types the stage\'s command into a terminal and never runs it', () => {
    const rows = progressRows(parseJourney({ result: answer })!, '/p');
    const row = rows.children!.find((r) => r.stageId === 'script')!;
    assert.equal(row.typeCommand, 'weave check main.loom --no-env');
});

test('R3: a done stage whose product is a file opens that file', () => {
    const rows = progressRows(parseJourney({ result: answer })!, '/p');
    const design = rows.children!.find((r) => r.stageId === 'design-evaluation')!;
    assert.equal(design.file, '/p/EVAL_DESIGN.md');
    assert.equal(design.typeCommand, undefined);
});

test('R3: an answer that is not the stage list is not a journey', () => {
    assert.equal(parseJourney(undefined), undefined);
    assert.equal(parseJourney({ result: { steps: [] } }), undefined);
    assert.equal(parseJourney({ result: { stages: [{ id: 3 }] } }), undefined);
});

test('R3: the progress group comes first, above the file and the folder', () => {
    const progress = progressRows(parseJourney({ result: answer })!, '/p');
    const rows = outlineRows({ hasFolder: true, files: [file], active: file, progress });
    assert.match(rows[0].label, /^Project progress/);
    assert.match(rows[1].label, /This file/);
});

test('R3: without the engine the group says progress needs it and how to fix that, and nothing else is hidden', () => {
    const rows = outlineRows({ hasFolder: true, files: [file], progress: { kind: 'message', label: 'Progress needs the Loom engine (Java 17 or newer)', command: 'loom.javaHelp' } });
    assert.equal(rows[0].command, 'loom.javaHelp');
    assert.ok(rows.some((r) => /Workflows in this folder/.test(r.label)));
});

test('R3: progress is asked for at most once in three seconds, and the last answer is kept with its age while a new one is awaited', async () => {
    let now = 1_000;
    let calls = 0;
    let release: (v: unknown) => void = () => undefined;
    const source = new ProgressSource(
        () => { calls++; return new Promise((resolve) => { release = resolve; }); },
        () => now,
    );
    const first = source.get();
    release({ result: answer });
    assert.ok((await first)!.journey);
    now += 1_000;
    const again = await source.get();
    assert.equal(calls, 1, 'within three seconds the last answer is reused');
    assert.equal(again!.ageSeconds, 1);

    now += 5_000;
    const slow = source.get();
    const during = await source.get();
    assert.equal(during!.ageSeconds, 6, 'while the refresh runs the old answer is shown with its age');
    release({ result: answer });
    await slow;
    assert.equal(calls, 2);
});

test('R3: when the engine fails the last known progress stays, and with none there is nothing to show', async () => {
    let fail = false;
    const source = new ProgressSource(() => (fail ? Promise.reject(new Error('engine is not ready')) : Promise.resolve({ result: answer })), () => 0, 0);
    assert.ok((await source.get())!.journey);
    fail = true;
    assert.ok((await source.get())!.journey, 'the last known result is kept');
    const empty = new ProgressSource(() => Promise.reject(new Error('engine is not ready')), () => 0, 0);
    assert.equal(await empty.get(), undefined);
});

test('R3: the status bar names the next step and how far along the project is; it is empty without a journey', () => {
    const journey = parseJourney({ result: answer })!;
    const bar = progressStatus(journey)!;
    assert.equal(bar.text, '$(error) Loom 4/10 · The script checks clean');
    assert.match(bar.tooltip, /Next: 4\. The script checks clean \(failing: line 4/);
    assert.equal(bar.command, 'loom.progress.type');
    assert.equal(bar.argument, 'weave check main.loom --no-env');
    assert.equal(progressStatus(undefined), undefined);
});

test('R3: when every stage is done or skipped the status bar says so', () => {
    const done = parseJourney({ result: { stages: answer.stages.map((s) => ({ ...s, state: 'done' })) } })!;
    assert.equal(progressStatus(done)!.text, '$(pass) Loom 10/10 · all done');
});

test('R3: files that change a stage (eval, dataset, tests, loom) are the ones watched', () => {
    for (const f of ['eval/dataset.jsonl', 'a/eval/judge-validation.json', 'EVAL_DESIGN.md', 'main.loom', 'tests/x.test.yaml']) {
        assert.ok(isProgressFile(f), f);
    }
    assert.ok(!isProgressFile('node_modules/x/eval/a.json'));
    assert.ok(!isProgressFile('README.txt'));
});
