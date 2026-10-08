import test from 'node:test';
import assert from 'node:assert/strict';
import { GraphPanelController, DEFAULT_DEBOUNCE_MS } from '../src/graph/controller';
import { GraphExitError } from '../src/graph/errors';
import { FakeHost, FakeRunner, FakeScheduler, sampleResult, settle } from './support';

function setup(cursor = { file: '/p/main.loom', line: 1 }) {
    const runner = new FakeRunner();
    const host = new FakeHost();
    const scheduler = new FakeScheduler();
    const controller = new GraphPanelController({ entry: '/p/main.loom', cursor, runner, host, scheduler });
    return { runner, host, scheduler, controller };
}

const json = (result = sampleResult()) => JSON.stringify(result);

async function loaded(cursor?: { file: string; line: number }) {
    const s = setup(cursor);
    const load = s.controller.load();
    s.runner.finish(json());
    assert.equal((await load).ok, true);
    return s;
}

test('the first load posts the graph with the workflow the cursor is in', async () => {
    const s = await loaded({ file: '/p/main.loom', line: 12 });
    const graph = s.host.ofType('graph');
    assert.equal(graph.length, 1);
    assert.equal(graph[0].workflow, 'Second');
    assert.equal(graph[0].result.workflows.length, 3);
});

test('a first load that fails returns the error and posts nothing', async () => {
    const s = setup();
    const load = s.controller.load();
    s.runner.fail(new GraphExitError(2, 'Error: /p/main.loom:2: Parse error'));
    const outcome = await load;
    assert.equal(outcome.ok, false);
    assert.equal(s.host.posted.length, 0);
});

test('the panel asking for its content gets the graph, or the loading state before there is one', async () => {
    const before = setup();
    before.controller.handle({ type: 'ready' });
    assert.deepEqual(before.host.posted, [{ type: 'loading', file: '/p/main.loom' }]);

    const after = await loaded();
    after.controller.handle({ type: 'ready' });
    assert.equal(after.host.ofType('graph').length, 2);
});

test('saving the entry or an imported file refreshes, saving another file does not', async () => {
    const s = await loaded();
    s.controller.fileSaved('/p/elsewhere.loom');
    assert.equal(s.scheduler.waiting, 0);
    s.controller.fileSaved('/p/lib.loom');
    assert.equal(s.scheduler.waiting, 1);
    s.scheduler.advance(DEFAULT_DEBOUNCE_MS);
    assert.equal(s.runner.requests.length, 2);
    s.runner.finish(json());
    await settle();
    s.controller.fileSaved('/p/main.loom');
    assert.equal(s.scheduler.waiting, 1);
});

test('ten saves within the debounce make one run', async () => {
    const s = await loaded();
    for (let i = 0; i < 10; i++) {
        s.controller.fileSaved('/p/main.loom');
        s.scheduler.advance(25);
    }
    assert.equal(s.runner.requests.length, 1, 'nothing has run yet');
    s.scheduler.advance(DEFAULT_DEBOUNCE_MS);
    assert.equal(s.runner.requests.length, 2, 'the load and one refresh');
});

test('a newer run stops the one in flight and only the newer result is shown', async () => {
    const s = await loaded();
    s.controller.refresh();
    s.scheduler.advance(DEFAULT_DEBOUNCE_MS);
    assert.equal(s.runner.inFlight >= 1, true);
    const first = s.runner.requests[1];
    s.controller.refresh();
    s.scheduler.advance(DEFAULT_DEBOUNCE_MS);
    const second = s.runner.requests[2];
    assert.equal(first.signal.aborted, true, 'the older run was stopped');
    assert.equal(second.signal.aborted, false);
    const before = s.host.ofType('graph').length;
    s.runner.finish(json(sampleResult({ entry: '/p/main.loom' })));
    await settle();
    assert.equal(s.host.ofType('graph').length, before + 1);
    assert.equal(s.host.ofType('stale').length, 0, 'stopping the older run is not an error');
});

