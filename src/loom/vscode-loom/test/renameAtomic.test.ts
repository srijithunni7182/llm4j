import { strict as assert } from 'assert';
import * as path from 'path';
import { test } from 'node:test';
import { planRename, prepareRename, FileFacts, RenamePlan } from '../src/semantic/queries';
import { SymbolIndex } from '../src/semantic/symbols';
import { at, fixtureIndex, fixtureText, SCRIPTS } from './semanticSupport';

const index = fixtureIndex('main');
const main = path.join(SCRIPTS, 'main.loom');
const text = fixtureText('main.loom');
const everythingWritable: FileFacts = { isWritable: () => true, exists: () => false };

function plan(needle: string, inside: number, newName: string, facts: FileFacts = everythingWritable, from: SymbolIndex = index): RenamePlan {
    return planRename(from, main, at(text, needle, inside), newName, facts);
}

function refused(p: RenamePlan): string {
    assert.equal(p.ok, false, 'the rename should be refused');
    return p.ok ? '' : p.reason;
}

test('R23: renaming an agent edits its definition in the import and every use', () => {
    const p = plan('to Scorer', 4, 'Judge');
    assert.ok(p.ok);
    if (!p.ok) { return; }
    assert.ok(p.edits.every((e) => e.newText === 'Judge'));
    // the definition (in the import), `proposed by`, and the delegate
    assert.equal(p.edits.length, 3);
    assert.ok(p.edits.some((e) => /agents\.loom$/.test(e.file)));
    assert.deepEqual(p.renames, []);
});

test('R23: renaming from the definition and from a use give the same edits', () => {
    const fromUse = plan('to Scorer', 4, 'Judge');
    const agents = fixtureText('lib/agents.loom');
    const fromDefinition = planRename(index, path.join(SCRIPTS, 'lib', 'agents.loom'), at(agents, 'agent Scorer', 8), 'Judge', everythingWritable);
    assert.ok(fromUse.ok && fromDefinition.ok);
    if (fromUse.ok && fromDefinition.ok) {
        assert.deepEqual(fromUse.edits, fromDefinition.edits);
    }
});

test('R23: a variable is renamed everywhere it is used, in conditions and in {placeholders}', () => {
    const p = plan('-> reply', 4, 'answer');
    assert.ok(p.ok);
    if (!p.ok) { return; }
    // made once, used in: "Score {reply}", `call ... (memo = reply)`, `run ... (text = reply)`, `return reply`
    assert.equal(p.edits.length, 5);
    const lines = text.split('\n');
    for (const e of p.edits) {
        assert.equal(lines[e.range.start.line].slice(e.range.start.character, e.range.end.character), 'reply');
    }
    // the other workflow's `done`/`memo` and the parameter `message` are untouched
    assert.ok(p.edits.every((e) => e.file === main));
});

test('R23: a variable named like one in another workflow is a different variable', () => {
    const p = plan('workflow Escalate(memo)', 'workflow Escalate('.length + 1, 'text');
    assert.ok(p.ok);
    if (p.ok) {
        // the parameter and its {memo} use in Escalate only; `memo = reply` in Main is the callee's parameter name
        assert.equal(p.edits.length, 2);
    }
});

test('R23: a checkpoint is renamed with its rewinds, within its workflow', () => {
    const p = plan('checkpoint gathered', 'checkpoint '.length + 1, 'begin');
    assert.ok(p.ok);
    if (p.ok) {
        assert.equal(p.edits.length, 2);
    }
});

test('R23: a prompt rename edits the id and asks to rename the file', () => {
    const p = plan('"writer"', 2, 'drafter');
    assert.ok(p.ok);
    if (!p.ok) { return; }
    assert.equal(p.edits.length, 1);
    assert.equal(p.renames.length, 1);
    assert.match(p.renames[0].from, /prompts[\\/]writer\.md$/);
    assert.match(p.renames[0].to, /prompts[\\/]drafter\.md$/);
    assert.match(refused(plan('"writer"', 2, 'drafter', { isWritable: () => true, exists: (f) => /drafter\.md$/.test(f) })), /already exists/);
});

test('R23: nothing changes if any file to be edited is read-only, and the message names it', () => {
    const readOnlyImport: FileFacts = { isWritable: (f) => !/agents\.loom$/.test(f), exists: () => false };
    const reason = refused(plan('to Scorer', 4, 'Judge', readOnlyImport));
    assert.match(reason, /Nothing was changed/);
    assert.match(reason, /agents\.loom/);
});

test('R23: nothing changes if an import cannot be found, since uses might be missed', () => {
    const broken: SymbolIndex = { ...index, references: [...index.references, { to: 'file:/nowhere/lost.loom', kind: 'file', role: 'import', file: main, range: index.references[0].range, resolved: false }] };
    assert.match(refused(plan('to Scorer', 4, 'Judge', everythingWritable, broken)), /import cannot be found.*lost\.loom/);
});

test('R23: a name that is taken, a keyword, a bad name, or the same name is refused', () => {
    assert.match(refused(plan('to Scorer', 4, 'Helper')), /already used by another agent/);
    assert.match(refused(plan('to Scorer', 4, 'delegate')), /keyword/);
    assert.match(refused(plan('to Scorer', 4, '9lives')), /not a valid name/);
    assert.match(refused(plan('to Scorer', 4, 'has space')), /not a valid name/);
    assert.match(refused(plan('to Scorer', 4, 'Scorer')), /same as the old/);
    assert.match(refused(plan('-> reply', 4, 'message')), /already used by another variable/);
    assert.match(refused(plan('-> reply', 4, 'my-var')), /not a valid name/);
});

test('R23: an agent and a workflow may share a name, a hyphenated agent name is fine', () => {
    assert.ok(plan('to Scorer', 4, 'Escalate').ok, 'a different kind');
    assert.ok(plan('to Scorer', 4, 'score-keeper').ok);
});

test('R23: what the script does not define is not renamed, and the reason says what to do', () => {
    assert.match(refused(plan('run Archive', 5, 'Store')), /supplied by the host/);
    assert.match(refused(plan('lib/agents.loom', 2, 'other')), /Explorer/);
    assert.match(refused(plan('workflow Main', 2, 'X')), /Nothing here/);
});

test('R23: prepareRename gives the range and the current name, or the reason', () => {
    const ok = prepareRename(index, main, at(text, 'to Scorer', 4));
    assert.ok('placeholder' in ok);
    if ('placeholder' in ok) {
        assert.equal(ok.placeholder, 'Scorer');
    }
    assert.ok('reason' in prepareRename(index, main, at(text, 'run Archive', 5)));
});
