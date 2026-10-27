import { strict as assert } from 'assert';
import * as fs from 'fs';
import * as os from 'os';
import * as path from 'path';
import { test } from 'node:test';
import { suggest } from '../src/semantic/completion';
import { hoverAt } from '../src/semantic/hover';
import { planRename } from '../src/semantic/queries';
import { parseIndex } from '../src/semantic/symbols';
import { available, realClient } from './realEngine';
import { at, typed } from './semanticSupport';

/**
 * A6 / R26 of spec 10: feedback is fast. The numbers are the spec's, measured on the real engine once it is warm: the instant tier
 * under 150 ms, a full review under 1 s, completion and hover under 100 ms, a rename of a 20-file project under 1 s, and a cold
 * start to the first answer under 4 s. Each gate is p95 over a run of requests with a different text each time (no cache hits).
 */
const skip = !available && 'ai-agent4j-loom is not built';
const reviewScripts = path.join(__dirname, '..', '..', 'test', 'fixtures', 'review', 'scripts');
const semanticScripts = path.join(__dirname, '..', '..', 'test', 'fixtures', 'semantic', 'scripts');

function p95(times: number[]): number {
    const sorted = [...times].sort((a, b) => a - b);
    return sorted[Math.min(sorted.length - 1, Math.floor(sorted.length * 0.95))];
}

async function timed(times: number[], work: () => Promise<unknown> | unknown): Promise<void> {
    const t0 = process.hrtime.bigint();
    await work();
    times.push(Number(process.hrtime.bigint() - t0) / 1e6);
}

function report(name: string, times: number[], budget: number): void {
    const sorted = [...times].sort((a, b) => a - b);
    console.log(`# latency ${name}: p50 ${sorted[Math.floor(sorted.length / 2)].toFixed(1)} ms, p95 ${p95(times).toFixed(1)} ms (budget ${budget} ms)`);
    assert.ok(p95(times) < budget, `${name}: p95 ${p95(times).toFixed(1)} ms is over ${budget} ms`);
}

test('A6: from starting the engine to its first answer takes under 4 seconds', { skip }, async () => {
    const t0 = Date.now();
    const client = realClient([reviewScripts]);
    try {
        await client.request('loom/check', { file: path.join(reviewScripts, 'clean.loom') });
        const took = Date.now() - t0;
        console.log(`# latency cold start: ${took} ms (budget 4000 ms)`);
        assert.ok(took < 4000, `cold start took ${took} ms`);
    } finally {
        client.dispose();
    }
});

test('A6: the instant tier, warm, answers in under 150 ms; a full review in under 1 s', { skip }, async () => {
    const client = realClient([reviewScripts]);
    try {
        // the biggest fixture: 240 findings
        const file = path.join(reviewScripts, 'many.loom');
        const text = fs.readFileSync(file, 'utf8');
        await client.request('loom/review', { file, tier: 'full', text });
        const instant: number[] = [];
        const full: number[] = [];
        for (let i = 0; i < 30; i++) {
            await timed(instant, () => client.request('loom/review', { file, tier: 'instant', text: `${text}\n// ${i}\n` }));
        }
        for (let i = 0; i < 20; i++) {
            await timed(full, () => client.request('loom/review', { file, tier: 'full', text: `${text}\n// ${i}\n` }));
        }
        report('instant tier', instant, 150);
        report('full review', full, 1000);
    } finally {
        client.dispose();
    }
});

test('A6: completion and hover, warm, answer in under 100 ms (the engine\'s index and the answer together)', { skip }, async () => {
    const client = realClient([semanticScripts]);
    try {
        const file = path.join(semanticScripts, 'main.loom');
        const text = fs.readFileSync(file, 'utf8');
        const complete: number[] = [];
        const hover: number[] = [];
        const where = at(text, '        run Archive');
        for (let i = 0; i < 40; i++) {
            const t = typed(text, where, `        note intent.${i % 2 ? 'k' : ''}`);
            await timed(complete, async () => {
                const index = parseIndex((await client.request('loom/symbols', { file, text: t.text })).result)!;
                assert.ok(suggest(index, file, t.text, t.cursor)!.items.length > 0);
            });
            const edited = `${text}\n// ${i}\n`;
            await timed(hover, async () => {
                const index = parseIndex((await client.request('loom/symbols', { file, text: edited })).result)!;
                assert.ok(hoverAt(index, file, edited, at(edited, 'to Replier', 4)));
            });
        }
        report('completion', complete, 100);
        report('hover', hover, 100);
    } finally {
        client.dispose();
    }
});

test('A6: renaming an agent used across a 20-file project takes under 1 second', { skip }, async () => {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'loom-rename-'));
    const client = realClient([dir]);
    try {
        // twenty files, each defining an agent and a workflow that delegates to the shared agent in lib.loom
        fs.writeFileSync(path.join(dir, 'lib.loom'), 'agent Shared { model: "m" }\n');
        const imports: string[] = ['import "lib.loom"'];
        for (let i = 0; i < 19; i++) {
            fs.writeFileSync(path.join(dir, `part${i}.loom`), `import "lib.loom"\nagent Local${i} { model: "m" }\nworkflow Flow${i}(x) {\n  delegate "go {x}" to Shared -> r${i}\n  delegate "again {r${i}}" to Local${i} -> s${i}\n  return s${i}\n}\n`);
            imports.push(`import "part${i}.loom"`);
        }
        const main = path.join(dir, 'main.loom');
        fs.writeFileSync(main, `${imports.join('\n')}\nworkflow Main(x) {\n  delegate "start" to Shared -> first\n  return first\n}\n`);
        await client.request('loom/symbols', { file: main }); // warm
        const times: number[] = [];
        const text = fs.readFileSync(main, 'utf8');
        for (let i = 0; i < 12; i++) {
            const edited = `${text}\n// ${i}\n`;
            await timed(times, async () => {
                const index = parseIndex((await client.request('loom/symbols', { file: main, text: edited })).result)!;
                const plan = planRename(index, main, at(edited, 'to Shared', 4), `Renamed${i}`, { isWritable: () => true, exists: () => false });
                assert.ok(plan.ok);
                if (plan.ok) {
                    assert.equal(plan.edits.length, 1 + 1 + 19, 'the definition, main, the 19 parts');
                }
            });
        }
        report('rename of 20 files', times, 1000);
    } finally {
        client.dispose();
        fs.rmSync(dir, { recursive: true, force: true });
    }
});
