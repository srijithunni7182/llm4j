import { strict as assert } from 'assert';
import * as path from 'path';
import { test } from 'node:test';
import { ProcessEngineClient } from '../src/engine/client';
import { suggest } from '../src/semantic/completion';
import { parseIndex, SymbolIndex } from '../src/semantic/symbols';
import { at, fixtureText, SCRIPTS, typed } from './semanticSupport';

import { available, realClient } from './realEngine';

const skip = !available && 'ai-agent4j-loom is not built';

async function indexOf(client: ProcessEngineClient, file: string, text?: string): Promise<SymbolIndex> {
    const answer = await client.request('loom/symbols', { file, text });
    const index = parseIndex(answer.result);
    assert.ok(index, 'the engine answered with an index');
    return index!;
}

async function errorsOf(client: ProcessEngineClient, file: string, text: string): Promise<string[]> {
    const answer = await client.request('loom/check', { file, text });
    const diagnostics = (answer.result as { diagnostics: Array<{ severity: string; message: string }> }).diagnostics;
    return diagnostics.filter((d) => d.severity === 'error').map((d) => d.message);
}

const main = path.join(SCRIPTS, 'main.loom');

test('R24: every suggestion, put into a statement, passes check (no error the script did not already have)', { skip }, async () => {
    const client = realClient([SCRIPTS]);
    try {
        const text = fixtureText('main.loom');
        const baseline = new Set(await errorsOf(client, main, text));
        const target = at(text, '        run Archive');
        // [what is typed up to the place, what follows the chosen name, a note]
        const contexts: Array<[string, string]> = [
            ['        delegate "x" to ', ' -> zz'],
            ['        intentclassify ', ' on message -> zz'],
            ['        decide ', ' -> zz'],
            ['        rewind to ', ' when (verdict.score < 5) at most 2 times'],
            ['        alt (intent.kind == ', ') {\n            note "x"\n        }'],
            ['        alt (intent.urgent != ', ') {\n            note "x"\n        }'],
            ['        note "{intent.', '}"'],
            ['        note "{verdict.', '}"'],
            ['        note "{', '}"'],
        ];
        let checked = 0;
        for (const [head, tail] of contexts) {
            const t = typed(text, target, head);
            const index = await indexOf(client, main, t.text);
            const found = suggest(index, main, t.text, t.cursor);
            assert.ok(found, `${head.trim()} is a place the engine's index knows`);
            assert.ok(found!.items.length > 0, `${head.trim()} offers something`);
            for (const item of found!.items) {
                const whole = typed(text, target, `${head}${item.insertText ?? item.label}${tail}\n`);
                const fresh = (await errorsOf(client, main, whole.text)).filter((e) => !baseline.has(e));
                assert.deepEqual(fresh, [], `${head.trim()} ${item.label} must not break the script`);
                checked++;
            }
        }
        assert.ok(checked > 25, `checked ${checked} suggestions`);
    } finally {
        client.dispose();
    }
});

test('R27: while a line is half typed, the engine still answers and the earlier variables are still known', { skip }, async () => {
    const client = realClient([SCRIPTS]);
    try {
        const text = fixtureText('main.loom');
        const t = typed(text, at(text, '        run Archive'), '        delegate "x" to \n        note intent.');
        const index = await indexOf(client, main, t.text);
        assert.equal(index.partial, true);
        assert.ok(index.problems.length > 0);
        const found = suggest(index, main, t.text, t.cursor);
        assert.ok(found);
        assert.ok(found!.items.some((i) => i.label === 'kind'), 'intent.kind is offered although the line above is broken');
    } finally {
        client.dispose();
    }
});

test('R21: the engine\'s ranges, put back on the text, read as the names (emoji, tabs, CRLF)', { skip }, async () => {
    const client = realClient([SCRIPTS]);
    try {
        const body = 'agent Writer { model: "m" }\nworkflow Main(topic) {\n\tdelegate "🙂🙂 {topic}" to Writer -> draft\n\treturn draft\n}\n';
        for (const variant of [body, body.replace(/\n/g, '\r\n')]) {
            const index = await indexOf(client, path.join(SCRIPTS, 'scratch.loom'), variant);
            const lines = variant.split('\n').map((l) => l.replace(/\r$/, ''));
            const read = (r: { start: { line: number; character: number }; end: { character: number } }): string => lines[r.start.line].slice(r.start.character, r.end.character);
            for (const d of index.definitions) {
                assert.equal(read(d.range), d.name);
            }
            for (const b of index.bindings) {
                assert.equal(read(b.range), b.name);
            }
            for (const r of index.references) {
                assert.equal(read(r.range), r.to.replace(/^[a-z]+:/, '').replace(/^.*\//, ''));
            }
        }
    } finally {
        client.dispose();
    }
});

test('R22: a request is answered well inside the budget once the engine is warm', { skip }, async () => {
    const client = realClient([SCRIPTS]);
    try {
        const text = fixtureText('main.loom');
        await indexOf(client, main, text); // warm
        const times: number[] = [];
        for (let i = 0; i < 40; i++) {
            const edited = text + `\n// edit ${i}\n`; // a different text each time: no cache hit
            const t0 = process.hrtime.bigint();
            await indexOf(client, main, edited);
            times.push(Number(process.hrtime.bigint() - t0) / 1e6);
        }
        times.sort((a, b) => a - b);
        const p95 = times[Math.floor(times.length * 0.95)];
        console.log(`# loom/symbols p50 ${times[20].toFixed(1)} ms, p95 ${p95.toFixed(1)} ms`);
        assert.ok(p95 < 100, `p95 ${p95} ms`);
    } finally {
        client.dispose();
    }
});
