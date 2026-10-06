import test from 'node:test';
import assert from 'node:assert/strict';
import * as fs from 'fs';
import * as path from 'path';

const root = path.join(__dirname, '..', '..');
const manifest = JSON.parse(fs.readFileSync(path.join(root, 'package.json'), 'utf8'));
const PNG_SIGNATURE = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]);

function pngSize(file: string): { width: number; height: number } {
    const bytes = fs.readFileSync(file);
    assert.ok(bytes.subarray(0, 8).equals(PNG_SIGNATURE), `${file} is not a PNG`);
    return { width: bytes.readUInt32BE(16), height: bytes.readUInt32BE(20) };
}

test('V5.1: the command is declared and offered for .loom files in the palette, the title bar and the context menu', () => {
    const command = manifest.contributes.commands.find((c: { command: string }) => c.command === 'loom.showGraph');
    assert.equal(command.title, 'Loom: Show Workflow Graph');
    for (const menu of ['commandPalette', 'editor/title', 'editor/context']) {
        const entry = manifest.contributes.menus[menu].find((m: { command: string }) => m.command === 'loom.showGraph');
        assert.ok(entry, menu);
        assert.equal(entry.when, 'resourceLangId == loom', menu);
    }
});

test('the three settings exist with their defaults', () => {
    const props = manifest.contributes.configuration.properties;
    assert.equal(props['loom.graph.javaPath'].default, 'java');
    assert.equal(props['loom.graph.timeoutMs'].default, 30000);
    assert.equal(props['loom.graph.autoRefresh'].default, true);
});

test('V9.6: the extension icon is a PNG that exists in the package', () => {
    assert.equal(manifest.icon, 'media/loom-mark-128.png');
    assert.deepEqual(pngSize(path.join(root, manifest.icon)), { width: 128, height: 128 });
});

test('V9.5: the package includes the webview files, the logos, the compiled code and the runtime', () => {
    assert.ok(manifest.files.includes('media/**/*'));
    assert.ok(manifest.files.includes('out/**/*'));
    assert.ok(manifest.files.includes('bin/**/*'));
    for (const file of ['graph-render.js', 'graph-render.css', 'panel.js', 'panel.css', 'loom-mark-128.png', 'loom-logo-320.png']) {
        assert.ok(fs.existsSync(path.join(root, 'media', file)), file);
    }
});

test('the panel files the page loads are exactly the files in media', () => {
    const html = fs.readFileSync(path.join(root, 'src', 'graph', 'html.ts'), 'utf8');
    const named = [...html.matchAll(/uri\('([^']+)'\)/g)].map((m) => m[1]);
    assert.ok(named.length >= 6);
    for (const file of named) {
        assert.ok(fs.existsSync(path.join(root, 'media', file)), `${file} is loaded by the page but missing`);
    }
});

test('the test runner and the packaging stay out of the shipped files', () => {
    assert.ok(!manifest.files.some((f: string) => f.includes('test') || f.includes('out-test')));
});

test('the main entry point and the activation events are unchanged', () => {
    assert.equal(manifest.main, './out/extension.js');
    assert.deepEqual(manifest.activationEvents, ['onLanguage:loom', 'onLanguage:loot']);
});

test('the compiled extension registers both commands', () => {
    const source = fs.readFileSync(path.join(root, 'src', 'extension.ts'), 'utf8');
    assert.match(source, /registerCommand\(\s*'loom\.runWorkflow'/);
    assert.match(source, /registerCommand\('loom\.showGraph'/);
});

test('the webview page and scripts load nothing from outside the extension', () => {
    for (const file of ['panel.js', 'graph-render.js']) {
        const text = fs.readFileSync(path.join(root, 'media', file), 'utf8');
        assert.ok(!/\bfetch\(|XMLHttpRequest|WebSocket|eval\(|new Function|importScripts/.test(text), file);
    }
    const panel = fs.readFileSync(path.join(root, 'media', 'panel.js'), 'utf8');
    assert.ok(!/innerHTML|insertAdjacentHTML|document\.write/.test(panel), 'script text is never put in the page as HTML');
});
