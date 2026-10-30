import test from 'node:test';
import assert from 'node:assert/strict';
// eslint-disable-next-line @typescript-eslint/no-var-requires
const Module = require('module');
import { createFakeVscode } from './fakeVscode';

/** Loads the provider against the fake editor, and gives back what it created. */
function provider() {
    const fake = createFakeVscode({});
    const originalLoad = Module._load;
    Module._load = function (request: string, ...rest: unknown[]) {
        return request === 'vscode' ? fake.vscode : originalLoad.call(this, request, ...rest);
    };
    for (const key of Object.keys(require.cache)) {
        if (/views[\\/]WorkflowOutlineProvider/.test(key)) {
            delete require.cache[key];
        }
    }
    try {
        const { WorkflowOutlineProvider } = require('../src/views/WorkflowOutlineProvider');
        return { fake, outline: new WorkflowOutlineProvider() };
    } finally {
        Module._load = originalLoad;
    }
}

test('R3: the Outline creates a status-bar item and two file watchers, and removes all of them when it is disposed', () => {
    const { fake, outline } = provider();
    assert.equal(fake.state.statusBarItems.length, 1);
    assert.equal(fake.state.watchers.length, 2);
    assert.deepEqual(fake.state.watchers.map((w: any) => w.glob), ['**/*.loom', '**/{eval/**,EVAL_DESIGN.md,*.test.yaml,*.test.yml}']);
    const listenersBefore = fake.state.save.count + fake.state.activeEditorChanged.count;
    assert.ok(listenersBefore >= 2);

    outline.dispose();

    assert.equal(fake.state.statusBarItems[0].disposed, true, 'the status-bar item is gone');
    assert.ok(fake.state.watchers.every((w: any) => w.disposed), 'both watchers are disposed');
    assert.equal(fake.state.save.count + fake.state.activeEditorChanged.count, 0, 'no listener is left');
    assert.equal(fake.state.watchers.reduce((n: number, w: any) => n + w.created.count + w.deleted.count + w.changed.count, 0), 0, 'no watcher listener is left');
});

test('R3: disposing twice does nothing the second time', () => {
    const { outline } = provider();
    outline.dispose();
    assert.doesNotThrow(() => outline.dispose());
});
