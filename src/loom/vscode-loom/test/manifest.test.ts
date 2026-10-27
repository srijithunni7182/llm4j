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
    for (const file of ['graph-render.js', 'graph-render.css', 'graph-model.js', 'views.js', 'panel.js', 'panel.css', 'loom-mark-128.png', 'loom-logo-320.png']) {
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

test('the compiled extension registers its commands', () => {
    const source = fs.readFileSync(path.join(root, 'src', 'extension.ts'), 'utf8');
    assert.match(source, /registerCommand\(\s*'loom\.runWorkflow'/);
    assert.match(source, /registerCommand\('loom\.showGraph'/);
    assert.match(source, /registerCommand\('loom\.createPromptFile'/);
    assert.match(source, /registerGuideCommands\(context\)/);
    const guide = fs.readFileSync(path.join(root, 'src', 'commands', 'guideCommands.ts'), 'utf8');
    assert.match(guide, /registerCommand\('loom\.openGuide'/);
    assert.match(guide, /registerCommand\('loom\.installSkill'/);
    const titles = manifest.contributes.commands.map((c: { command: string; title: string }) => `${c.command}=${c.title}`);
    assert.ok(titles.includes('loom.openGuide=Loom: Open Guide'));
    assert.ok(titles.includes('loom.installSkill=Loom: Install the Loom Skill in This Project'));
});

test('the webview page and scripts load nothing from outside the extension', () => {
    for (const file of ['panel.js', 'graph-render.js']) {
        const text = fs.readFileSync(path.join(root, 'media', file), 'utf8');
        assert.ok(!/\bfetch\(|XMLHttpRequest|WebSocket|eval\(|new Function|importScripts/.test(text), file);
    }
    const panel = fs.readFileSync(path.join(root, 'media', 'panel.js'), 'utf8');
    assert.ok(!/innerHTML|insertAdjacentHTML|document\.write/.test(panel), 'script text is never put in the page as HTML');
});

test('10a: the engine commands and the java setting are declared, and the old graph key still works', () => {
    const commands = manifest.contributes.commands.map((c: { command: string }) => c.command);
    assert.ok(commands.includes('loom.restartEngine'));
    assert.ok(commands.includes('loom.showEngineLog'));
    const props = manifest.contributes.configuration.properties;
    assert.equal(props['loom.javaPath'].default, 'java');
    assert.ok(props['loom.graph.javaPath'], 'the older key is honoured');
});

test('10b: the review command is offered for .loom files everywhere, and no command depends on the state of a review', () => {
    const command = manifest.contributes.commands.find((c: { command: string }) => c.command === 'loom.review');
    assert.equal(command.title, 'Loom: Review Workflow');
    for (const menu of ['commandPalette', 'editor/title', 'editor/context']) {
        const entry = manifest.contributes.menus[menu].find((m: { command: string }) => m.command === 'loom.review');
        assert.ok(entry, menu);
        assert.equal(entry.when, 'resourceLangId == loom', menu);
    }
    assert.equal(manifest.contributes.menus['explorer/context'].find((m: { command: string }) => m.command === 'loom.review').when, 'resourceExtname == .loom');
    // R43: no command is conditional on a clean review
    const allWhen = JSON.stringify(manifest.contributes.menus);
    assert.ok(!/review|finding|clean|loom\.has/i.test(allWhen.replace(/loom\.review[.\w]*/g, '').replace(/loom\.fixFinding/g, '')), 'no when clause mentions the review state');
});

test('10b: the review settings exist with the defaults the spec names', () => {
    const props = manifest.contributes.configuration.properties;
    assert.equal(props['loom.review.on'].default, 'save');
    assert.deepEqual(props['loom.review.on'].enum, ['save', 'type', 'manual']);
    assert.equal(props['loom.review.minSeverity'].default, 'low');
    assert.equal(props['loom.review.codeLens'].default, true);
    assert.equal(props['loom.review.statusBar'].default, true);
    assert.equal(props['loom.review.failOn'].default, 'high');
    assert.equal(props['loom.review.lenses'].default, '');
    assert.equal(props['loom.review.layout'].default, 'split');
    assert.deepEqual(props['loom.review.layout'].enum, ['split', 'outline', 'list']);
});

test('10b: the panel page loads exactly the files that exist in media', () => {
    const html = fs.readFileSync(path.join(root, 'src', 'review', 'html.ts'), 'utf8');
    const named = [...html.matchAll(/uri\('([^']+)'\)/g)].map((m) => m[1]);
    assert.ok(named.includes('review.js') && named.includes('review-model.js') && named.includes('review.css'));
    for (const file of named) {
        assert.ok(fs.existsSync(path.join(root, 'media', file)), `${file} is loaded by the review page but missing`);
    }
});

test('A10: no review file builds markup from text: no innerHTML, outerHTML, insertAdjacentHTML, eval or document.write', () => {
    for (const file of ['review.js', 'review-model.js']) {
        const code = fs.readFileSync(path.join(root, 'media', file), 'utf8');
        for (const banned of ['innerHTML', 'outerHTML', 'insertAdjacentHTML', 'document.write', 'eval(', 'new Function']) {
            assert.ok(!code.includes(banned), `${file} uses ${banned}`);
        }
    }
});

test('10c: every triage command is declared with a palette title and a when clause; the ones that need arguments are hidden from the palette', () => {
    const declared = manifest.contributes.commands.map((c: { command: string }) => c.command);
    const palette = manifest.contributes.menus.commandPalette;
    for (const id of ['loom.review.fixAll', 'loom.review.explain', 'loom.review.restore', 'loom.review.baseline']) {
        assert.ok(declared.includes(id), id);
    }
    for (const id of ['loom.review.fixAll', 'loom.review.restore', 'loom.review.baseline']) {
        assert.equal(palette.find((m: { command: string }) => m.command === id).when, 'resourceLangId == loom', id);
    }
    for (const id of ['loom.review.accept', 'loom.review.dismiss', 'loom.review.fixFinding']) {
        assert.ok(declared.includes(id), id);
        assert.equal(palette.find((m: { command: string }) => m.command === id).when, 'false', id + ' needs a finding, so it is not in the palette');
    }
    for (const c of manifest.contributes.commands) {
        assert.match(c.title, /^Loom: /, c.command);
    }
});

test('10c: no command is conditional on the state of a review (the hidden ones are hidden because they need arguments)', () => {
    const when = manifest.contributes.menus.commandPalette.map((m: { when: string }) => m.when).filter((w: string) => w !== 'false');
    assert.ok(when.every((w: string) => w === 'resourceLangId == loom'), JSON.stringify(when));
});