test('a refresh that fails keeps the last good graph and posts a stale notice with the file and line', async () => {
    const s = await loaded();
    const goodBefore = s.controller.result;
    s.controller.refresh();
    s.scheduler.advance(DEFAULT_DEBOUNCE_MS);
    s.runner.fail(new GraphExitError(2, 'Error: /p/main.loom:14: Parse error at line 14: expected )'));
    await settle();
    const stale = s.host.ofType('stale');
    assert.equal(stale.length, 1);
    assert.match(stale[0].message, /expected \)/);
    assert.deepEqual([stale[0].file, stale[0].line], ['/p/main.loom', 14]);
    assert.equal(s.controller.result, goodBefore);
});

test('a fix after a failure posts the new graph, which clears the notice', async () => {
    const s = await loaded();
    s.controller.refresh();
    s.scheduler.advance(DEFAULT_DEBOUNCE_MS);
    s.runner.fail(new Error('boom'));
    await settle();
    s.controller.refresh();
    s.scheduler.advance(DEFAULT_DEBOUNCE_MS);
    s.runner.finish(json());
    await settle();
    assert.equal(s.host.posted[s.host.posted.length - 1].type, 'graph');
});

test('output that is not a graph is a stale notice too, not a crash', async () => {
    const s = await loaded();
    s.controller.refresh();
    s.scheduler.advance(DEFAULT_DEBOUNCE_MS);
    s.runner.finish('this is not json');
    await settle();
    assert.match(s.host.ofType('stale')[0].message, /did not print JSON/);
});

test('the chosen workflow survives a refresh, and falls back when it is gone', async () => {
    const s = await loaded({ file: '/p/main.loom', line: 12 });
    assert.equal(s.controller.selectedWorkflow, 'Second');
    s.controller.refresh();
    s.scheduler.advance(DEFAULT_DEBOUNCE_MS);
    s.runner.finish(json());
    await settle();
    assert.equal(s.host.ofType('graph').pop()!.workflow, 'Second');

    s.controller.handle({ type: 'selectWorkflow', name: 'Lib' });
    assert.equal(s.controller.selectedWorkflow, 'Lib');
    const without = sampleResult();
    without.workflows = without.workflows.filter((w) => w.name !== 'Lib');
    s.controller.refresh();
    s.scheduler.advance(DEFAULT_DEBOUNCE_MS);
    s.runner.finish(json(without));
    await settle();
    assert.notEqual(s.host.ofType('graph').pop()!.workflow, 'Lib');
});

test('selecting a workflow that does not exist is ignored', async () => {
    const s = await loaded();
    s.controller.handle({ type: 'selectWorkflow', name: 'Nope' });
    assert.equal(s.controller.selectedWorkflow, 'First');
});

test('moving the cursor to a line with a step highlights it, and to a line without clears it, once each', async () => {
    const s = await loaded();
    s.controller.cursorMoved('/p/main.loom', 4);
    s.controller.cursorMoved('/p/main.loom', 4);
    s.controller.cursorMoved('/p/main.loom', 8);
    s.controller.cursorMoved('/p/main.loom', 9);
    assert.deepEqual(s.host.ofType('highlight').map((m) => m.id), ['n1', null]);
});

test('highlighting follows the selected workflow, not any workflow', async () => {
    const s = await loaded();
    s.controller.cursorMoved('/p/main.loom', 11);
    assert.deepEqual(s.host.ofType('highlight'), [], 'line 11 belongs to Second, First is shown');
});

test('opening a source file is allowed for the entry and its imports only', async () => {
    const s = await loaded();
    s.controller.handle({ type: 'openSource', file: '/p/main.loom', line: 4, beside: true });
    s.controller.handle({ type: 'openSource', file: '/p/lib.loom', line: 1, beside: false });
    assert.deepEqual(s.host.opened, [{ file: '/p/main.loom', line: 4, beside: true }, { file: '/p/lib.loom', line: 1, beside: false }]);
    for (const file of ['/etc/passwd', '/p/../etc/passwd', '/p/other.loom', '../../secret', 'C:\\Windows\\win.ini']) {
        s.controller.handle({ type: 'openSource', file, line: 1, beside: false });
    }
    assert.equal(s.host.opened.length, 2, 'nothing else was opened');
    assert.equal(s.host.reports.filter((r) => r.kind === 'warning').length, 5);
});

