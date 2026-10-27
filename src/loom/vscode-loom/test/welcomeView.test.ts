import test from 'node:test';
import assert from 'node:assert/strict';
import * as fs from 'fs';
import * as path from 'path';
import { protocolMismatch, protocolOf, shellKind, terminalCommand } from '../src/welcome/welcome';

const root = path.join(__dirname, '..', '..');
const manifest = JSON.parse(fs.readFileSync(path.join(root, 'package.json'), 'utf8'));

test('R28: a workspace with no .loom file is welcomed with the four buttons, each a declared command', () => {
    const w = manifest.contributes.viewsWelcome.find((x: { view: string }) => x.view === 'loom.workflowOutline');
    assert.ok(w);
    assert.equal(w.when, '!loom.hasLoom');
    const commands = manifest.contributes.commands.map((c: { command: string }) => c.command);
    for (const label of ['Try a demo', 'Generate a workflow', 'Open an example', 'Check my setup']) assert.ok(w.contents.includes(`[${label}]`), label);
    const used = [...w.contents.matchAll(/\(command:([\w.]+)\)/g)].map((m) => m[1]);
    assert.deepEqual(used.sort(), ['loom.doctor', 'loom.generate', 'loom.openExample', 'loom.try']);
    for (const u of used) assert.ok(commands.includes(u), u);
});

test('R28: the welcome commands are registered by the extension', () => {
    const src = fs.readFileSync(path.join(root, 'src', 'welcome', 'register.ts'), 'utf8');
    for (const c of ['loom.try', 'loom.doctor', 'loom.openExample']) assert.ok(src.includes(`'${c}'`), c);
    assert.ok(fs.readFileSync(path.join(root, 'src', 'extension.ts'), 'utf8').includes('registerWelcome(context)'));
});

test('R20: a CLI that speaks another protocol than the bundled engine is named in one sentence', () => {
    const bundled = 'weave 6.0.0 (built 2026-10-27T00:00:00Z), serve protocol 1';
    assert.equal(protocolOf(bundled), 1);
    assert.equal(protocolMismatch(bundled, 'weave 6.0.0, serve protocol 1'), undefined);
    assert.match(protocolMismatch(bundled, 'weave 7.0.0, serve protocol 2') ?? '', /protocol 2 but this extension's engine speaks 1/);
    assert.equal(protocolMismatch(bundled, 'weave 5.0.0'), undefined, 'an old CLI that prints no protocol is not a mismatch');
});

test('the command is quoted for the shell the terminal runs, and typed into it so the output stays on screen', () => {
    assert.equal(terminalCommand('java', '/ext/bin/weave.jar', ['try', '--step'], 'posix'), 'java -jar /ext/bin/weave.jar try --step');
    assert.equal(terminalCommand('java', '/my ext/weave.jar', ['x$(y)'], 'posix'), 'java -jar "/my ext/weave.jar" "x\\$(y)"');
    assert.equal(terminalCommand('java', 'C:\\Program Files\\ext\\weave.jar', ['try'], 'powershell'), "& 'java' '-jar' 'C:\\Program Files\\ext\\weave.jar' 'try'");
    assert.equal(terminalCommand('java', "C:\\it's\\w.jar", ['doctor'], 'powershell'), "& 'java' '-jar' 'C:\\it''s\\w.jar' 'doctor'");
    assert.equal(terminalCommand('java', 'C:\\Program Files\\ext\\weave.jar', ['try'], 'cmd'), 'java -jar "C:\\Program Files\\ext\\weave.jar" try');
});

test('the shell kind comes from the terminal shell path', () => {
    assert.equal(shellKind('/bin/bash', 'linux'), 'posix');
    assert.equal(shellKind('C:\\Windows\\System32\\WindowsPowerShell\\v1.0\\powershell.exe', 'win32'), 'powershell');
    assert.equal(shellKind('C:\\Program Files\\PowerShell\\7\\pwsh.exe', 'win32'), 'powershell');
    assert.equal(shellKind('C:\\Windows\\System32\\cmd.exe', 'win32'), 'cmd');
    assert.equal(shellKind(undefined, 'win32'), 'powershell');
    assert.equal(shellKind(undefined, 'darwin'), 'posix');
});
