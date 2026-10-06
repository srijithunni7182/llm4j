import test from 'node:test';
import assert from 'node:assert/strict';
import * as fs from 'fs';
import * as path from 'path';
import { parseDocument } from '../src/lsp/parseDocument';

// These two regressions shipped unnoticed because nothing in this suite
// exercised parseDocument directly — every other test here goes through
// `weave graph` (the real Java parser), which never saw these bugs.

test('a delegate payload string spanning multiple lines is not a lex/parse error', () => {
    const src = [
        'agent Showrunner { model: "gemini" }',
        '',
        'workflow W(topic) {',
        '    delegate "Cast your team for this brief.',
        'IDEA: {topic}',
        'CREATOR: @{topic}',
        'YOUR PAST CASTINGS:',
        '{topic}" to Showrunner -> castingSheet retry 1 on_failure {',
        '        note "failed"',
        '    }',
        '}',
    ].join('\n');

    const result = parseDocument(src);
    assert.deepEqual(result.errors, []);
});

test('an unterminated string is still reported, at the quote that opened it', () => {
    const src = [
        'workflow W() {',
        '    note "this never closes',
        '}',
    ].join('\n');

    // The open string swallows the rest of the file, including the closing
    // '}' on line 2 — so an unmatched '{' is also correctly reported.
    const result = parseDocument(src);
    const unterminated = result.errors.filter(e => e.message === 'Unterminated string literal');
    assert.equal(unterminated.length, 1);
    assert.equal(unterminated[0].line, 1);
});

test("handoff has no '->' and must not be flagged as missing one", () => {
    const src = [
        'agent CEO { model: "gemini" }',
        'workflow W(verdict) {',
        '    handoff "Decision Reached: {verdict}" to CEO',
        '}',
    ].join('\n');

    const result = parseDocument(src);
    assert.deepEqual(result.errors, []);
});

test("a delegate statement missing '->' is still caught", () => {
    const src = [
        'agent A { model: "gemini" }',
        'workflow W() {',
        '    delegate "do it" to A',
        '}',
    ].join('\n');

    const result = parseDocument(src);
    assert.equal(result.errors.length, 1);
    assert.equal(result.errors[0].message, "Missing '->' in 'delegate' statement");
});

test('delegate / handoff / broadcast agent references name the agent, not the output variable', () => {
    const src = [
        'workflow W(topic) {',
        '    delegate "research {topic}" to Researcher -> researchDossier',
        '    handoff "done" to CEO',
        '    broadcast "fyi" to [Alice, Bob] -> acks',
        '}',
    ].join('\n');

    const result = parseDocument(src);
    const names = result.agentRefs.map(r => r.name).sort();
    assert.deepEqual(names, ['Alice', 'Bob', 'CEO', 'Researcher']);
    // explicitly not the output variables
    assert.ok(!names.includes('researchDossier'));
    assert.ok(!names.includes('acks'));
});

test('a multi-line delegate prompt still resolves its agent reference correctly', () => {
    const src = [
        'agent Showrunner { model: "gemini" }',
        'workflow W(topic) {',
        '    delegate "line one',
        'line two',
        '{topic}" to Showrunner -> castingSheet',
        '}',
    ].join('\n');

    const result = parseDocument(src);
    assert.deepEqual(result.errors, []);
    assert.equal(result.agentRefs.length, 1);
    assert.equal(result.agentRefs[0].name, 'Showrunner');
});

test('every real sample and example .loom/.loot file parses without lex/parse errors', () => {
    const roots = [
        path.join(__dirname, '../../ai-agent4j-loom/samples'),
        path.join(__dirname, '../../ctk/scripts'),
    ];

    function walk(dir: string): string[] {
        if (!fs.existsSync(dir)) return [];
        let out: string[] = [];
        for (const f of fs.readdirSync(dir)) {
            const p = path.join(dir, f);
            const st = fs.statSync(p);
            if (st.isDirectory()) out = out.concat(walk(p));
            else if (f.endsWith('.loom') || f.endsWith('.loot')) out.push(p);
        }
        return out;
    }

    for (const root of roots) {
        for (const file of walk(root)) {
            const result = parseDocument(fs.readFileSync(file, 'utf8'));
            assert.deepEqual(result.errors, [], `${file}: ${JSON.stringify(result.errors)}`);
        }
    }
});