test('a file with a problem reported, and the file of the last error, may be opened too', async () => {
    const s = await loaded();
    const withProblem = sampleResult({ diagnostics: [{ severity: 'error', file: '/p/broken.loom', line: 5, message: 'bad' }] });
    s.controller.refresh();
    s.scheduler.advance(DEFAULT_DEBOUNCE_MS);
    s.runner.finish(json(withProblem));
    await settle();
    s.controller.handle({ type: 'openSource', file: '/p/broken.loom', line: 5, beside: false });
    assert.deepEqual(s.host.opened, [{ file: '/p/broken.loom', line: 5, beside: false }]);

    s.controller.refresh();
    s.scheduler.advance(DEFAULT_DEBOUNCE_MS);
    s.runner.fail(new GraphExitError(2, 'Error: /p/other.loom:3: Parse error'));
    await settle();
    s.controller.handle({ type: 'openSource', file: '/p/other.loom', line: 3, beside: false });
    assert.equal(s.host.opened.length, 2);
});

test('messages that are not exactly the known ones are ignored', async () => {
    const s = await loaded();
    const before = s.host.posted.length;
    for (const bad of [null, 'x', { type: 'openSource', file: '/p/main.loom', line: 0 }, { type: 'run', command: 'x' }, { type: 'selectWorkflow' }]) {
        s.controller.handle(bad);
    }
    assert.equal(s.host.opened.length, 0);
    assert.equal(s.host.posted.length, before);
});

test('copying the Mermaid of a workflow asks the command for it and puts it on the clipboard', async () => {
    const s = await loaded();
    s.controller.handle({ type: 'copyMermaid', name: 'Second' });
    const request = s.runner.requests[s.runner.requests.length - 1];
    assert.equal(request.format, 'mermaid');
    assert.equal(request.workflow, 'Second');
    s.runner.finish('flowchart TD\n  start --> end_\n');
    await settle();
    assert.deepEqual(s.host.copied, ['flowchart TD\n  start --> end_\n']);
    assert.match(s.host.reports.pop()!.message, /Copied the Mermaid diagram of Second/);
});

test('copying Mermaid for an unknown workflow does nothing, and a failure is reported', async () => {
    const s = await loaded();
    const runs = s.runner.requests.length;
    s.controller.handle({ type: 'copyMermaid', name: 'Nope' });
    assert.equal(s.runner.requests.length, runs);
    s.controller.handle({ type: 'copyMermaid', name: 'First' });
    s.runner.fail(new Error('no java'));
    await settle();
    assert.equal(s.host.reports.pop()!.kind, 'error');
});

test('disposing stops the run in flight and every timer, and nothing is posted afterwards', async () => {
    const s = await loaded();
    s.controller.refresh();
    s.scheduler.advance(DEFAULT_DEBOUNCE_MS);
    s.controller.refresh();
    const running = s.runner.requests[s.runner.requests.length - 1];
    s.controller.dispose();
    assert.equal(running.signal.aborted, true);
    assert.equal(s.scheduler.waiting, 0);
    const before = s.host.posted.length;
    s.controller.fileSaved('/p/main.loom');
    s.controller.cursorMoved('/p/main.loom', 4);
    s.controller.handle({ type: 'ready' });
    await settle();
    assert.equal(s.scheduler.waiting, 0, 'no refresh is scheduled after dispose');
    assert.equal(s.host.posted.length, before);
});

test('a result that arrives after dispose is dropped', async () => {
    const s = setup();
    const load = s.controller.load();
    s.controller.dispose();
    s.runner.finish(json());
    const outcome = await load;
    assert.equal(outcome.ok, false);
    assert.equal(s.host.posted.length, 0);
});

test('R7.1: the panel may open the prompt file of an agent, and no other file in the same folder', async () => {
    const result = sampleResult();
    result.agents = [{ name: 'Researcher', prompt: { ref: 'researcher', version: 'v2', file: '/p/prompts/researcher/v2.md' } }];
    const s = setup();
    const load = s.controller.load();
    s.runner.finish(json(result));
    await load;

    s.controller.handle({ type: 'openSource', file: '/p/prompts/researcher/v2.md', line: 1, beside: true });
    s.controller.handle({ type: 'openSource', file: '/p/prompts/researcher/v1.md', line: 1, beside: true });

    assert.deepEqual(s.host.opened.map((o) => o.file), ['/p/prompts/researcher/v2.md']);
    assert.equal(s.host.reports.length, 1);
});
