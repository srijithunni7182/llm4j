import test from 'node:test';
import assert from 'node:assert/strict';
import { GraphParseError, MAX_GRAPH_BYTES, parseGraph } from '../src/graph/model';
import { golden } from './support';

const valid = () => JSON.parse(golden('content_factory'));

test('the output of weave graph for every sample parses', () => {
    for (const name of ['content_factory', 'boardroom', 'digest', 'imports_parent', 'all_statements', 'handlers']) {
        const result = parseGraph(golden(name));
        assert.equal(result.version, 1);
        assert.ok(result.workflows.length > 0, name);
    }
});

test('VS.3: text that is not JSON is refused with a message that says so', () => {
    assert.throws(() => parseGraph('Error: no such file'), (e: Error) => e instanceof GraphParseError && /did not print JSON/.test(e.message));
});

test('JSON that is not an object is refused', () => {
    assert.throws(() => parseGraph('[]'), /JSON object/);
    assert.throws(() => parseGraph('"x"'), /JSON object/);
    assert.throws(() => parseGraph('null'), /JSON object/);
});

test('VS.3: another version is refused and the message says how to fix it', () => {
    const wrong = valid();
    wrong.version = 2;
    assert.throws(() => parseGraph(JSON.stringify(wrong)), (e: Error) => /version 2/.test(e.message) && /needs 1/.test(e.message) && /Update the extension or weave.jar/.test(e.message));
    delete wrong.version;
    assert.throws(() => parseGraph(JSON.stringify(wrong)), /version undefined/);
});

test('a missing or mistyped field is named by its path', () => {
    const noId = valid();
    delete noId.workflows[0].nodes[1].id;
    assert.throws(() => parseGraph(JSON.stringify(noId)), /workflows\[0\]\.nodes\[1\]\.id should be a string/);

    const badEdge = valid();
    badEdge.workflows[0].edges[0].to = 7;
    assert.throws(() => parseGraph(JSON.stringify(badEdge)), /workflows\[0\]\.edges\[0\]\.to should be a string/);

    const noFiles = valid();
    delete noFiles.files;
    assert.throws(() => parseGraph(JSON.stringify(noFiles)), /files should be a list/);

    const badSeverity = valid();
    badSeverity.diagnostics = [{ severity: 'fatal', file: 'f', line: 1, message: 'm' }];
    assert.throws(() => parseGraph(JSON.stringify(badSeverity)), /severity should be "error" or "warning"/);
});

test('VS.3: output over the limit is refused before it is parsed', () => {
    const big = ' '.repeat(MAX_GRAPH_BYTES + 1);
    assert.throws(() => parseGraph(big), /larger than 8 MB/);
});

test('a graph with no workflows is valid', () => {
    const empty = valid();
    empty.workflows = [];
    assert.equal(parseGraph(JSON.stringify(empty)).workflows.length, 0);
});
