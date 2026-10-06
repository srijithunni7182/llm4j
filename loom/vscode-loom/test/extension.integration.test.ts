/**
 * The extension's glue code (command, panel, runner) with the real weave.jar and real processes, against a stand-in for
 * the vscode module. A real VS Code is not needed to check what the extension asks of it; it is needed to look at the
 * result, which scripts/verify-vsix.sh and the browser tests of the page cover.
 */
import test from 'node:test';
import assert from 'node:assert/strict';
import * as fs from 'fs';
import * as os from 'os';
import * as path from 'path';
import { createFakeVscode } from './fakeVscode';

const Module = require('module');
// the real module object: an `import *` namespace cannot be patched
const child_process = require('child_process');
const extRoot = path.join(__dirname, '..', '..');
const repo = path.join(extRoot, '..', '..');
const samples = path.join(repo, 'loom', 'ai-agent4j-loom', 'samples');

function findJar(): string | undefined {
    const bundled = path.join(extRoot, 'bin', 'weave.jar');
    if (fs.existsSync(bundled)) {
        return bundled;
    }
    const target = path.join(repo, 'loom', 'ai-agent4j-loom', 'target');
    const built = fs.existsSync(target) ? fs.readdirSync(target).find((f) => /^ai-agent4j-loom-.*\.jar$/.test(f) && !f.startsWith('original-')) : undefined;
    return built ? path.join(target, built) : undefined;
}

const jar = findJar();
const hasJava = child_process.spawnSync('java', ['-version']).status === 0;
const skip = !jar ? 'no weave.jar: run scripts/build-vsix.sh' : !hasJava ? 'java is not installed' : false;

/** An extension folder holding the compiled media and the jar, as installed. */
function installedExtension(withJar = true): string {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'loom-ext-'));
    fs.symlinkSync(path.join(extRoot, 'media'), path.join(dir, 'media'));
    if (withJar && jar) {
        fs.mkdirSync(path.join(dir, 'bin'));
        fs.symlinkSync(jar, path.join(dir, 'bin', 'weave.jar'));
    }
    return dir;
}

interface Session {
    fake: ReturnType<typeof createFakeVscode>;
    spawns: string[][];
    run(file: string, line?: number): Promise<void>;
    context: any;
    restore(): void;
}

/** Loads the compiled command against a fresh fake vscode, and records every process it starts. */
function session(settings: Record<string, unknown> = {}, ext = installedExtension()): Session {
    const fake = createFakeVscode(settings);
    const spawns: string[][] = [];
    const originalSpawn = child_process.spawn;
    child_process.spawn = (command: string, args: string[], options: unknown) => {
        spawns.push([command, ...args]);
        return (originalSpawn as any)(command, args, options);
    };
    const originalLoad = Module._load;
    Module._load = function (request: string, ...rest: unknown[]) {
        return request === 'vscode' ? fake.vscode : originalLoad.call(this, request, ...rest);
    };
    for (const key of Object.keys(require.cache)) {
        if (key.includes(path.join('out-test', 'src'))) {
            delete require.cache[key];
        }
    }
    const { showGraphCommand } = require('../src/commands/showGraph');
    const context = { extensionUri: fake.vscode.Uri.file(ext), asAbsolutePath: (p: string) => path.join(ext, p) };
    return {
        fake, spawns, context,
        run: async (file: string, line = 1) => {
            fake.state.activeEditor = { document: { uri: { fsPath: file } }, selection: { active: { line: line - 1 } }, viewColumn: 1 };
            await showGraphCommand(context);
        },
        restore: () => {
            child_process.spawn = originalSpawn;
            Module._load = originalLoad;
            fake.state.panels.forEach((p) => p.dispose());
        },
    };
}

const wait = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms));
async function until(condition: () => boolean, ms = 8000): Promise<void> {
    const end = Date.now() + ms;
    while (!condition() && Date.now() < end) {
        await wait(25);
    }
    assert.ok(condition(), 'timed out waiting');
}

const contentFactory = path.join(samples, 'content_factory', 'main.loom');
const twoWorkflows = path.join(__dirname, '..', '..', 'test', 'fixtures', 'two-workflows.loom');

