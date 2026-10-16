import test from 'node:test';
import assert from 'node:assert/strict';
import { stopCompletions } from '../src/lsp/stopCompletion';

/** The text with `|` where the cursor is. */
function at(text: string) {
    const offset = text.indexOf('|');
    return stopCompletions(text.replace('|', ''), offset);
}

const labels = (r: ReturnType<typeof at>) => (r ?? []).map((c) => c.label);

test('after _stop. the fields of a stop are offered', () => {
    const r = at('delegate "x" to A -> o on_stopped { alt (_stop.|) { skip } }');
    assert.deepEqual(labels(r), ['reason', 'note', 'kind', 'phase', 'partial', 'changed', 'origin']);
    assert.ok(r!.every((c) => c.kind === 'field'));
});

test('a partly typed field still offers them all', () => {
    assert.equal(labels(at('on_stopped { alt (_stop.re|) { skip } }')).length, 7);
});

test('after rewind to only the checkpoints declared earlier are offered, and start', () => {
    const r = at('workflow W() {\n  checkpoint collected\n  checkpoint drafted\n  delegate "x" to A -> o on_stopped {\n    rewind to |\n  }\n  checkpoint later\n}');
    assert.deepEqual(labels(r), ['start', 'collected', 'drafted']);
    assert.ok(!labels(r).includes('later'));
});

test('at the start of a line inside an on_stopped handler the outcomes are offered', () => {
    const r = at('delegate "x" to A -> o\n  on_stopped {\n    |\n  }');
    assert.ok(labels(r).includes('use partial') && labels(r).includes('skip') && labels(r).includes('rewind to') && labels(r).includes('alt'));
});

test('outside a handler, or after it is closed, nothing special is offered', () => {
    assert.equal(at('workflow W() {\n  |\n}'), undefined);
    assert.equal(at('delegate "x" to A -> o on_stopped { skip }\n|'), undefined);
});

test('a brace inside a string does not close the handler', () => {
    const r = at('delegate "x" to A -> o on_stopped {\n  alt (_stop.note == "}") {\n    skip\n  }\n  |\n}');
    assert.ok(labels(r).includes('skip'));
});
