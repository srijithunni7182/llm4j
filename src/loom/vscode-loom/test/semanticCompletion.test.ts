import { strict as assert } from 'assert';
import * as path from 'path';
import { test } from 'node:test';
import { suggest, Suggestions } from '../src/semantic/completion';
import { at, fixtureIndex, fixtureText, SCRIPTS, typed } from './semanticSupport';

const index = fixtureIndex('main');
const main = path.join(SCRIPTS, 'main.loom');
const text = fixtureText('main.loom');

/** Types `insert` where `needle` is (replacing nothing) and asks what can be written at the cursor. */
function complete(needle: string, insert: string, inside = 0): Suggestions | undefined {
    const t = typed(text, at(text, needle, inside), insert);
    return suggest(index, main, t.text, t.cursor);
}

function labels(s: Suggestions | undefined): string[] {
    assert.ok(s, 'the place should be one the index knows');
    return s!.items.map((i) => i.label).sort();
}

test('R24: after `delegate ... to` only agents are offered, from this file and its imports', () => {
    const s = complete('        delegate "Reply to', '', 0);
    assert.equal(s, undefined, 'a word inside a prompt string is not syntax');
    const t = typed(text, at(text, 'workflow Escalate(memo) {\n'), '');
    const probe = typed(t.text, at(t.text, '    return done'), '    delegate "x" to ');
    assert.deepEqual(labels(suggest(index, main, probe.text, probe.cursor)), ['Helper', 'Replier', 'Scorer']);
});

test('R24: only the agents that start with what is typed are replaced, by length', () => {
    const t = typed(text, at(text, '    return done'), '    delegate "x" to Sc');
    const s = suggest(index, main, t.text, t.cursor)!;
    assert.equal(s.replaceLength, 2);
});

test('R24: a rewind offers the checkpoints of its own workflow', () => {
    const t = typed(text, at(text, '        run Archive'), '        rewind to ');
    assert.deepEqual(labels(suggest(index, main, t.text, t.cursor)), ['gathered']);
    const other = typed(text, at(text, '    return done'), '    rewind to ');
    assert.deepEqual(labels(suggest(index, main, other.text, other.cursor)), [], 'Escalate has no checkpoints');
});

test('R24: call, intentclassify, decide and tool lists offer their own kind', () => {
    const line = (insert: string): string[] => {
        const t = typed(text, at(text, '        run Archive'), insert);
        return labels(suggest(index, main, t.text, t.cursor));
    };
    assert.deepEqual(line('        call '), ['Escalate', 'Main']);
    assert.deepEqual(line('        intentclassify '), ['Intent']);
    assert.deepEqual(line('        decide '), ['Refund']);
    assert.deepEqual(line('        run '), ['Archive'], 'tasks the script already runs');
    const t = typed(text, at(text, 'agent Replier'), 'agent Other { model: "m" tools: [');
    assert.deepEqual(labels(suggest(index, main, t.text, t.cursor)), ['Mailer']);
});

test('R24: `intent.` offers the fields of the classifier record with their types', () => {
    const t = typed(text, at(text, '        run Archive'), '        note intent.');
    const s = suggest(index, main, t.text, t.cursor)!;
    assert.deepEqual(s.items.map((i) => i.label).sort(), ['backend', 'calibrated', 'confidence', 'kind', 'model', 'urgent']);
    assert.equal(s.items.find((i) => i.label === 'kind')!.detail, 'order_status | refund | other | unsure');
    assert.equal(s.items.find((i) => i.label === 'urgent')!.detail, 'yes | no');
    assert.equal(s.items.find((i) => i.label === 'confidence')!.detail, 'number');
});

test('R24: a field being typed replaces the partial word', () => {
    const t = typed(text, at(text, '        run Archive'), '        note intent.ki');
    assert.equal(suggest(index, main, t.text, t.cursor)!.replaceLength, 2);
});

