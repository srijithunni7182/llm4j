import test from 'node:test';
import assert from 'node:assert/strict';
import * as fs from 'fs';
import * as path from 'path';

const root = path.join(__dirname, '..', '..');
const read = (...parts: string[]) => fs.readFileSync(path.join(root, ...parts), 'utf8');

test('R1.1: the language server no longer reports the scanner\'s findings as errors', () => {
    const server = read('src', 'lsp', 'server.ts');
    assert.ok(!/loom-lex|loom-parse/.test(server), 'no diagnostic is tagged as coming from the scanner');
    assert.ok(!/DiagnosticSeverity\.(Error|Warning)/.test(server.replace(/^\s*\/\/.*$/gm, '')), 'no severity is given to a scanner finding');
    assert.match(server, /sendDiagnostics\(\{ uri: doc\.uri, diagnostics: \[\] \}\)/);
});

test('R1.1: the extension shows the problems of weave check when a .loom file is opened, saved and closed', () => {
    assert.match(read('src', 'extension.ts'), /registerCheckDiagnostics\(context\)/);
    const wiring = read('src', 'commands', 'checkDiagnostics.ts');
    for (const event of ['onDidOpenTextDocument', 'onDidSaveTextDocument', 'onDidCloseTextDocument']) {
        assert.ok(wiring.includes(event), event);
    }
    assert.match(wiring, /source = 'weave check'/);
    assert.match(wiring, /\.endsWith\('\.loom'\)/);
});

test('the check runs with --no-env, so a key that is not set yet is not a mistake in the script', () => {
    assert.match(read('src', 'check', 'runner.ts'), /'--format', 'json', '--no-env'/);
});

test('the README says problems come from weave check and are refreshed on open and save', () => {
    const readme = read('README.md');
    assert.match(readme, /## Problems/);
    assert.match(readme, /opened and saved/);
    assert.match(readme, /weave check/);
});
