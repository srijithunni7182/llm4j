import test from 'node:test';
import assert from 'node:assert/strict';
import * as path from 'path';
import { promptFilePath, promptReferences, promptsFolder, starterText } from '../src/prompts/promptFiles';
import { createPromptFile, CreatePromptHost } from '../src/prompts/createPromptFile';

const script = [
    'agent Researcher { model: "m" prompt: "researcher" }',
    'agent Writer {',
    '    model: "m"',
    '    prompt: "writer@v2"',
    '    // prompt: "commented-out"',
    '}',
    'agent Old { model: "m" system_template: "legacy" }',
    'agent Inline { model: "m" system: "no prompt: file here" }',
].join('\n');

test('R7.2: the prompts a script names are found with their lines, comments and strings aside', () => {
    assert.deepEqual(promptReferences(script), [
        { id: 'researcher', version: undefined, line: 1 },
        { id: 'writer', version: 'v2', line: 4 },
        { id: 'legacy', version: undefined, line: 7 },
    ]);
});

test('the prompt folder is prompts/ beside the script unless the script names another', () => {
    assert.equal(promptsFolder('/p/main.loom', script), path.join('/p', 'prompts'));
    assert.equal(promptsFolder('/p/main.loom', 'prompts: "./shared/p"\n' + script), path.resolve('/p', 'shared/p'));
    assert.equal(promptsFolder('/p/main.loom', '// prompts: "nope"\n' + script), path.join('/p', 'prompts'));
});

test('a prompt file is <id>.md, or <id>/<version>.md when a version is named', () => {
    assert.equal(promptFilePath('/p/prompts', { id: 'writer', line: 1 }), path.join('/p/prompts', 'writer.md'));
    assert.equal(promptFilePath('/p/prompts', { id: 'writer', version: 'v2', line: 1 }), path.join('/p/prompts', 'writer', 'v2.md'));
});

test('a new prompt file starts with a description and a reminder of what to say', () => {
    const text = starterText({ id: 'researcher', line: 1 });
    assert.match(text, /^---\ndescription: .*researcher.*\n---\n/);
    assert.match(text, /You are researcher\./);
});

function fakeHost(over: Partial<CreatePromptHost> & { text?: string; cursorLine?: number; existing?: string[] } = {}) {
    const written: Record<string, string> = {};
    const opened: string[] = [];
    const reports: string[] = [];
    const existing = new Set(over.existing ?? []);
    const host: CreatePromptHost = {
        activeScript: () => ({ file: '/p/main.loom', text: over.text ?? script, cursorLine: over.cursorLine ?? 0 }),
        exists: (f) => existing.has(f) || f in written,
        write: (f, c) => { written[f] = c; },
        open: async (f) => { opened.push(f); },
        pick: over.pick ?? (async () => undefined),
        report: (m, l) => { reports.push(`${l}: ${m}`); },
        ...(over.activeScript ? { activeScript: over.activeScript } : {}),
    };
    return { host, written, opened, reports };
}

test('the prompt on the cursor line is the one created, in the folder beside the script, and it is opened', async () => {
    const h = fakeHost({ cursorLine: 4 });

    const file = await createPromptFile(h.host);

    assert.equal(file, path.join('/p', 'prompts', 'writer', 'v2.md'));
    assert.deepEqual(Object.keys(h.written), [file]);
    assert.deepEqual(h.opened, [file]);
});

test('with the cursor elsewhere the person picks among the missing ones', async () => {
    let offered: string[] = [];
    const h = fakeHost({ pick: async (choices) => { offered = choices; return 'legacy'; } });

    const file = await createPromptFile(h.host);

    assert.deepEqual(offered, ['researcher', 'writer@v2', 'legacy']);
    assert.equal(file, path.join('/p', 'prompts', 'legacy.md'));
});

test('cancelling the pick creates nothing', async () => {
    const h = fakeHost();

    assert.equal(await createPromptFile(h.host), undefined);
    assert.deepEqual(h.written, {});
});

test('a prompt that already has a file is not offered, and a lone missing one is created without asking', async () => {
    const h = fakeHost({
        existing: [path.join('/p', 'prompts', 'researcher.md'), path.join('/p', 'prompts', 'writer', 'v2.md')],
        pick: async () => { throw new Error('should not ask'); },
    });

    assert.equal(await createPromptFile(h.host), path.join('/p', 'prompts', 'legacy.md'));
});

test('when every prompt has a file, or the script names none, it says so and writes nothing', async () => {
    const none = fakeHost({ text: 'agent A { model: "m" system: "s" }' });
    assert.equal(await createPromptFile(none.host), undefined);
    assert.match(none.reports[0], /no prompt: "…" line/);

    const all = fakeHost({ existing: [path.join('/p', 'prompts', 'researcher.md'), path.join('/p', 'prompts', 'writer', 'v2.md'), path.join('/p', 'prompts', 'legacy.md')] });
    assert.equal(await createPromptFile(all.host), undefined);
    assert.match(all.reports[0], /already has a file/);
    assert.deepEqual(all.written, {});
});

test('with no .loom file open it says what to do', async () => {
    const h = fakeHost({ activeScript: () => undefined });

    assert.equal(await createPromptFile(h.host), undefined);
    assert.match(h.reports[0], /^error: Loom: open a \.loom file/);
});
