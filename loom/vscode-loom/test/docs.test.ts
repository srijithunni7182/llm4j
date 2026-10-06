import test from 'node:test';
import assert from 'node:assert/strict';
import * as fs from 'fs';
import * as path from 'path';

const root = path.join(__dirname, '..', '..');
const readme = fs.readFileSync(path.join(root, 'README.md'), 'utf8');
const manifest = JSON.parse(fs.readFileSync(path.join(root, 'package.json'), 'utf8'));

test('V9.4: the README names the command and where to find it', () => {
    const title = manifest.contributes.commands.find((c: { command: string }) => c.command === 'loom.showGraph').title;
    assert.ok(readme.includes(title.replace('Loom: ', '**Loom: ') + '**') || readme.includes(`**${title}**`), 'the command title');
    assert.match(readme, /Command Palette/);
    assert.match(readme, /editor title bar/);
    assert.match(readme, /right-click menu/);
});

test('V9.4: every setting is documented with the default the manifest declares', () => {
    for (const [name, spec] of Object.entries<{ default: unknown }>(manifest.contributes.configuration.properties)) {
        const row = readme.split('\n').find((line) => line.includes('`' + name + '`'));
        assert.ok(row, `${name} is in the README`);
        assert.ok(row!.includes('`' + String(spec.default) + '`'), `${name} default ${String(spec.default)} in: ${row}`);
    }
});

test('the README says what happens on failure and that nothing is run', () => {
    assert.match(readme, /last good graph/);
    assert.match(readme, /nothing is run and no model is called/);
    assert.match(readme, /Java 17/);
});

test('the README covers every node kind the panel draws', () => {
    for (const word of ['delegate', 'run', 'parallel', 'alt', 'loop', 'for each', 'call', 'checkpoint', 'rewind', 'guardrail', 'decide', 'observe', 'note']) {
        assert.ok(readme.includes(word), word);
    }
});

test('the README documents the prompt-file features and the command that creates the file', () => {
    const create = manifest.contributes.commands.find((c: { command: string }) => c.command === 'loom.createPromptFile');
    assert.ok(create, 'the command is declared');
    assert.ok(readme.includes(`**${create.title}**`), 'the command title is in the README');
    assert.match(readme, /Open prompt/);
    assert.match(readme, /never overwrites a file/);
});

test('the create command is offered in the palette only for .loom files', () => {
    const entry = manifest.contributes.menus.commandPalette.find((m: { command: string }) => m.command === 'loom.createPromptFile');
    assert.equal(entry.when, 'resourceLangId == loom');
});

test('the README documents the guide commands by their titles', () => {
    for (const id of ['loom.openGuide', 'loom.installSkill']) {
        const command = manifest.contributes.commands.find((c: { command: string }) => c.command === id);
        assert.ok(command, `${id} is in the manifest`);
        assert.ok(readme.includes(`**${command.title}**`), `${command.title} is in the README`);
    }
});
