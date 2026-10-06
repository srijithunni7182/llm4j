import test from 'node:test';
import assert from 'node:assert/strict';
import { parseWebviewMessage } from '../src/graph/protocol';

test('each message the panel sends is accepted with exactly its fields', () => {
    assert.deepEqual(parseWebviewMessage({ type: 'ready' }), { type: 'ready' });
    assert.deepEqual(parseWebviewMessage({ type: 'openSource', file: '/p/a.loom', line: 5, beside: true }), { type: 'openSource', file: '/p/a.loom', line: 5, beside: true });
    assert.deepEqual(parseWebviewMessage({ type: 'openSource', file: '/p/a.loom', line: 5 }), { type: 'openSource', file: '/p/a.loom', line: 5, beside: false });
    assert.deepEqual(parseWebviewMessage({ type: 'selectWorkflow', name: 'Main' }), { type: 'selectWorkflow', name: 'Main' });
    assert.deepEqual(parseWebviewMessage({ type: 'copyMermaid', name: 'Main' }), { type: 'copyMermaid', name: 'Main' });
});

test('extra fields are dropped, never passed on', () => {
    const accepted = parseWebviewMessage({ type: 'ready', command: 'rm -rf /', extra: 1 });
    assert.deepEqual(accepted, { type: 'ready' });
});

test('anything that is not one of the known messages is refused', () => {
    for (const bad of [null, undefined, 1, 'ready', [], {}, { type: 'unknown' }, { type: 5 }, { kind: 'ready' }]) {
        assert.equal(parseWebviewMessage(bad), undefined, JSON.stringify(bad));
    }
});

test('openSource needs a file and a line that is a whole number from 1 up', () => {
    const base = { type: 'openSource', file: '/p/a.loom', beside: false };
    for (const line of [0, -1, 1.5, NaN, Infinity, '5', null, undefined, 10_000_001]) {
        assert.equal(parseWebviewMessage({ ...base, line }), undefined, String(line));
    }
    for (const file of ['', 7, null, undefined, 'x'.repeat(5000)]) {
        assert.equal(parseWebviewMessage({ ...base, file, line: 3 }), undefined, String(file));
    }
});

test('workflow messages need a name', () => {
    for (const type of ['selectWorkflow', 'copyMermaid']) {
        assert.equal(parseWebviewMessage({ type }), undefined);
        assert.equal(parseWebviewMessage({ type, name: '' }), undefined);
        assert.equal(parseWebviewMessage({ type, name: 5 }), undefined);
    }
});