test('V5.2/VS.5: the command opens the panel beside the editor and shows the graph from the real weave.jar', { skip }, async () => {
    const s = session();
    try {
        await s.run(contentFactory);
        assert.equal(s.fake.state.panels.length, 1);
        const panel = s.fake.state.panels[0];
        assert.equal(panel.viewType, 'loomGraph');
        assert.equal(panel.title, 'Loom Graph: main.loom');
        assert.equal(panel.showOptions.viewColumn, -2, 'beside the editor');
        assert.deepEqual(panel.options.localResourceRoots.map((u: { fsPath: string }) => path.basename(u.fsPath)), ['media']);
        assert.equal(panel.options.enableScripts, true);
        assert.match((panel.iconPath as { fsPath: string }).fsPath, /loom-mark-128\.png$/);
        assert.match(panel.html, /Content-Security-Policy/);
        const graph = panel.ofType('graph');
        assert.equal(graph.length, 1);
        assert.equal(graph[0].workflow, 'GenerateContent');
        assert.equal(graph[0].result.version, 1);
        const jars = s.spawns.filter((c) => c.includes('graph'));
        assert.equal(jars.length, 1, 'one weave graph process');
        assert.deepEqual(jars[0].slice(1, 2), ['-jar']);
        assert.deepEqual(jars[0].slice(3), ['graph', contentFactory, '--format', 'json']);
        assert.deepEqual(s.fake.state.errors, []);
    } finally { s.restore(); }
});

test('V5.5: with several workflows in a file, the one the cursor is in is chosen first', { skip }, async () => {
    const s = session();
    try {
        await s.run(twoWorkflows, 7);
        assert.equal(s.fake.state.panels[0].ofType('graph')[0].workflow, 'Second');
        s.fake.state.panels[0].dispose();
        await s.run(twoWorkflows, 3);
        assert.equal(s.fake.state.panels[1].ofType('graph')[0].workflow, 'First');
    } finally { s.restore(); }
});

test('asking again for the same script shows the panel that is open instead of making another', { skip }, async () => {
    const s = session();
    try {
        await s.run(contentFactory);
        await s.run(contentFactory);
        assert.equal(s.fake.state.panels.length, 1);
        assert.equal(s.fake.state.panels[0].revealed, 1);
    } finally { s.restore(); }
});

test('V5.3a: when java cannot start there is an error with Open Settings and no panel', { skip }, async () => {
    const s = session({ 'loom.graph.javaPath': '/nonexistent/java' });
    s.fake.state.answers.set('*', 'Open Settings');
    try {
        await s.run(contentFactory);
        assert.equal(s.fake.state.panels.length, 0);
        assert.equal(s.fake.state.errors.length, 1);
        assert.match(s.fake.state.errors[0].message, /Java was not found.*loom\.graph\.javaPath/);
        assert.deepEqual(s.fake.state.errors[0].actions, ['Open Settings']);
        assert.deepEqual(s.fake.state.executed, [{ command: 'workbench.action.openSettings', args: ['loom.graph.javaPath'] }]);
    } finally { s.restore(); }
});

test('V5.3b: when weave.jar is missing there is an error and no panel, and no process is started', { skip }, async () => {
    const s = session({}, installedExtension(false));
    try {
        await s.run(contentFactory);
        assert.equal(s.fake.state.panels.length, 0);
        assert.match(s.fake.state.errors[0].message, /weave\.jar was not found.*Reinstall/);
        assert.equal(s.spawns.length, 0);
    } finally { s.restore(); }
});

test('V5.4: a graph that takes too long is stopped, the process is killed and the message says so', { skip }, async () => {
    const pidFile = path.join(os.tmpdir(), `slow-java-${process.pid}.pid`);
    process.env.SLOW_JAVA_PID_FILE = pidFile;
    const s = session({ 'loom.graph.javaPath': path.join(__dirname, '..', '..', 'test', 'fixtures', 'slow-java.sh'), 'loom.graph.timeoutMs': 1000 });
    try {
        const started = Date.now();
        await s.run(contentFactory);
        assert.ok(Date.now() - started < 6000);
        assert.match(s.fake.state.errors[0].message, /longer than 1 s/);
        assert.equal(s.fake.state.panels.length, 1);
        assert.equal(s.fake.state.panels[0].disposed, true, 'no empty panel is left open');
        const pid = Number(fs.readFileSync(pidFile, 'utf8'));
        await until(() => { try { process.kill(pid, 0); return false; } catch { return true; } }, 3000);
    } finally { s.restore(); fs.rmSync(pidFile, { force: true }); }
});

test('a script with a syntax error shows its message with Go to error, and leaves no panel open', { skip }, async () => {
    const s = session();
    const bad = path.join(__dirname, '..', '..', 'test', 'fixtures', 'bad.loom');
    s.fake.state.answers.set('*', 'Go to error');
    try {
        await s.run(bad);
        assert.equal(s.fake.state.panels[0].disposed, true);
        assert.match(s.fake.state.errors[0].message, /bad\.loom:2/);
        assert.deepEqual(s.fake.state.errors[0].actions, ['Go to error']);
        assert.equal(s.fake.state.shown.length, 1);
        assert.equal(s.fake.state.shown[0].file, bad);
        assert.equal(s.fake.state.shown[0].options.selection.start.line, 1);
    } finally { s.restore(); }
});

