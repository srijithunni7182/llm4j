import test from 'node:test';
import assert from 'node:assert/strict';
import { execFileSync, spawnSync } from 'child_process';
import * as fs from 'fs';
import * as os from 'os';
import * as path from 'path';

const repo = path.join(__dirname, '..', '..', '..', '..');
const script = path.join(repo, 'scripts', 'make-logo-assets.sh');
const hasConvert = spawnSync('convert', ['-version']).status === 0;

function size(file: string): { width: number; height: number } {
    const bytes = fs.readFileSync(file);
    return { width: bytes.readUInt32BE(16), height: bytes.readUInt32BE(20) };
}

test('V9.7: the logo script makes a 128 px mark and a 320 px logo, and the same bytes every time', { skip: !hasConvert && 'ImageMagick is not installed' }, () => {
    const a = fs.mkdtempSync(path.join(os.tmpdir(), 'logo-a-'));
    const b = fs.mkdtempSync(path.join(os.tmpdir(), 'logo-b-'));
    execFileSync('bash', [script, a]);
    execFileSync('bash', [script, b]);
    assert.deepEqual(size(path.join(a, 'loom-mark-128.png')), { width: 128, height: 128 });
    assert.deepEqual(size(path.join(a, 'loom-logo-320.png')), { width: 320, height: 320 });
    for (const name of ['loom-mark-128.png', 'loom-logo-320.png']) {
        assert.ok(fs.readFileSync(path.join(a, name)).equals(fs.readFileSync(path.join(b, name))), `${name} differs between runs`);
    }
});

test('the committed logos are what the script makes from the project logo', { skip: !hasConvert && 'ImageMagick is not installed' }, () => {
    const out = fs.mkdtempSync(path.join(os.tmpdir(), 'logo-c-'));
    execFileSync('bash', [script, out]);
    for (const name of ['loom-mark-128.png', 'loom-logo-320.png']) {
        assert.ok(fs.readFileSync(path.join(out, name)).equals(fs.readFileSync(path.join(__dirname, '..', '..', 'media', name))), `${name} is out of date: run scripts/make-logo-assets.sh`);
    }
});

test('the logo source exists', () => {
    assert.ok(fs.existsSync(path.join(repo, 'loom', 'ai-agent4j-loom', 'loom_logo.png')));
});
