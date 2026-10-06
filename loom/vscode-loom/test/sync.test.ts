import test from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'child_process';
import * as fs from 'fs';
import * as os from 'os';
import * as path from 'path';

const repo = path.join(__dirname, '..', '..', '..', '..');
const check = path.join(repo, 'scripts', 'check-graph-render-sync.sh');
const nodeAvailable = spawnSync('node', ['--version']).status === 0;

test('V10.14: the extension\'s copy of the renderer is the canonical file', { skip: !nodeAvailable }, () => {
    const result = spawnSync('bash', [check], { encoding: 'utf8' });
    assert.equal(result.status, 0, result.stderr);
    assert.match(result.stdout, /in sync/);
});

test('V10.14: a changed copy is caught by the check, and restoring it passes again', { skip: !nodeAvailable }, () => {
    const copy = path.join(repo, 'loom', 'vscode-loom', 'media', 'graph-render.js');
    const original = fs.readFileSync(copy);
    const backup = path.join(os.tmpdir(), 'graph-render.backup.js');
    fs.writeFileSync(backup, original);
    try {
        fs.appendFileSync(copy, '\n// drift\n');
        const drifted = spawnSync('bash', [check], { encoding: 'utf8' });
        assert.equal(drifted.status, 1);
        assert.match(drifted.stderr, /OUT OF SYNC: loom\/vscode-loom\/media\/graph-render\.js/);
    } finally {
        fs.writeFileSync(copy, original);
    }
    assert.equal(spawnSync('bash', [check], { encoding: 'utf8' }).status, 0);
});
