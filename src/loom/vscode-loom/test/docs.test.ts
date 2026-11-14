import test from 'node:test';
import assert from 'node:assert/strict';
import * as fs from 'fs';
import * as path from 'path';

const root = path.join(__dirname, '..', '..');
// The Marketplace page (README.md) is a short overview; the full reference, which these tests hold to the manifest, is REFERENCE.md.
const reference = fs.readFileSync(path.join(root, 'REFERENCE.md'), 'utf8');
const overview = fs.readFileSync(path.join(root, 'README.md'), 'utf8');
const manifest = JSON.parse(fs.readFileSync(path.join(root, 'package.json'), 'utf8'));

test('V9.4: the reference names the command and where to find it', () => {
    const title = manifest.contributes.commands.find((c: { command: string }) => c.command === 'loom.showGraph').title;
    assert.ok(reference.includes(title.replace('Loom: ', '**Loom: ') + '**') || reference.includes(`**${title}**`), 'the command title');
    assert.match(reference, /Command Palette/);
    assert.match(reference, /editor title bar/);
    assert.match(reference, /right-click menu/);
});

test('V9.4: every setting is documented with the default the manifest declares', () => {
    for (const [name, spec] of Object.entries<{ default: unknown }>(manifest.contributes.configuration.properties)) {
        const row = reference.split('\n').find((line) => line.includes('`' + name + '`'));
        assert.ok(row, `${name} is in the reference`);
        assert.ok(row!.includes('`' + String(spec.default) + '`'), `${name} default ${String(spec.default)} in: ${row}`);
    }
});

test('the reference says what happens on failure and that nothing is run', () => {
    assert.match(reference, /last good graph/);
    assert.match(reference, /nothing is run and no model is called/);
    assert.match(reference, /Java 17/);
});

test('the reference covers every node kind the panel draws', () => {
    for (const word of ['delegate', 'run', 'parallel', 'alt', 'loop', 'for each', 'call', 'checkpoint', 'rewind', 'guardrail', 'decide', 'observe', 'sleep', 'note']) {
        assert.ok(reference.includes(word), word);
    }
});

test('the reference documents the prompt-file features and the command that creates the file', () => {
    const create = manifest.contributes.commands.find((c: { command: string }) => c.command === 'loom.createPromptFile');
    assert.ok(create, 'the command is declared');
    assert.ok(reference.includes(`**${create.title}**`), 'the command title is in the reference');
    assert.match(reference, /Open prompt/);
    assert.match(reference, /never overwrites a file/);
});

test('the create command is offered in the palette only for .loom files', () => {
    const entry = manifest.contributes.menus.commandPalette.find((m: { command: string }) => m.command === 'loom.createPromptFile');
    assert.equal(entry.when, 'resourceLangId == loom');
});

test('the reference documents the guide commands by their titles', () => {
    for (const id of ['loom.openGuide', 'loom.installSkill']) {
        const command = manifest.contributes.commands.find((c: { command: string }) => c.command === id);
        assert.ok(command, `${id} is in the manifest`);
        assert.ok(reference.includes(`**${command.title}**`), `${command.title} is in the reference`);
    }
});

test('the reference lists every snippet prefix', () => {
    const snippets = JSON.parse(fs.readFileSync(path.join(root, 'snippets', 'loom.code-snippets'), 'utf8'));
    for (const s of Object.values(snippets) as { prefix: string }[]) {
        assert.ok(reference.includes('`' + s.prefix + '`'), `${s.prefix} is in the reference`);
    }
});

test('the Marketplace README stays short and links to the full reference', () => {
    const maxLines = 100;
    assert.ok(overview.split('\n').length <= maxLines, `README.md is at most ${maxLines} lines`);
    assert.match(overview, /https:\/\/github\.com\/srijithunni7182\/llm4j\/blob\/main\/src\/loom\/vscode-loom\/REFERENCE\.md/);
    assert.match(overview, /Java 17/);
});

test('the Marketplace README links only to absolute URLs, so every link works on the Marketplace page', () => {
    const relative = [...overview.matchAll(/\]\((?!https:\/\/)([^)]+)\)/g)].map((m) => m[1]);
    assert.deepEqual(relative, []);
});
