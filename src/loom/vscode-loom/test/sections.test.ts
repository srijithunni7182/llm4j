import test from 'node:test';
import assert from 'node:assert/strict';
import { parseSections, readSections } from '../src/graph/sections';
import { sampleResult } from './support';

const script = [
    'workflow Main(x) {',
    '    // 1. Look and research',
    '    // Diagnose, then research',
    '    // maybe more rounds',
    '    // and a third line that is not part of the hint',
    '    delegate A',
    '    loop {',
    '        // 1. numbered inside a block: not a section',
    '        delegate B',
    '    }',
    '    // 2. Write a fix',
    '    delegate C',
    '}',
    'workflow Other() {',
    '    // 1. Another',
    '}',
].join('\n');

test('the outermost numbered comments are the sections, with the plain comments under them as the hint', () => {
    const sections = parseSections(script, 2, 13);
    assert.deepEqual(sections.map((s) => [s.n, s.title, s.line]), [[1, 'Look and research', 2], [2, 'Write a fix', 11]]);
    assert.equal(sections[0].hint, 'Diagnose, then research maybe more rounds');
    assert.equal(sections[1].hint, '');
});

test('a script with no numbered comments has no sections', () => {
    assert.deepEqual(parseSections('workflow A() {\n  // note\n  delegate X\n}', 1, 4), []);
});

test('sections are read per workflow, only between its line and the next workflow of the file', async () => {
    const result = sampleResult();
    result.workflows[0].line = 1; result.workflows[1].line = 14; result.workflows[2].line = 1;
    const read = async (file: string) => (file === '/p/main.loom' ? script : undefined);
    const sections = await readSections(result, read);
    assert.deepEqual(Object.keys(sections).sort(), ['First@/p/main.loom', 'Second@/p/main.loom']);
    assert.equal(sections['First@/p/main.loom'].length, 2);
    assert.equal(sections['Second@/p/main.loom'][0].title, 'Another');
});

test('a file that cannot be read means no sections, not an error', async () => {
    const result = sampleResult();
    result.workflows.forEach((w, i) => { w.line = i + 1; });
    assert.deepEqual(await readSections(result, async () => { throw new Error('gone'); }), {});
});
