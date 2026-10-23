import { strict as assert } from 'assert';
import { execFileSync, spawn } from 'child_process';
import * as fs from 'fs';
import * as path from 'path';
import { test } from 'node:test';
import { EngineProcess, ProcessEngineClient } from '../src/engine/client';
import { EngineRpcError } from '../src/engine/errors';
import { TimeoutScheduler } from '../src/graph/timeoutScheduler';

// The real engine, from the Java module's classes (no VS Code needed). Skipped when the module has not been built.
const loomModule = path.join(__dirname, '..', '..', '..', 'ai-agent4j-loom');
const classes = path.join(loomModule, 'target', 'classes');
const lib = path.join(loomModule, 'target', 'lib');
const available = fs.existsSync(path.join(classes, 'io', 'github', 'llm4j', 'loom', 'cli', 'ServeCommand.class')) && fs.existsSync(lib);
const classpath = [classes, path.join(lib, '*')].join(path.delimiter);
const fixtures = path.join(__dirname, '..', '..', 'test', 'fixtures');

function startReal(): EngineProcess {
    const child = spawn('java', ['-cp', classpath, 'io.github.llm4j.loom.cli.WeaveCLI', 'serve', '--stdio'], { stdio: ['pipe', 'pipe', 'pipe'] });
    return {
        write: (b) => { child.stdin?.write(b); },
        onData: (l) => child.stdout?.on('data', l),
        onStderr: (l) => child.stderr?.on('data', (c: Buffer) => l(c.toString('utf8'))),
        onExit: (l) => child.once('exit', l),
        kill: () => { child.kill(); },
    };
}

function weave(args: string[]): string {
    try {
        return execFileSync('java', ['-cp', classpath, 'io.github.llm4j.loom.cli.WeaveCLI', ...args], { encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] });
    } catch (e) {
        return (e as { stdout: string }).stdout; // exit 2 still prints the JSON
    }
}

function newClient(): ProcessEngineClient {
    const client = new ProcessEngineClient({ start: startReal, roots: () => [fixtures], scheduler: new TimeoutScheduler(), requestTimeoutMs: 30_000 });
    client.start();
    return client;
}

test('10a: the real engine and the command give the same check and graph JSON (A12)', { skip: !available && 'ai-agent4j-loom is not built' }, async () => {
    const client = newClient();
    try {
        for (const name of ['two-workflows.loom', 'bad.loom']) {
            const file = path.join(fixtures, name);
            const viaEngine = (await client.request('loom/check', { file })).result;
            const viaCommand = JSON.parse(weave(['check', file, '--format', 'json', '--no-env']));
            assert.deepEqual(viaEngine, viaCommand, name);
        }
        const file = path.join(fixtures, 'two-workflows.loom');
        const graph = (await client.request('loom/graph', { file })).result;
        assert.deepEqual(graph, JSON.parse(weave(['graph', file])));
    } finally {
        client.dispose();
    }
});

test('10a: unsaved text is checked, a file outside the folders is refused, an unknown rule is explained as an error', { skip: !available && 'ai-agent4j-loom is not built' }, async () => {
    const client = newClient();
    try {
        const file = path.join(fixtures, 'two-workflows.loom');
        const broken = await client.request('loom/check', { file, text: 'workflow Main(x) { delegate "hi" to Nobody -> r }\n' });
        assert.match(JSON.stringify(broken.result), /Nobody/);
        await assert.rejects(client.request('loom/check', { file: path.join(__dirname, 'engine.integration.test.js') }), (e: Error) => e instanceof EngineRpcError && e.kind === 'outside-workspace');
        const explained = await client.request('loom/explainRule', { rule: 'LA01' });
        assert.match(explained.text, /LA01/);
    } finally {
        client.dispose();
    }
});

test('R12, A6: cold start and warm feedback (recorded, with generous bounds)', { skip: !available && 'ai-agent4j-loom is not built' }, async () => {
    const client = newClient();
    try {
        const file = path.join(fixtures, 'two-workflows.loom');
        const cold = Date.now();
        await client.request('loom/check', { file });
        const coldMs = Date.now() - cold;
        const warm: number[] = [];
        for (let i = 0; i < 20; i++) {
            const t = process.hrtime.bigint();
            await client.request('loom/check', { file, text: `// edit ${i}\n` + fs.readFileSync(file, 'utf8') });
            warm.push(Number(process.hrtime.bigint() - t) / 1e6);
        }
        warm.sort((a, b) => a - b);
        const p95 = warm[Math.floor(warm.length * 0.95) - 1];
        console.log(`# engine latency: cold ${coldMs} ms, warm check p95 ${p95.toFixed(0)} ms (median ${warm[10].toFixed(0)} ms)`);
        assert.ok(coldMs < 8000, `cold ${coldMs} ms`);
        assert.ok(p95 < 1000, `warm p95 ${p95} ms`);
    } finally {
        client.dispose();
    }
});

test('R16: when the real engine process is killed, the next request is served by a new one', { skip: !available && 'ai-agent4j-loom is not built' }, async () => {
    const client = newClient();
    try {
        const file = path.join(fixtures, 'two-workflows.loom');
        await client.request('loom/check', { file });
        let killed = false;
        const p = new ProcessEngineClient({
            start: () => { const e = startReal(); if (!killed) { killed = true; setTimeout(() => e.kill(), 1500); } return e; },
            roots: () => [fixtures],
            scheduler: new TimeoutScheduler(),
            backoffMs: [50],
            requestTimeoutMs: 30_000,
        });
        p.start();
        await p.request('loom/check', { file });
        await new Promise((r) => setTimeout(r, 2500));
        const again = await p.request('loom/check', { file });
        assert.equal(again.status, 0);
        p.dispose();
    } finally {
        client.dispose();
    }
});
