import test from 'node:test';
import assert from 'node:assert/strict';
import * as fs from 'fs';
import * as path from 'path';
import {
    applyArgs,
    buildArgs,
    DEFAULT_SETTINGS,
    editArgs,
    EventReader,
    exitMeaning,
    folderFor,
    isWorse,
    patchedFiles,
    planArgs,
    progressText,
    questionIn,
    requestWith,
    resultOf,
} from '../src/generate/generateFlow';

const root = path.join(__dirname, '..', '..');
const manifest = JSON.parse(fs.readFileSync(path.join(root, 'package.json'), 'utf8'));

test('R33: the plan is asked for alone, with the proposed answers, as events', () => {
    assert.deepEqual(planArgs('triage tickets', '/tmp/p', DEFAULT_SETTINGS), ['generate', 'triage tickets', '--plan-only', '--no-ask', '--out', '/tmp/p', '--format', 'json']);
});

test('R33: the project is built from the edited plan into a new folder, and only on that', () => {
    assert.deepEqual(buildArgs('/tmp/p/plan.json', '/w/triage', DEFAULT_SETTINGS), ['generate', '--from-plan', '/tmp/p/plan.json', '--out', '/w/triage', '--yes', '--format', 'json']);
});

test('the settings become options, and an unset one is left to the command', () => {
    const s = { model: ' claude-haiku-5-5 ', maxCost: 0.25, maxTokens: 90000.7, prices: 'prices.txt' };
    const args = planArgs('x', '/p', s);
    assert.deepEqual(args.slice(args.indexOf('--model')), ['--model', 'claude-haiku-5-5', '--max-cost', '0.25', '--max-tokens', '90000', '--prices', 'prices.txt', '--format', 'json']);
    assert.ok(!planArgs('x', '/p', DEFAULT_SETTINGS).includes('--model'));
    assert.ok(!planArgs('x', '/p', DEFAULT_SETTINGS).includes('--max-cost'));
});

test('R33: a change is made into a patch file and applied later from it, with --accept-worse only when the person agreed', () => {
    assert.deepEqual(editArgs('/w/main.loom', 'add approval', '/tmp/patch.json', DEFAULT_SETTINGS), ['generate', '--edit', '/w/main.loom', 'add approval', '--patch-out', '/tmp/patch.json', '--format', 'json']);
    assert.deepEqual(applyArgs('/w/main.loom', '/tmp/patch.json', false), ['generate', '--edit', '/w/main.loom', '--apply-patch', '/tmp/patch.json', '--apply', '--format', 'json']);
    assert.ok(applyArgs('/w/main.loom', '/tmp/patch.json', true).includes('--accept-worse'));
});

test('the request carries the selected text as the part it is about, or nothing when none is selected', () => {
    assert.equal(requestWith('  make it calmer ', undefined), 'make it calmer');
    assert.equal(requestWith('make it calmer', '   '), 'make it calmer');
    assert.match(requestWith('make it calmer', 'agent A { }'), /make it calmer\n\nThe part of the script this is about:\nagent A \{ \}/);
});

test('events are read from whatever pieces the process prints, and a stray line is kept as text', () => {
    const r = new EventReader();
    assert.deepEqual(r.push('{"event":"stage","stage":"pl'), []);
    const events = r.push('an"}\nnoise here\n{"event":"result","exit":0,"text":"done"}\n');
    assert.deepEqual(events.map((e) => e.event), ['stage', 'result']);
    assert.deepEqual(r.text, ['noise here']);
    assert.deepEqual(resultOf(events), { exit: 0, text: 'done' });
    assert.equal(resultOf([]), undefined);
});

test('while it works the person reads words, not events', () => {
    assert.equal(progressText({ event: 'stage', stage: 'write' }), 'Writing the files…');
    assert.equal(progressText({ event: 'attempt', n: 1, of: 8 }), undefined);
    assert.equal(progressText({ event: 'attempt', n: 2, of: 8 }), 'Repairing (attempt 2 of 8)…');
    assert.equal(progressText({ event: 'gate', gate: 'G2', name: 'review', passed: false, problems: 3 }), 'G2 review did not pass (3)');
    assert.equal(progressText({ event: 'gate', gate: 'G1', name: 'check', passed: true, problems: 0 }), undefined);
});

test('the exit codes mean what the command says they mean', () => {
    assert.equal(exitMeaning(0), 'done');
    assert.match(exitMeaning(3), /gate/);
    assert.match(exitMeaning(4), /spend cap/);
    assert.match(exitMeaning(2), /could not run/);
    assert.match(exitMeaning(9), /9/);
});

test('a new project folder is named after the workflow', () => {
    assert.equal(folderFor('SupportNotify'), 'support-notify');
    assert.equal(folderFor('!!!'), 'workflow');
});

test('a change held back as worse, and a question instead of a guess, are told from the text', () => {
    assert.ok(isWorse('x\n⚠ This change makes the project WORSE than it was (see G2 above).'));
    assert.ok(!isWorse('✓ all fine'));
    assert.equal(questionIn('The change is ambiguous, so nothing was guessed. The question:\n  Approved by whom?\nAnswer it with: ...'), 'Approved by whom?');
    assert.equal(questionIn('fine'), undefined);
});

test('the files of a saved patch are read, and anything else is refused', () => {
    assert.deepEqual(patchedFiles('{"version":1,"files":{"main.loom":"x\\n","bad":3}}'), { 'main.loom': 'x\n' });
    assert.equal(patchedFiles('not json'), undefined);
    assert.equal(patchedFiles('{"version":1}'), undefined);
});

test('R33: the manifest has both commands, their settings, and the registration', () => {
    const titles = manifest.contributes.commands.map((c: { command: string; title: string }) => `${c.command}=${c.title}`);
    assert.ok(titles.includes('loom.generate=Loom: Generate Workflow...'));
    assert.ok(titles.includes('loom.change=Loom: Change Workflow...'));
    const props = manifest.contributes.configuration.properties;
    assert.equal(props['loom.generate.model'].default, '');
    assert.equal(props['loom.generate.maxCost'].minimum, 0);
    const change = manifest.contributes.menus.commandPalette.find((m: { command: string }) => m.command === 'loom.change');
    assert.equal(change.when, 'resourceLangId == loom');
    const source = fs.readFileSync(path.join(root, 'src', 'generate', 'vscodeGenerate.ts'), 'utf8');
    assert.match(source, /registerCommand\('loom\.generate'/);
    assert.match(source, /registerCommand\('loom\.change'/);
    assert.match(fs.readFileSync(path.join(root, 'src', 'extension.ts'), 'utf8'), /registerGenerate\(context\)/);
});

test('the model is only ever called by the generate process, never by the checking engine', () => {
    const source = fs.readFileSync(path.join(root, 'src', 'generate', 'vscodeGenerate.ts'), 'utf8');
    assert.doesNotMatch(source, /engineHost|ProcessEngineClient|loom\/generate/);
    assert.match(source, /spawn\(configuredJavaPath\(\), \['-jar', jar, \.\.\.args\]/);
    // arguments are elements, never a shell string
    assert.doesNotMatch(source, /exec\(|shell: true/);
});
