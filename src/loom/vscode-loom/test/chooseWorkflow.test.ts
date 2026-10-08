import test from 'node:test';
import assert from 'node:assert/strict';
import { chooseWorkflow } from '../src/graph/chooseWorkflow';
import { sampleResult } from './support';

test('the workflow the cursor is in is chosen', () => {
    const result = sampleResult();
    assert.equal(chooseWorkflow(result, '/p/main.loom', 4), 'First');
    assert.equal(chooseWorkflow(result, '/p/main.loom', 12), 'Second');
    assert.equal(chooseWorkflow(result, '/p/main.loom', 10), 'Second');
});

test('a cursor above every workflow chooses the first workflow of the entry file', () => {
    assert.equal(chooseWorkflow(sampleResult(), '/p/main.loom', 1), 'First');
});

test('a cursor in another file chooses a workflow of that file', () => {
    assert.equal(chooseWorkflow(sampleResult(), '/p/lib.loom', 1), 'Lib');
});

test('a file with no workflow falls back to the entry file', () => {
    assert.equal(chooseWorkflow(sampleResult(), '/p/elsewhere.loom', 3), 'First');
});

test('a script with no workflows chooses nothing', () => {
    assert.equal(chooseWorkflow(sampleResult({ workflows: [] }), '/p/main.loom', 3), undefined);
});