test('a file that is not a .loom file is refused, with no panel and no process', { skip }, async () => {
    const s = session();
    try {
        await s.run(path.join(extRoot, 'package.json'));
        assert.equal(s.fake.state.panels.length, 0);
        assert.match(s.fake.state.errors[0].message, /open a \.loom file/);
        assert.equal(s.spawns.length, 0);
    } finally { s.restore(); }
});

test('V8.1/V8.2: saves of the script refresh the graph once, however many arrive together', { skip }, async () => {
    const s = session();
    try {
        await s.run(contentFactory);
        const panel = s.fake.state.panels[0];
        const before = s.spawns.filter((c) => c.includes('graph')).length;
        for (let i = 0; i < 10; i++) {
            s.fake.state.save.fire({ uri: { fsPath: contentFactory } });
            await wait(20);
        }
        await until(() => panel.ofType('graph').length === 2);
        await wait(600);
        assert.equal(panel.ofType('graph').length, 2, 'one refresh');
        assert.equal(s.spawns.filter((c) => c.includes('graph')).length - before, 1);
        s.fake.state.save.fire({ uri: { fsPath: path.join(samples, 'content_factory', 'primitives.loom') } });
        await until(() => panel.ofType('graph').length === 3);
        s.fake.state.save.fire({ uri: { fsPath: '/somewhere/else.loom' } });
        await wait(600);
        assert.equal(panel.ofType('graph').length, 3, 'a file outside the graph does nothing');
    } finally { s.restore(); }
});

test('V8.3: breaking the script keeps the last good graph and says so, and fixing it brings the graph back', { skip }, async () => {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'loom-edit-'));
    const file = path.join(dir, 'edit.loom');
    fs.writeFileSync(file, 'workflow W() {\n    note "one"\n}\n');
    const s = session();
    try {
        await s.run(file);
        const panel = s.fake.state.panels[0];
        fs.writeFileSync(file, 'workflow W() {\n    delegate "x" to A }\n');
        s.fake.state.save.fire({ uri: { fsPath: file } });
        await until(() => panel.ofType('stale').length === 1);
        assert.match(panel.ofType('stale')[0].message, /edit\.loom:2/);
        assert.equal(panel.ofType('stale')[0].line, 2);
        fs.writeFileSync(file, 'workflow W() {\n    note "two"\n    note "three"\n}\n');
        s.fake.state.save.fire({ uri: { fsPath: file } });
        await until(() => panel.ofType('graph').length === 2);
        assert.equal(panel.ofType('graph')[1].result.workflows[0].nodes.length, 4, 'start, two notes, end');
    } finally { s.restore(); }
});

test('V8.4: closing the panel stops listening, and later saves start nothing', { skip }, async () => {
    const s = session();
    try {
        await s.run(contentFactory);
        const panel = s.fake.state.panels[0];
        assert.equal(s.fake.state.save.count, 1);
        assert.equal(s.fake.state.selection.count, 1);
        panel.dispose();
        assert.equal(s.fake.state.save.count, 0, 'the save watcher is gone');
        assert.equal(s.fake.state.selection.count, 0, 'the cursor watcher is gone');
        const before = s.spawns.length;
        s.fake.state.save.fire({ uri: { fsPath: contentFactory } });
        await wait(600);
        assert.equal(s.spawns.length, before);
    } finally { s.restore(); }
});

test('with auto refresh off, saves do not redraw', { skip }, async () => {
    const s = session({ 'loom.graph.autoRefresh': false });
    try {
        await s.run(contentFactory);
        assert.equal(s.fake.state.save.count, 0);
    } finally { s.restore(); }
});

test('V7.1/VS.2: the panel can open the script and its imports, and nothing else', { skip }, async () => {
    const s = session();
    try {
        await s.run(contentFactory);
        const panel = s.fake.state.panels[0];
        const imported = path.join(samples, 'content_factory', 'primitives.loom');
        panel.received.fire({ type: 'openSource', file: imported, line: 3, beside: true });
        assert.equal(s.fake.state.shown.length, 1);
        assert.equal(s.fake.state.shown[0].file, imported);
        assert.equal(s.fake.state.shown[0].options.viewColumn, 1, 'in the column the script was opened in');
        assert.equal(s.fake.state.shown[0].options.selection.start.line, 2);
        for (const file of ['/etc/passwd', path.join(samples, '..', 'pom.xml'), `${samples}/content_factory/../../pom.xml`]) {
            panel.received.fire({ type: 'openSource', file, line: 1, beside: false });
        }
        assert.equal(s.fake.state.shown.length, 1);
        assert.equal(s.fake.state.warnings.length, 3);
    } finally { s.restore(); }
});

