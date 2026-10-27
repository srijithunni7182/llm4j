import { strict as assert } from 'assert';
import * as path from 'path';
import { test } from 'node:test';
import { hoverAt } from '../src/semantic/hover';
import { at, fixtureIndex, fixtureText, SCRIPTS } from './semanticSupport';

const index = fixtureIndex('main');
const main = path.join(SCRIPTS, 'main.loom');
const text = fixtureText('main.loom');

function hover(needle: string, inside: number, nth = 0): string {
    const h = hoverAt(index, main, text, at(text, needle, inside, nth));
    assert.ok(h, `hover on ${needle}`);
    return h!.markdown;
}

test('R22: an agent shows its model, tools and uses', () => {
    const md = hover('agent Replier', 8);
    assert.match(md, /\*\*agent\*\* `Replier`/);
    assert.match(md, /model `claude-haiku-5-5`/);
    assert.match(md, /tools: `Mailer`/);
    assert.match(md, /1 use ·/);
});

test('R22: an agent with an output_schema shows what it replies with', () => {
    const md = hover('to Scorer', 4);
    assert.match(md, /Replies with/);
    assert.match(md, /`score`: number/);
    assert.match(md, /`tags`: list<string>/);
});

test('R22: a workflow shows its signature, a classifier its record, a decision its choices', () => {
    assert.match(hover('call Escalate', 7), /`Escalate\(memo\)`/);
    const classifier = hover('intentclassify Intent', 'intentclassify '.length + 1);
    assert.match(classifier, /`kind`: order_status \| refund \| other \| unsure/);
    assert.match(classifier, /`urgent`: yes \| no/);
    assert.match(hover('decide Refund', 'decide '.length + 1), /choices: approve \| reject/);
});

test('R22: a variable shows its type and where it was made', () => {
    const md = hover('intent.kind', 2);
    assert.match(md, /\*\*variable\*\* `intent`/);
    assert.match(md, /Made by `intentclassify`/);
    assert.match(md, /`confidence`: number/);
    assert.match(hover('{tone}', 2), /A parameter of the workflow/);
    assert.match(hover('-> handled', 4), /not known from the script/);
});

test('R22: a field of a variable shows its own type, in code and in a {placeholder}', () => {
    assert.match(hover('intent.kind', 'intent.'.length + 1), /`kind`: `order_status \| refund \| other \| unsure`/);
    assert.match(hover('verdict.score', 'verdict.'.length + 1), /`score`: `number`/);
    const inPrompt = text.replace('        return reply\n', '        note "Got {verdict.score}"\n        return reply\n');
    const h = hoverAt(index, main, inPrompt, at(inPrompt, '{verdict.score}', 'verdict.'.length + 2));
    assert.match(h!.markdown, /`score`: `number`/);
    const bad = text.replace('verdict.score <', 'verdict.nope <');
    assert.match(hoverAt(index, main, bad, at(bad, 'verdict.nope', 'verdict.'.length + 1))!.markdown, /not a field this variable is known to have/);
});

test('R22: the string in a comparison lists what the field can be, and warns about a value it never is', () => {
    const md = hover('"order_status"', 3);
    assert.match(md, /`intent.kind` is one of: `order_status`, `refund`, `other`, `unsure`/);
    assert.doesNotMatch(md, /never true/);
    const wrong = text.replace('"order_status"', '"shipping"');
    const h = hoverAt(index, main, wrong, at(wrong, '"shipping"', 3));
    assert.match(h!.markdown, /`shipping` is not one of them, so this comparison is never true/);
});

test('R22: a task is said to be the host\'s, an unresolved name is said to be undefined', () => {
    assert.match(hover('run Archive', 5), /Supplied by the host/);
    const broken = { ...index, references: index.references.map((r) => (r.to === 'agent:Helper' ? { ...r, resolved: false } : r)), definitions: index.definitions.filter((d) => d.key !== 'agent:Helper') };
    const h = hoverAt(broken, main, text, at(text, 'to Helper', 4));
    assert.match(h!.markdown, /is not defined in this script or its imports/);
});

test('R22: a keyword has no hover here, so the older answer is used', () => {
    assert.equal(hoverAt(index, main, text, at(text, 'workflow Main', 2)), undefined);
    assert.equal(hoverAt(index, main, text, at(text, 'Reply to', 2)), undefined);
});
