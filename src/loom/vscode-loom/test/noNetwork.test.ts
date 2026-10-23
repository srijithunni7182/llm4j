import { strict as assert } from 'assert';
import * as fs from 'fs';
import * as path from 'path';
import { test } from 'node:test';

/**
 * R32 of spec 10: no telemetry and no network use by the extension. The extension runs Java processes on this machine and talks to
 * them over pipes; the pages it shows may load nothing from outside.
 */
const root = path.join(__dirname, '..', '..');

function filesUnder(dir: string, extension: string, out: string[] = []): string[] {
    for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
        const p = path.join(dir, entry.name);
        if (entry.isDirectory()) {
            filesUnder(p, extension, out);
        } else if (entry.name.endsWith(extension)) {
            out.push(p);
        }
    }
    return out;
}

const sources = filesUnder(path.join(root, 'src'), '.ts');
const pages = filesUnder(path.join(root, 'media'), '.js');

test('R32: no module in the extension can open a connection', () => {
    const network = /from ['"](?:node:)?(?:http|https|http2|net|tls|dgram|dns|ws)['"]|require\(['"](?:node:)?(?:http|https|http2|net|tls|dgram|dns|ws)['"]\)|\bfetch\(|XMLHttpRequest|WebSocket|sendBeacon|new EventSource/;
    const offenders = sources.filter((f) => network.test(fs.readFileSync(f, 'utf8'))).map((f) => path.relative(root, f));
    // `this.fetch(run)` in the graph controller is a method of its own, not the web API
    assert.deepEqual(offenders.filter((f) => f !== path.join('src', 'graph', 'controller.ts')), []);
});

test('R32: no page script can open a connection or load code', () => {
    const forbidden = /\bfetch\(|XMLHttpRequest|WebSocket|sendBeacon|EventSource|importScripts|\beval\(|new Function|import\(/;
    const offenders = pages.filter((f) => forbidden.test(fs.readFileSync(f, 'utf8'))).map((f) => path.relative(root, f));
    assert.deepEqual(offenders, []);
});

test('R32: every page the extension shows has the one policy, which forbids everything it does not name', () => {
    const graph = fs.readFileSync(path.join(root, 'src', 'graph', 'html.ts'), 'utf8');
    assert.match(graph, /default-src 'none'/);
    assert.doesNotMatch(graph, /connect-src|https?:\/\/(?!www\.w3\.org)/, 'the policy names no outside address');
    const review = fs.readFileSync(path.join(root, 'src', 'review', 'html.ts'), 'utf8');
    assert.match(review, /contentSecurityPolicy\(/, 'the review page uses the same policy');
    assert.doesNotMatch(review, /https?:\/\/(?!www\.w3\.org)/);
});

test('R32: processes are started only to run Java (the engine and the commands), and the engine is told to run nothing', () => {
    const users = sources.filter((f) => /from 'child_process'/.test(fs.readFileSync(f, 'utf8'))).map((f) => path.relative(root, f).split(path.sep).join('/')).sort();
    assert.deepEqual(users, ['src/commands/runWorkflow.ts', 'src/engine/spawn.ts', 'src/engine/vscodeHost.ts', 'src/generate/vscodeGenerate.ts', 'src/graph/runner.ts']);
    const spawnText = fs.readFileSync(path.join(root, 'src', 'engine', 'spawn.ts'), 'utf8');
    assert.match(spawnText, /'serve', '--stdio'/);
});

test('R32: the manifest asks for no telemetry package and has no setting that sends anything', () => {
    const manifest = JSON.parse(fs.readFileSync(path.join(root, 'package.json'), 'utf8'));
    const dependencies = { ...manifest.dependencies, ...manifest.devDependencies };
    assert.ok(!Object.keys(dependencies).some((name) => /telemetry|applicationinsights|segment|sentry|posthog|mixpanel/i.test(name)));
    const settings = Object.keys(manifest.contributes.configuration.properties);
    assert.ok(!settings.some((s) => /telemetry|analytics|report|upload/i.test(s)), settings.join(', '));
});

test('R31: the settings the spec names exist with their defaults, under the loom. namespace', () => {
    const manifest = JSON.parse(fs.readFileSync(path.join(root, 'package.json'), 'utf8'));
    const props = manifest.contributes.configuration.properties;
    const expected: Record<string, unknown> = {
        'loom.javaPath': 'java',
        'loom.review.on': 'save',
        'loom.review.lenses': '',
        'loom.review.minSeverity': 'low',
        'loom.review.codeLens': true,
        'loom.review.statusBar': true,
        'loom.review.failOn': 'high',
        'loom.serve.memoryMb': 64,
        'loom.serve.timeoutMs': 10000,
        'loom.semantic.enable': true,
    };
    for (const [key, value] of Object.entries(expected)) {
        assert.equal(props[key]?.default, value, key);
        assert.ok(props[key].description.length > 20, `${key} has a description`);
    }
    assert.ok(props['loom.graph.javaPath'], 'the older key still works');
    assert.equal(props['loom.serve.memoryMb'].minimum, 8);
    assert.equal(props['loom.serve.timeoutMs'].minimum, 1000);
    const commands = manifest.contributes.commands.map((c: { command: string }) => c.command);
    assert.ok(commands.includes('loom.javaHelp'));
});
