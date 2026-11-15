import test from 'node:test';
import assert from 'node:assert/strict';
import * as fs from 'fs';
import * as os from 'os';
import * as path from 'path';
import { STARTERS, copyStarter, findStartersDir, pickItems } from '../src/starters/starterGallery';

const ext = path.join(__dirname, '..', '..');
const startersDir = findStartersDir(ext) as string;

test('the starters are found next to the extension when it runs from the repository', () => {
    assert.ok(startersDir, 'starters directory');
});

test('the gallery lists exactly the starter folders there are, each with a main.loom', () => {
    const folders = fs.readdirSync(startersDir).filter((f) => /^\d\d-/.test(f)).sort();
    assert.deepEqual(STARTERS.map((s) => s.folder).sort(), folders);
    for (const f of folders) {
        assert.ok(fs.existsSync(path.join(startersDir, f, 'main.loom')), f);
    }
});

test('the quick pick is grouped: a separator per category, with the requested categories present', () => {
    const items = pickItems();
    const seps = items.filter((i) => i.kind === 'separator').map((i) => i.label);
    assert.equal(new Set(seps).size, seps.length, 'each category appears once');
    for (const c of ['Human-in-the-loop approvals', 'Classifier routing', 'Parallel fan-out', 'Budgeted agents']) {
        assert.ok(seps.includes(c), c);
    }
    assert.equal(items.filter((i) => i.kind === 'starter').length, STARTERS.length);
});

test('copying a starter writes its main.loom, tests and no .env, and returns the script to open', () => {
    const ws = fs.mkdtempSync(path.join(os.tmpdir(), 'loom-starter-'));
    const starter = STARTERS.find((s) => s.folder === '07-approval')!;
    const main = copyStarter(startersDir, starter, ws);
    assert.equal(main, path.join(ws, '07-approval', 'main.loom'));
    assert.ok(fs.existsSync(path.join(ws, '07-approval', 'tests', 'main.test.yaml')));
    assert.ok(!fs.existsSync(path.join(ws, '07-approval', '.env')));
});

test('copying never overwrites existing work', () => {
    const ws = fs.mkdtempSync(path.join(os.tmpdir(), 'loom-starter-'));
    fs.mkdirSync(path.join(ws, '01-hello'));
    fs.writeFileSync(path.join(ws, '01-hello', 'main.loom'), 'mine');
    assert.throws(() => copyStarter(startersDir, STARTERS[0], ws), /already exists/);
    assert.equal(fs.readFileSync(path.join(ws, '01-hello', 'main.loom'), 'utf8'), 'mine');
});
