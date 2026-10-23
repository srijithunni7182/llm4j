import { strict as assert } from 'assert';
import * as fs from 'fs';
import * as path from 'path';
import { test } from 'node:test';
import { parseDocument } from '../src/lsp/parseDocument';
import { parseIndex } from '../src/semantic/symbols';
import { available, realClient } from './realEngine';

/**
 * R25 of spec 10: the TypeScript parser (src/lsp/parseDocument.ts) is frozen, and this compares what it finds with the Java index on
 * every script of the repository. The differences are committed in test/fixtures/parity/parser-delta.json: a new one fails the test
 * (the frozen parser must not drift), and one that disappears asks for the file to be regenerated (UPDATE_PARITY=1), so the size of
 * the delta is a number to decide on when retiring the parser.
 */
const repo = path.join(__dirname, '..', '..', '..', '..', '..');
const baselineFile = path.join(__dirname, '..', '..', 'test', 'fixtures', 'parity', 'parser-delta.json');

function corpus(dir: string, out: string[] = []): string[] {
    for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
        const p = path.join(dir, entry.name);
        if (entry.isDirectory()) {
            if (!['node_modules', 'target', '.git', 'out', 'out-test'].includes(entry.name)) {
                corpus(p, out);
            }
        } else if (entry.name.endsWith('.loom')) {
            out.push(p);
        }
    }
    return out.sort();
}

test('R25: the frozen TypeScript parser and the Java index agree, except for the tracked differences', { skip: !available && 'ai-agent4j-loom is not built' }, async () => {
    const client = realClient([repo]);
    const delta: string[] = [];
    let compared = 0;
    try {
        for (const file of corpus(repo)) {
            if (!fs.statSync(file).isFile()) {
                continue;
            }
            const text = fs.readFileSync(file, 'utf8');
            const answer = await client.request('loom/symbols', { file });
            const index = parseIndex(answer.result);
            assert.ok(index, file);
            if (index!.partial) {
                continue; // wrong on purpose (or an import file the language cannot read alone): not a fair comparison
            }
            compared++;
            const rel = path.relative(repo, file).split(path.sep).join('/');
            const ts = parseDocument(text);
            const here = (f: string): boolean => path.resolve(f) === path.resolve(file);
            const javaDefs = index!.definitions.filter((d) => here(d.file) && (d.kind === 'agent' || d.kind === 'workflow'));
            const key = (kind: string, name: string, line: number, col: number): string => `${kind} ${name} @${line}:${col}`;
            const java = new Set(javaDefs.map((d) => key(d.kind, d.name, d.range.start.line, d.range.start.character)));
            const tsSet = new Set(ts.definitions.map((d) => key(d.kind, d.name, d.line, d.col)));
            for (const k of java) {
                if (!tsSet.has(k)) {
                    delta.push(`${rel}: only Java defines ${k}`);
                }
            }
            for (const k of tsSet) {
                if (!java.has(k)) {
                    delta.push(`${rel}: only TypeScript defines ${k}`);
                }
            }
            const javaRefs = new Set(index!.references.filter((r) => here(r.file) && r.kind === 'agent' && /target$/.test(r.role)).map((r) => `${r.to.slice(6)} @${r.range.start.line}:${r.range.start.character}`));
            const tsRefs = new Set(ts.agentRefs.map((r) => `${r.name} @${r.line}:${r.col}`));
            for (const k of javaRefs) {
                if (!tsRefs.has(k)) {
                    delta.push(`${rel}: only Java sees the agent reference ${k}`);
                }
            }
            for (const k of tsRefs) {
                if (!javaRefs.has(k)) {
                    delta.push(`${rel}: only TypeScript sees the agent reference ${k}`);
                }
            }
        }
    } finally {
        client.dispose();
    }
    assert.ok(compared > 20, `compared ${compared} scripts`);
    delta.sort();
    console.log(`# parser parity: ${compared} scripts compared, ${delta.length} differences`);
    if (process.env.UPDATE_PARITY === '1') {
        fs.writeFileSync(baselineFile, JSON.stringify({ compared, differences: delta }, null, 2) + '\n');
    }
    const baseline = JSON.parse(fs.readFileSync(baselineFile, 'utf8')) as { compared: number; differences: string[] };
    const added = delta.filter((d) => !baseline.differences.includes(d));
    const gone = baseline.differences.filter((d) => !delta.includes(d));
    assert.deepEqual(added, [], 'new differences: the frozen parser must not drift (or the corpus grew: UPDATE_PARITY=1)');
    assert.deepEqual(gone, [], 'differences that went away: regenerate with UPDATE_PARITY=1');
});