test('V7.4: moving the cursor highlights the step on that line in the shown workflow', { skip }, async () => {
    const s = session();
    try {
        await s.run(contentFactory);
        const panel = s.fake.state.panels[0];
        s.fake.state.selection.fire({ textEditor: { document: { uri: { fsPath: contentFactory } } }, selections: [{ active: { line: 4 } }] });
        s.fake.state.selection.fire({ textEditor: { document: { uri: { fsPath: contentFactory } } }, selections: [{ active: { line: 0 } }] });
        assert.deepEqual(panel.ofType('highlight').map((m: any) => m.id), ['n1', null]);
    } finally { s.restore(); }
});

test('Copy Mermaid puts the real Mermaid text of the workflow on the clipboard', { skip }, async () => {
    const s = session();
    try {
        await s.run(contentFactory);
        s.fake.state.panels[0].received.fire({ type: 'copyMermaid', name: 'GenerateContent' });
        await until(() => s.fake.state.clipboard.length === 1);
        assert.match(s.fake.state.clipboard[0], /^%% workflow GenerateContent \(.*main\.loom\)\nflowchart TD\n/);
        assert.match(s.fake.state.clipboard[0], /n4 -->\|then\| n5/);
        assert.match(s.fake.state.infos[0], /Copied the Mermaid diagram of GenerateContent/);
    } finally { s.restore(); }
});

test('the LOOM_GRAPH_DELAY_MS hook holds the loading state long enough to see it', { skip }, async () => {
    process.env.LOOM_GRAPH_DELAY_MS = '700';
    const s = session();
    try {
        const run = s.run(contentFactory);
        await wait(250);
        assert.equal(s.fake.state.panels[0].ofType('graph').length, 0, 'still loading');
        s.fake.state.panels[0].received.fire({ type: 'ready' });
        assert.deepEqual(s.fake.state.panels[0].ofType('loading'), [{ type: 'loading', file: contentFactory }]);
        await run;
        assert.equal(s.fake.state.panels[0].ofType('graph').length, 1);
    } finally { delete process.env.LOOM_GRAPH_DELAY_MS; s.restore(); }
});

test('VP.6: from a save to the redrawn graph takes at most 3.5 s, a JVM start included', { skip }, async () => {
    const s = session();
    try {
        await s.run(contentFactory);
        const panel = s.fake.state.panels[0];
        const started = Date.now();
        s.fake.state.save.fire({ uri: { fsPath: contentFactory } });
        await until(() => panel.ofType('graph').length === 2, 6000);
        const elapsed = Date.now() - started;
        assert.ok(elapsed <= 3500, `the refresh took ${elapsed} ms`);
    } finally { s.restore(); }
});

test('VG.3: the outline view and the run command behave as before', { skip }, async () => {
    const s = session();
    try {
        const { WorkflowOutlineProvider } = require('../src/views/WorkflowOutlineProvider');
        const { runWorkflowCommand } = require('../src/commands/runWorkflow');
        const provider = new WorkflowOutlineProvider();
        const text = 'import "x.loom"\nagent Researcher { model: "m" }\nworkflow Main() { note "a" }\nschedule Daily { cron: "0 7 * * *" }\nrouting Smart { strategy: fallback }\n';
        const offsets = (needle: string) => text.indexOf(needle);
        const document = { languageId: 'loom', getText: () => text, positionAt: (o: number) => new s.fake.vscode.Position(text.slice(0, o).split('\n').length - 1, 0), uri: { fsPath: '/p/a.loom' } };
        s.fake.state.activeEditor = { document };
        s.fake.state.activeEditorChanged.fire(s.fake.state.activeEditor);
        const nodes = await provider.getChildren();
        assert.deepEqual(nodes.map((n: any) => [n.kind, n.name]), [['agent', 'Researcher'], ['workflow', 'Main'], ['schedule', 'Daily'], ['routing', 'Smart']]);
        assert.ok(offsets('agent') > 0);
        assert.equal((await provider.getChildren(nodes[0])).length, 0);

        s.fake.state.activeEditor = { document: { ...document, uri: { fsPath: '/p/readme.md' } } };
        runWorkflowCommand({ asAbsolutePath: (p: string) => p });
        assert.match(s.fake.state.errors[0].message, /not a \.loom file/);
        assert.equal(s.spawns.length, 0);
        s.fake.state.activeEditor = undefined;
        runWorkflowCommand({ asAbsolutePath: (p: string) => p });
        assert.match(s.fake.state.errors[1].message, /No active editor/);
    } finally { s.restore(); }
});
