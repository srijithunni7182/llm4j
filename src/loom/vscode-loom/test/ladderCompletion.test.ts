import test from 'node:test';
import assert from 'node:assert/strict';
import { ladderCompletions } from '../src/lsp/ladderCompletion';

/** `at(text)`: the completions where the `|` in the text is. */
function at(text: string) {
    const offset = text.indexOf('|');
    assert.ok(offset >= 0, 'the text needs a | for the cursor');
    return ladderCompletions(text.replace('|', ''), offset);
}
const labels = (r: ReturnType<typeof at>) => (r || []).map(c => c.label).sort();

const LADDERS = `import "loom/autonomy"
measure careful(c) = c.review_time >= 20s
measure big_edit(c) = distance(c.proposal.value, c.response.final) > 10%

ladder Cautious {
    rung watch { mode: blind }
    rung suggest {
        mode: confirm
        climb when history.count >= 10
    }
    rung act {
        mode: autonomous
        climb when history.count >= 30
        slip to suggest when history.last(50).count(big_edit) >= 2
    }
    climbing is automatic
}
ladder Other {
    rung draft { mode: blind }
    rung publish { mode: autonomous  climb when history.count >= 5 }
    climbing is automatic
}
`;

test('B5: after ladder: the ladders of the document are offered, with their rungs', () => {
    const r = at(LADDERS + 'decision D { proposed by: A  remember: amount, tier\n    ladder: |\n}\n');
    assert.deepEqual(labels(r), ['Cautious', 'Other']);
    assert.match(r![0].detail, /3 rungs: watch, suggest, act/);
});

test('B5: after ceiling: the rungs of the ladder the decision binds, and only those', () => {
    const r = at(LADDERS + 'decision D { proposed by: A\n    ladder: Cautious\n    ceiling: |\n}\n');
    assert.deepEqual(labels(r), ['act', 'suggest', 'watch']);
    const other = at(LADDERS + 'decision D { proposed by: A\n    ladder: Other\n    ceiling: pu|\n}\n');
    assert.deepEqual(labels(other), ['draft', 'publish']);
});

test('B5: an agent may carry a ladder and a ceiling too', () => {
    const r = at(LADDERS + 'agent T { model: "m"\n    ladder: Other\n    ceiling: |\n}\n');
    assert.deepEqual(labels(r), ['draft', 'publish']);
});

test('B5: after slip to the rungs of the ladder being written, not of another', () => {
    const text = LADDERS.replace('slip to suggest when', 'slip to | when');
    assert.deepEqual(labels(at(text)), ['act', 'suggest', 'watch']);
});

test('B5: after mode: the three modes, and a rung that is not mine is not offered', () => {
    const text = LADDERS.replace('mode: confirm', 'mode: |');
    assert.deepEqual(labels(at(text)), ['autonomous', 'blind', 'confirm']);
});

test('B5: after history. the methods and fields of a collection', () => {
    const text = LADDERS.replace('climb when history.count >= 10', 'climb when history.|');
    const l = labels(at(text));
    for (const w of ['where', 'last', 'since', 'rate', 'median', 'count', 'age', 'span']) assert.ok(l.includes(w), w);
    assert.ok(!l.includes('lower95'), 'a collection has no lower95');
});

test('B5: after .rate(...). the parts of a ratio', () => {
    const text = LADDERS.replace('climb when history.count >= 10', 'climb when history.rate(agrees).|');
    assert.deepEqual(labels(at(text)), ['lower95', 'n', 'upper95', 'value']);
});

test('B5: inside the brackets of a method the measures of the script, the standard ones that were imported, and the fields of a case', () => {
    const text = LADDERS.replace('climb when history.count >= 10', 'climb when history.rate(|)').replace('decision', 'decision');
    const l = labels(at(text + 'decision D { proposed by: A  remember: amount\n dangerous mistake: propose a, person decides b }\n'));
    for (const w of ['careful', 'big_edit', 'agrees', 'edit_size', 'untouched', 'reversed', 'dangerous', 'amount', 'response', 'proposal']) assert.ok(l.includes(w), w);
});

test('B5: the standard measures are offered only when the library is imported', () => {
    const text = LADDERS.replace('import "loom/autonomy"\n', '').replace('climb when history.count >= 10', 'climb when history.rate(|)');
    const l = labels(at(text));
    assert.ok(l.includes('careful'));
    assert.ok(!l.includes('agrees'));
});

test('B5: after case. the fields a decision remembers come first-class', () => {
    const text = LADDERS.replace('climb when history.count >= 30', 'climb when case.|') + 'decision D { proposed by: A\n    remember: amount: number, tier\n    group cases by: region\n}\n';
    const l = labels(at(text));
    for (const w of ['amount', 'tier', 'region', 'response']) assert.ok(l.includes(w), w);
});

test('B5: at the start of a line in a rung the words a rung has', () => {
    const text = LADDERS.replace('        climb when history.count >= 30\n', '        |\n');
    const l = labels(at(text));
    assert.ok(l.includes('mode:') && l.includes('climb when') && l.includes('slip to') && l.includes('sample') && l.includes('escalate when'));
});

test('B5: outside a ladder, and not after ladder: or ceiling:, nothing is claimed (the server offers what it always did)', () => {
    assert.equal(at(LADDERS + 'workflow W() {\n    note "hi"|\n}\n'), undefined);
    assert.equal(at('agent A { model: "m" }\nworkflow W() { delegate "x" to |'), undefined);
});

test('B5: braces in strings and comments do not move where a ladder ends', () => {
    const text = 'ladder L {\n    // a { in a comment\n    rung a { mode: blind }\n    rung b { mode: confirm  climb when history.rate(|) }\n    climbing is automatic\n}\n';
    assert.ok(labels(at(text)).includes('response'));
});