test('R24: a delegate result with an output_schema offers its fields; plain text offers none', () => {
    const verdict = typed(text, at(text, '        run Archive'), '        note verdict.');
    assert.deepEqual(labels(suggest(index, main, verdict.text, verdict.cursor)), ['choice', 'notes', 'reasoning', 'score', 'tags']);
    const reply = typed(text, at(text, '        run Archive'), '        note reply.');
    assert.deepEqual(labels(suggest(index, main, reply.text, reply.cursor)), [], 'a place the index knows, with nothing to offer');
});

test('R24: nothing completes from a type that is not known', () => {
    const t = typed(text, at(text, '        return reply'), '        note handled.');
    assert.deepEqual(labels(suggest(index, main, t.text, t.cursor)), []);
});

test('R24: a variable made later in the workflow is not offered before it exists', () => {
    const early = typed(text, at(text, '    checkpoint gathered'), '    note intent.');
    // `intent` is made on the line before, so it is there
    assert.ok(labels(suggest(index, main, early.text, early.cursor)).includes('kind'));
    const tooEarly = typed(text, at(text, '    intentclassify Intent'), '    return ');
    const names = suggest(index, main, tooEarly.text, tooEarly.cursor)!.items.map((i) => i.label).sort();
    assert.deepEqual(names, ['message', 'tone'], 'only the parameters exist yet');
});

test('R24: after == the values of an enum are offered, quoted unless the quote is already typed', () => {
    const bare = typed(text, at(text, '    alt (intent.kind == "order_status") {'), '    alt (intent.kind == ');
    const s = suggest(index, main, bare.text, bare.cursor)!;
    assert.deepEqual(s.items.map((i) => i.label), ['order_status', 'refund', 'other', 'unsure']);
    assert.equal(s.items[0].insertText, '"order_status"');
    const quoted = typed(text, at(text, '    alt (intent.kind == "order_status") {'), '    alt (intent.kind == "re');
    const q = suggest(index, main, quoted.text, quoted.cursor)!;
    assert.equal(q.items[1].insertText, 'refund');
    assert.equal(q.replaceLength, 3, 'the quote and the two letters');
    const yesno = typed(text, at(text, '    alt (intent.kind'), '    alt (intent.urgent != ');
    assert.deepEqual(suggest(index, main, yesno.text, yesno.cursor)!.items.map((i) => i.label), ['yes', 'no']);
});

test('R24: a number or text has no values to offer after ==', () => {
    const t = typed(text, at(text, '    alt (intent.kind'), '    alt (intent.confidence == ');
    assert.deepEqual(suggest(index, main, t.text, t.cursor)!.items, []);
});

test('R24: inside a {placeholder} in a prompt, variables and their fields are offered', () => {
    const vars = typed(text, at(text, '        run Archive'), '        note "Hello {');
    assert.deepEqual(labels(suggest(index, main, vars.text, vars.cursor)), ['choice', 'handled', 'intent', 'message', 'reply', 'tone', 'verdict']);
    const fields = typed(text, at(text, '        run Archive'), '        note "Hello {verdict.');
    assert.deepEqual(labels(suggest(index, main, fields.text, fields.cursor)), ['choice', 'notes', 'reasoning', 'score', 'tags']);
    const plain = typed(text, at(text, '        run Archive'), '        note "Hello wor');
    assert.equal(suggest(index, main, plain.text, plain.cursor), undefined, 'ordinary words in a prompt are not completed');
    const closed = typed(text, at(text, '        run Archive'), '        note "Hello {tone} and ');
    assert.equal(suggest(index, main, closed.text, closed.cursor), undefined);
});

test('R24: a comment is left alone, and so is a place the index does not know', () => {
    const comment = typed(text, at(text, '        run Archive'), '        // delegate "x" to ');
    assert.equal(suggest(index, main, comment.text, comment.cursor), undefined);
    const nothing = typed(text, at(text, '        run Archive'), '        ');
    assert.equal(suggest(index, main, nothing.text, nothing.cursor), undefined);
});

test('R24: a dotted name that is not one of the workflow\'s variables is left to the older completion', () => {
    const t = typed(text, at(text, '        run Archive'), '        note env.');
    assert.equal(suggest(index, main, t.text, t.cursor), undefined);
});
