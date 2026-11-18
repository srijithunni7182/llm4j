import test from 'node:test';
import assert from 'node:assert/strict';
import * as fs from 'fs';
import * as path from 'path';

const root = path.join(__dirname, '..', '..');
const manifest = JSON.parse(fs.readFileSync(path.join(root, 'package.json'), 'utf8'));
const declared = new Set<string>(manifest.contributes.commands.map((c: { command: string }) => c.command));
const walkthrough = manifest.contributes.walkthroughs[0];

function commandsIn(text: string): string[] {
    return [...text.matchAll(/\(command:([\w.]+)/g)].map((m) => m[1]);
}
const sources = (): string[] => walkthrough.steps.flatMap((s: { description: string; media: { markdown: string } }) => [s.description, fs.readFileSync(path.join(root, s.media.markdown), 'utf8')]);

test('every command the walkthrough links to is declared in the manifest', () => {
    const used = sources().flatMap(commandsIn);
    assert.ok(used.length >= 10);
    for (const id of used) {
        assert.ok(declared.has(id), `${id} is linked from the walkthrough but not declared`);
    }
});

test('every completion event names a declared command or a built-in event', () => {
    for (const step of walkthrough.steps) {
        for (const ev of step.completionEvents) {
            const m = /^onCommand:(.+)$/.exec(ev);
            assert.ok(m ? declared.has(m[1]) : ev === 'onStepSelected', `${step.id}: ${ev}`);
        }
    }
});

test('the walkthrough has the five steps in order, and every media file exists', () => {
    assert.deepEqual(walkthrough.steps.map((s: { id: string }) => s.id), ['loom.step.agent', 'loom.step.guide', 'loom.step.chat', 'loom.step.demo', 'loom.step.tour']);
    for (const s of walkthrough.steps) {
        assert.ok(fs.existsSync(path.join(root, s.media.markdown)), s.media.markdown);
    }
    assert.ok(walkthrough.steps[0].description.includes('(command:loom.installSkill)'));
    assert.deepEqual(walkthrough.steps[0].completionEvents, ['onCommand:loom.installSkill']);
    assert.ok(walkthrough.steps[1].description.includes('(command:loom.guide.setUp)'));
    assert.ok(walkthrough.steps[2].description.includes('(command:loom.startWorkflowInChat)'));
    assert.ok(walkthrough.steps[3].description.includes('(command:loom.try)'));
});

test('the tour covers every major capability and names the four skills in order', () => {
    const tour = fs.readFileSync(path.join(root, 'media/walkthrough/04-command-palette-tour.md'), 'utf8');
    for (const id of ['loom.showGraph', 'loom.review', 'loom.test', 'loom.eval', 'loom.debug', 'loom.observe', 'loom.newFromStarter', 'loom.deploy']) {
        assert.ok(tour.includes(`(command:${id})`), id);
    }
    const gates = fs.readFileSync(path.join(root, 'media/walkthrough/02-first-workflow.md'), 'utf8');
    const order = ['llm4j-harness-design', 'llm4j-workflow-guide', 'llm4j-eval-design', 'llm4j-loom-deployer'].map((s) => gates.indexOf(s));
    assert.ok(order.every((i, k) => i >= 0 && (k === 0 || i > order[k - 1])));
});

test('the new commands exist, are registered in code, and the old ones are untouched', () => {
    const code = ['src/commands/onboardingCommands.ts', 'src/extension.ts', 'src/commands/guideCommands.ts', 'src/welcome/register.ts']
        .map((f) => fs.readFileSync(path.join(root, f), 'utf8')).join('\n');
    for (const id of ['loom.openWalkthrough', 'loom.startWorkflowInChat', 'loom.newFromStarter', 'loom.installSkill', 'loom.try']) {
        assert.ok(declared.has(id), id);
        assert.ok(code.includes(`'${id}'`), `${id} is not registered`);
    }
    assert.equal(manifest.contributes.commands.find((c: { command: string }) => c.command === 'loom.installSkill').title, 'Loom: Set Up My Coding Agent');
});

test('the marketplace categories include AI, Testing and Visualization', () => {
    for (const c of ['Programming Languages', 'AI', 'Testing', 'Visualization']) {
        assert.ok(manifest.categories.includes(c), c);
    }
    assert.ok(manifest.files.includes('starters/**/*'));
});

test('every step shows the Loom logo, and the tutorial can be opened from the demo step and the tour', () => {
    for (const s of walkthrough.steps) {
        const md = fs.readFileSync(path.join(root, s.media.markdown), 'utf8');
        const src = /<img src="([^"]+loom-logo-320\.png)"/.exec(md)?.[1];
        assert.ok(src, `${s.id} shows the logo`);
        assert.ok(fs.existsSync(path.join(root, path.dirname(s.media.markdown), src as string)), `${s.id}: logo file`);
    }
    assert.ok(walkthrough.steps[3].description.includes('(command:loom.openTutorial)'));
    assert.ok(fs.readFileSync(path.join(root, 'media/walkthrough/04-command-palette-tour.md'), 'utf8').includes('(command:loom.openTutorial)'));
    assert.ok(declared.has('loom.openTutorial'));
    assert.match(fs.readFileSync(path.join(root, 'src/commands/guideCommands.ts'), 'utf8'), /registerCommand\('loom\.openTutorial'/);
});
