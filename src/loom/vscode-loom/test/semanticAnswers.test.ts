import { strict as assert } from 'assert';
import * as path from 'path';
import { test } from 'node:test';
import { EngineClient } from '../src/engine/client';
import { EngineTimeoutError } from '../src/engine/errors';
import { completionAnswer, definitionAnswer, hoverAnswer, outlineAnswer, referencesAnswer, signatureAnswer } from '../src/semantic/answers';
import { EngineSymbolsSource, SymbolsSource } from '../src/semantic/source';
import { at, fixtureIndex, fixtureText, SCRIPTS } from './semanticSupport';

const main = path.join(SCRIPTS, 'main.loom');
const text = fixtureText('main.loom');
const withIndex: SymbolsSource = { index: async () => fixtureIndex('main') };
const withNothing: SymbolsSource = { index: async () => undefined };

test('R22: with an index every feature answers', async () => {
    assert.ok(await definitionAnswer(withIndex, main, at(text, 'to Replier', 4)));
    assert.ok(await referencesAnswer(withIndex, main, at(text, 'to Replier', 4), true));
    assert.ok(await hoverAnswer(withIndex, main, text, at(text, 'to Replier', 4)));
    assert.ok(await outlineAnswer(withIndex, main));
    assert.ok(await signatureAnswer(withIndex, main, 'call Escalate('));
    assert.ok(await completionAnswer(withIndex, main, text.replace('        run Archive', '        rewind to \n        run Archive'), at(text.replace('        run Archive', '        rewind to \n        run Archive'), 'rewind to \n', 10)));
});

test('R22: with no index (no engine, a failure) every feature steps aside, each by itself', async () => {
    for (const source of [withNothing, undefined]) {
        assert.equal(await definitionAnswer(source, main, at(text, 'to Replier', 4)), undefined);
        assert.equal(await referencesAnswer(source, main, at(text, 'to Replier', 4), true), undefined);
        assert.equal(await hoverAnswer(source, main, text, at(text, 'to Replier', 4)), undefined);
        assert.equal(await completionAnswer(source, main, text, at(text, 'to Replier', 4)), undefined);
        assert.equal(await outlineAnswer(source, main), undefined);
        assert.equal(await signatureAnswer(source, main, 'call Escalate('), undefined);
    }
});

test('R22: a place the index does not know is left to the older answer', async () => {
    assert.equal(await definitionAnswer(withIndex, main, at(text, 'workflow Main', 2)), undefined);
    assert.equal(await definitionAnswer(withIndex, main, at(text, 'run Archive', 5)), undefined, 'a task has no definition');
    assert.equal(await hoverAnswer(withIndex, main, text, at(text, 'workflow Main', 2)), undefined);
});

class FakeEngine implements Partial<EngineClient> {
    readonly calls: Array<{ method: string; params: any; options: any }> = [];
    constructor(private readonly respond: () => Promise<unknown>) {}
    async request(method: string, params: any, options: any): Promise<any> {
        this.calls.push({ method, params, options });
        return this.respond();
    }
}

function sourceOver(engine: FakeEngine | undefined, log: string[] = []): EngineSymbolsSource {
    return new EngineSymbolsSource(() => engine as unknown as EngineClient | undefined, (file) => ({ text: file === main ? 'unsaved' : undefined, buffers: { '/other.loom': 'changed' } }), (l) => log.push(l));
}

test('R22: the engine source sends the unsaved text and the other changed files, and returns the index', async () => {
    const engine = new FakeEngine(async () => ({ result: fixtureIndex('main'), file: main, status: 0, cached: false }));
    const index = await sourceOver(engine).index(main);
    assert.ok(index);
    assert.equal(engine.calls[0].method, 'loom/symbols');
    assert.deepEqual(engine.calls[0].params, { file: main, text: 'unsaved', buffers: { '/other.loom': 'changed' } });
});

test('R22: no engine, a timeout, or an answer in a form it does not know is "no index", and the reason is logged', async () => {
    assert.equal(await sourceOver(undefined).index(main), undefined);
    const log: string[] = [];
    assert.equal(await sourceOver(new FakeEngine(() => Promise.reject(new EngineTimeoutError(5000))), log).index(main), undefined);
    assert.match(log.join('\n'), /loom\/symbols failed/);
    const odd: string[] = [];
    assert.equal(await sourceOver(new FakeEngine(async () => ({ result: { version: 99 } })), odd).index(main), undefined);
    assert.match(odd.join('\n'), /form this extension does not know/);
});
