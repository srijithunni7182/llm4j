import test from 'node:test';
import assert from 'node:assert/strict';
import * as fs from 'fs';
import * as path from 'path';
import { adapterArgs, launchFor, liveWarning, resolveLaunch } from '../src/debug/debugConfig';
import { codeLensFor } from '../src/review/presentation';

const root = path.join(__dirname, '..', '..');
const manifest = JSON.parse(fs.readFileSync(path.join(root, 'package.json'), 'utf8'));

test('R41: F5 on a .loom file with no launch.json creates a simulated, mock-model configuration', () => {
    const r = resolveLaunch({}, { file: '/p/triage.loom' });
    assert.deepEqual(r.config, launchFor('/p/triage.loom'));
    assert.equal(r.config?.effects, 'simulate');
    assert.equal(r.config?.models, 'mock');
    assert.equal(r.config?.stopOnEntry, true);
    assert.equal(r.config?.name, 'Debug triage.loom');
});

test('R41: with no .loom file open there is nothing to debug, and it says so', () => {
    assert.match(resolveLaunch({}, {}).problem!, /Open a \.loom file/);
    assert.match(resolveLaunch(undefined, { file: '/p/notes.md' }).problem!, /Open a \.loom file/);
});

test('a workflow chosen from the CodeLens is named in the configuration', () => {
    const c = launchFor('/p/a.loom', 'Triage');
    assert.equal(c.workflow, 'Triage');
    assert.equal(c.name, 'Debug Triage (a.loom)');
});

test('a written configuration is kept, and anything it leaves out gets the safe default', () => {
    const r = resolveLaunch({ type: 'loom', request: 'launch', script: '/p/a.loom', workflow: 'W', input: { n: 3 }, mask: ['secret'] }, {});
    assert.deepEqual(r.config?.input, { n: '3' });
    assert.deepEqual(r.config?.mask, ['secret']);
    assert.equal(r.config?.models, 'mock');
    assert.equal(r.config?.effects, 'simulate');
});

test('R40: real effects and unknown model modes are refused with the reason', () => {
    assert.match(resolveLaunch({ type: 'loom', request: 'launch', script: '/p/a.loom', effects: 'real' }, {}).problem!, /simulated effects/);
    assert.match(resolveLaunch({ type: 'loom', request: 'launch', script: '/p/a.loom', models: 'gpt' }, {}).problem!, /mock, scripted or live/);
    assert.match(resolveLaunch({ type: 'dotnet', request: 'launch' }, {}).problem!, /"type": "loom"/);
    assert.match(resolveLaunch({ type: 'loom', request: 'launch', script: '/p/a.txt' }, {}).problem!, /\.loom file/);
});

test('R40: live models need a cap, and a money cap needs prices', () => {
    const live = { type: 'loom', request: 'launch', script: '/p/a.loom', models: 'live' };
    assert.match(resolveLaunch(live, {}).problem!, /spend money/);
    assert.match(resolveLaunch({ ...live, maxCost: 1 }, {}).problem!, /needs "prices"/);
    assert.ok(resolveLaunch({ ...live, maxTokens: 500 }, {}).config);
    assert.ok(resolveLaunch({ ...live, maxCost: '0.5', prices: 'prices.txt' }, {}).config);
    assert.match(resolveLaunch({ ...live, maxTokens: -3 }, {}).problem!, /spend money/);
});

test('R40: only live models ask for confirmation, and the question says how much', () => {
    assert.equal(liveWarning(launchFor('/p/a.loom')), undefined);
    const live = resolveLaunch({ type: 'loom', request: 'launch', script: '/p/a.loom', models: 'live', maxCost: 2, prices: 'p.txt' }, {}).config!;
    assert.match(liveWarning(live)!, /may spend up to \$2/);
    assert.match(liveWarning({ ...live, maxCost: undefined, maxTokens: 900 })!, /900 tokens/);
});

test('the adapter is the bundled engine run as `weave dap`, arguments as elements', () => {
    assert.deepEqual(adapterArgs('/x/weave.jar'), ['-jar', '/x/weave.jar', 'dap']);
});

test('R41: the manifest contributes the loom debugger, its breakpoints, and the command with a palette entry', () => {
    const debuggers = manifest.contributes.debuggers;
    assert.equal(debuggers.length, 1);
    assert.equal(debuggers[0].type, 'loom');
    assert.deepEqual(debuggers[0].languages, ['loom']);
    assert.deepEqual(debuggers[0].configurationAttributes.launch.required, ['script']);
    assert.deepEqual(debuggers[0].configurationAttributes.launch.properties.effects.enum, ['simulate']);
    assert.deepEqual(manifest.contributes.breakpoints, [{ language: 'loom' }]);
    assert.ok(manifest.contributes.commands.some((c: { command: string; title: string }) => c.command === 'loom.debug' && c.title === 'Loom: Debug Workflow'));
    const entry = manifest.contributes.menus.commandPalette.find((m: { command: string }) => m.command === 'loom.debug');
    assert.equal(entry.when, 'resourceLangId == loom');
    for (const initial of debuggers[0].initialConfigurations) {
        assert.equal(initial.effects, 'simulate');
        assert.equal(initial.models, 'mock');
    }
    // contributing a debugger activates the extension by itself; the activation events stay as they were
    assert.deepEqual(manifest.activationEvents, ['onLanguage:loom', 'onLanguage:loot']);
});

test('R41: the extension registers the debugger and the command, and the CodeLens offers Debug above each workflow', () => {
    const source = fs.readFileSync(path.join(root, 'src', 'debug', 'vscodeDebug.ts'), 'utf8');
    assert.match(source, /registerCommand\('loom\.debug'/);
    assert.match(source, /registerDebugAdapterDescriptorFactory/);
    assert.match(source, /registerDebugConfigurationProvider/);
    assert.match(fs.readFileSync(path.join(root, 'src', 'extension.ts'), 'utf8'), /registerDebug\(context\)/);
    const lens = codeLensFor(undefined, [{ name: 'Main', file: '/p/a.loom', line: 4 }], '/p/a.loom');
    const debug = lens.find((l) => l.title === 'Debug')!;
    assert.equal(debug.command, 'loom.debug');
    assert.deepEqual(debug.args, ['/p/a.loom', 'Main']);
    assert.equal(debug.line, 3);
});
