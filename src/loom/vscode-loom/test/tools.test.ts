import test from 'node:test';
import assert from 'node:assert/strict';
import * as fs from 'fs';
import * as path from 'path';
import { DEPLOY_TARGETS, EVAL_MODES, chooseScript, deployPlan, evalPlan, manPlan, nextPlan, observePlan, testPlan } from '../src/tools/tools';

const root = path.join(__dirname, '..', '..');
const manifest = JSON.parse(fs.readFileSync(path.join(root, 'package.json'), 'utf8'));

test('each button is one weave command for the script', () => {
    assert.deepEqual(testPlan('/p/main.loom').args, ['test', '/p/main.loom', '--coverage']);
    assert.deepEqual(evalPlan('/p/main.loom', 'mock').args, ['eval', '/p/main.loom', '--mock']);
    assert.deepEqual(evalPlan('/p/main.loom', 'check').args, ['eval', '/p/main.loom', '--check']);
    assert.deepEqual(evalPlan('/p/main.loom', 'init').args, ['eval', '/p/main.loom', '--init']);
    assert.deepEqual(deployPlan('/p/main.loom', 'k8s').args, ['deploy', '/p/main.loom', '--target', 'k8s']);
    assert.deepEqual(nextPlan('/p').args, ['next', '/p']);
    assert.deepEqual(manPlan().args, ['man']);
});

test('the free choices are free: nothing but the live run asks first or spends', () => {
    for (const p of [testPlan('a.loom'), evalPlan('a.loom', 'mock'), evalPlan('a.loom', 'check'), evalPlan('a.loom', 'init'), deployPlan('a.loom', 'docker'), nextPlan('.'), manPlan()]) assert.equal(p.confirm, undefined);
    assert.ok(EVAL_MODES.every((m) => /^Free/.test(m.detail)));
});

test('the live run asks first, names the file, says it spends, and is capped by tokens', () => {
    const p = observePlan('/work/refunds/main.loom', 20000);
    assert.deepEqual(p.args, ['run', '/work/refunds/main.loom', '--observe', '--max-tokens', '20000']);
    assert.match(p.confirm ?? '', /main\.loom/);
    assert.match(p.confirm ?? '', /spends tokens/);
    assert.match(p.confirm ?? '', /20000/);
    // a cap of nothing is not a cap
    assert.equal(observePlan('a.loom', 5).args[4], '1000');
});

test('the script is the open one, else the only one in the folder, else the person picks', () => {
    assert.deepEqual(chooseScript('/p/a.loom', ['/p/b.loom', '/p/c.loom']), { script: '/p/a.loom' });
    assert.deepEqual(chooseScript(undefined, ['/p/b.loom']), { script: '/p/b.loom' });
    assert.deepEqual(chooseScript(undefined, ['/p/c.loom', '/p/b.loom']), { pick: ['/p/b.loom', '/p/c.loom'] });
    assert.deepEqual(chooseScript(undefined, []), { none: true });
});

test('every target of deploy is one weave deploy accepts', () => {
    assert.deepEqual(DEPLOY_TARGETS.map((t) => t.target), ['docker', 'compose', 'k8s']);
});

test('the commands are declared, registered by the extension, and the live run is capped by a setting', () => {
    const titles = Object.fromEntries(manifest.contributes.commands.map((c: { command: string; title: string }) => [c.command, c.title]));
    const want = { 'loom.test': 'Loom: Run Tests', 'loom.eval': 'Loom: Evaluate Workflow...', 'loom.observe': 'Loom: Run With Live Page...', 'loom.deploy': 'Loom: Write Deployment Files...', 'loom.next': 'Loom: What Should I Do Next?', 'loom.commands': 'Loom: Show All Commands (weave man)' };
    for (const [id, title] of Object.entries(want)) assert.equal(titles[id], title, id);
    const src = fs.readFileSync(path.join(root, 'src', 'tools', 'register.ts'), 'utf8');
    for (const id of Object.keys(want)) assert.ok(src.includes(`'${id}'`), id);
    assert.ok(fs.readFileSync(path.join(root, 'src', 'extension.ts'), 'utf8').includes('registerTools(context)'));
    assert.equal(manifest.contributes.configuration.properties['loom.observe.maxTokens'].default, 50000);
    assert.ok(!/spawn|execFile|child_process/.test(src), 'these buttons type into a terminal; they start no process of their own');
});
