import { strict as assert } from 'assert';
import * as path from 'path';
import { test } from 'node:test';
import { definitionOf, elementAt, outline, referencesOf, signatureAt, workflowAt } from '../src/semantic/queries';
import { at, fixtureIndex, fixtureText, SCRIPTS } from './semanticSupport';

const index = fixtureIndex('main');
const main = path.join(SCRIPTS, 'main.loom');
const text = fixtureText('main.loom');

test('R22: go to definition finds an agent from its delegate, in this file and in an import', () => {
    const replier = elementAt(index, main, at(text, 'to Replier', 4));
    assert.equal(replier?.key, 'agent:Replier');
    const here = definitionOf(index, replier!);
    assert.equal(here.length, 1);
    assert.equal(here[0].file, main);
    assert.equal(here[0].range.start.line, text.split('\n').findIndex((l) => l.startsWith('agent Replier')));

    const helper = elementAt(index, main, at(text, 'to Helper', 4));
    const there = definitionOf(index, helper!);
    assert.match(there[0].file, /lib[\\/]agents\.loom$/);
});

test('R22: every kind the language names can be found from where it is used', () => {
    const cases: Array<[string, number, string]> = [
        ['intentclassify Intent', 'intentclassify '.length + 1, 'classifier:Intent'],
        ['decide Refund', 'decide '.length + 1, 'decision:Refund'],
        ['call Escalate', 'call '.length + 1, 'workflow:Escalate'],
        ['rewind to gathered', 'rewind to '.length + 1, 'checkpoint:Main/gathered'],
        ['tools: [Mailer]', 'tools: ['.length + 1, 'tool:Mailer'],
        ['proposed by: Scorer', 'proposed by: '.length + 1, 'agent:Scorer'],
    ];
    for (const [needle, inside, key] of cases) {
        const e = elementAt(index, main, at(text, needle, inside));
        assert.equal(e?.key, key, needle);
        assert.ok(definitionOf(index, e!).length >= 1, `${needle} has a definition`);
    }
});

test('R22: a prompt file and an import open their file', () => {
    const prompt = elementAt(index, main, at(text, '"writer"', 2));
    assert.equal(prompt?.kind, 'prompt');
    assert.match(definitionOf(index, prompt!)[0].file, /prompts[\\/]writer\.md$/);
    const imp = elementAt(index, main, at(text, 'lib/agents.loom', 2));
    assert.equal(imp?.kind, 'file');
    assert.match(definitionOf(index, imp!)[0].file, /agents\.loom$/);
});

test('R22: a variable goes to where it is made, from a condition and from a {placeholder}', () => {
    const fromCondition = elementAt(index, main, at(text, 'intent.kind', 2));
    assert.equal(fromCondition?.key, 'binding:Main/intent');
    const made = definitionOf(index, fromCondition!);
    assert.equal(text.split('\n')[made[0].range.start.line].trim(), 'intentclassify Intent on message -> intent');

    const fromPlaceholder = elementAt(index, main, at(text, '{reply}', 2));
    assert.equal(fromPlaceholder?.key, 'binding:Main/reply');
    assert.equal(definitionOf(index, fromPlaceholder!).length, 1);
});

test('R22: a task or an undefined name has no definition, and says so', () => {
    const task = elementAt(index, main, at(text, 'run Archive', 5));
    assert.equal(task?.kind, 'task');
    assert.deepEqual(definitionOf(index, task!), []);
});

test('R22: references include every use, with or without the definition', () => {
    const uses = referencesOf(index, 'agent:Scorer', false);
    assert.deepEqual(uses.map((u) => path.basename(u.file)), ['main.loom', 'main.loom']); // proposed by, delegate
    const withDefinition = referencesOf(index, 'agent:Scorer', true);
    assert.equal(withDefinition.length, 3);
    assert.ok(withDefinition.some((l) => /agents\.loom$/.test(l.file)), 'the definition in the import');
    // a variable: made once, used in the condition-free places
    const reply = referencesOf(index, 'binding:Main/reply', true);
    assert.ok(reply.length >= 4);
});

test('R22: the element under the cursor is the innermost one, and the end of a word still counts', () => {
    const e = elementAt(index, main, at(text, 'Replier', 'Replier'.length, 1));
    assert.equal(e?.key, 'agent:Replier');
    assert.equal(elementAt(index, main, at(text, 'workflow Main', 2)), undefined, 'a keyword is not a symbol');
});

test('R22: the outline lists blocks with their checkpoints and variables inside workflows', () => {
    const items = outline(index, main);
    assert.deepEqual(items.map((i) => `${i.kind}:${i.name}`), ['classifier:Intent', 'decision:Refund', 'agent:Replier', 'workflow:Main', 'workflow:Escalate']);
    const wf = items.find((i) => i.name === 'Main')!;
    assert.ok(wf.children.some((c) => c.kind === 'checkpoint' && c.name === 'gathered'));
    assert.ok(wf.children.some((c) => c.name === 'message' && c.detail === 'parameter'));
    assert.ok(wf.range.end.line > wf.selection.end.line, 'the block is larger than its name');
    // the imported file's outline is its own
    assert.deepEqual(outline(index, path.join(SCRIPTS, 'lib', 'agents.loom')).map((i) => i.name), ['Mailer', 'Helper', 'Scorer']);
});

test('R22: signature help names the parameters of the workflow being called and the one being typed', () => {
    assert.deepEqual(signatureAt(index, '    call Escalate(')?.parameters, ['memo']);
    const two = signatureAt(index, 'call Main(message = x, ');
    assert.equal(two?.label, 'Main(message, tone)');
    assert.equal(two?.active, 1);
    assert.equal(signatureAt(index, 'call Main(tone = x')?.active, 1, 'by the name being written');
    assert.equal(signatureAt(index, 'call Nowhere('), undefined);
    assert.equal(signatureAt(index, 'delegate "x" to A -> r'), undefined);
});

test('R22: the workflow around a place is found', () => {
    assert.equal(workflowAt(index, main, at(text, 'checkpoint gathered')), 'Main');
    assert.equal(workflowAt(index, main, at(text, 'Escalate: {memo}')), 'Escalate');
    assert.equal(workflowAt(index, main, at(text, 'agent Replier')), undefined);
});
