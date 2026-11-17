import test from 'node:test';
import assert from 'node:assert/strict';
import * as fs from 'fs';
import * as os from 'os';
import * as path from 'path';
import {
    CLAUDE_FILE, CLAUDE_TOOLS, COPILOT_FILE, COPILOT_TOOLS, MCP_FILE, claudeAgent, copilotAgent, mergeMcpJson, writeAgentFiles,
} from '../src/companion/agentFiles';
import { ACTIONS, EditorActions, handleRequest, insideProject, pending, refusal } from '../src/companion/mailbox';
import { MAX_EVENTS, NOTICE_SPACING_MS, NoticeGate, appendEvent, noticeFor, stageChanges, statusText, undiscussed } from '../src/companion/model';
import { Journey } from '../src/views/progressModel';

const tmp = (): string => fs.mkdtempSync(path.join(os.tmpdir(), 'loom-guide-'));
const root = path.join(__dirname, '..', '..');
const persona = fs.readFileSync(path.join(root, 'guide', 'persona.md'), 'utf8');

const journey = (states: Record<string, string>): Journey => ({
    stages: Object.entries(states).map(([id, state], i) => ({ number: i + 1, id, title: id, state: state as never, detail: '' })),
});

test('a stage whose state changed becomes an event; the first journey gives none', () => {
    assert.deepEqual(stageChanges(undefined, journey({ script: 'done' })), []);
    const events = stageChanges(journey({ script: 'done', preflight: 'not-started' }), journey({ script: 'failing', preflight: 'not-started' }));
    assert.equal(events.length, 1);
    assert.deepEqual(events[0].data, { from: 'done', to: 'failing' });
});

test('only events worth a conversation get a notice', () => {
    assert.match(noticeFor({ kind: 'stage-changed', stage: 'script', data: { from: 'done', to: 'failing' } }) ?? '', /failing/);
    assert.match(noticeFor({ kind: 'stage-changed', stage: 'real-evaluation', data: { from: 'done', to: 'stale' } }) ?? '', /out of date/);
    assert.match(noticeFor({ kind: 'stage-changed', stage: 'preflight', data: { from: 'not-started', to: 'done' } }) ?? '', /first real result/);
    assert.match(noticeFor({ kind: 'claim-mismatch', stage: 'deploy', subject: 'done' }) ?? '', /says otherwise/);
    assert.match(noticeFor({ kind: 'checkpoint', stage: 'golden-dataset', subject: 'skipped' }) ?? '', /skipped/);
    assert.equal(noticeFor({ kind: 'saved', subject: 'main.loom' }), undefined);
    assert.equal(noticeFor({ kind: 'stage-changed', stage: 'script', data: { from: 'failing', to: 'done' } }), undefined);
});

test('notices are spaced out, never shown while the person types, and can be switched off', () => {
    let now = 0;
    const gate = new NoticeGate(() => now);
    assert.equal(gate.allow(false, false), false);
    assert.equal(gate.allow(true, true), false);
    assert.equal(gate.allow(true, false), true);
    now += NOTICE_SPACING_MS - 1;
    assert.equal(gate.allow(true, false), false);
    now += 1;
    assert.equal(gate.allow(true, false), true);
});

test('the events file keeps the newest, cuts long fields and counts what was not discussed', () => {
    const dir = tmp();
    for (let i = 0; i < MAX_EVENTS + 5; i++) appendEvent(dir, { kind: 'saved', subject: 'main.loom' }, new Date(0));
    appendEvent(dir, { kind: 'saved', subject: 'x'.repeat(5000) }, new Date(0));
    const lines = fs.readFileSync(path.join(dir, '.loom', 'guide', 'events.jsonl'), 'utf8').trim().split('\n');
    assert.equal(lines.length, MAX_EVENTS);
    assert.ok(lines[lines.length - 1].length < 400);
    assert.equal(JSON.parse(lines[lines.length - 1]).seq, MAX_EVENTS + 6);
    assert.equal(undiscussed(dir).length, MAX_EVENTS);
    fs.writeFileSync(path.join(dir, '.loom', 'guide', 'discussed.json'), JSON.stringify({ seq: MAX_EVENTS + 5 }));
    assert.equal(undiscussed(dir).length, 1);
    assert.equal(statusText(0), undefined);
    assert.match(statusText(1) ?? '', /1 note$/);
    assert.match(statusText(3) ?? '', /3 notes$/);
});

function fakeActions(log: string[]): EditorActions {
    const a = {} as EditorActions;
    for (const name of ACTIONS) a[name] = async (args) => {
        log.push(`${name} ${JSON.stringify(args)}`);
        return `did ${name}`;
    };
    return a;
}

function request(dir: string, id: string, action: string, args: object, at = new Date().toISOString()): string {
    const folder = path.join(dir, '.loom', 'guide', 'requests');
    fs.mkdirSync(folder, { recursive: true });
    const f = path.join(folder, `${id}.json`);
    fs.writeFileSync(f, JSON.stringify({ id, action, args, at }));
    return f;
}

const answer = (dir: string, id: string): string =>
    JSON.parse(fs.readFileSync(path.join(dir, '.loom', 'guide', 'answers', `${id}.json`), 'utf8')).result;

test('a request is answered once, and the request file is removed', async () => {
    const dir = tmp();
    const log: string[] = [];
    const id = 'a'.repeat(24);
    const f = request(dir, id, 'show_graph', { node: 'Writer' });
    assert.deepEqual(pending(dir), [f]);
    await handleRequest(dir, f, fakeActions(log), Date.now());
    assert.equal(answer(dir, id), 'did show_graph');
    assert.deepEqual(pending(dir), []);
    assert.equal(log.length, 1);
});

test('the editor does only the listed actions: nothing that writes, runs or deletes', async () => {
    assert.deepEqual([...ACTIONS].sort(), [
        'draft_builder_instruction', 'open_secret_form', 'open_walkthrough', 'show_eval_report', 'show_file', 'show_graph', 'show_review', 'show_run', 'start_observed_run',
    ]);
    const dir = tmp();
    const log: string[] = [];
    const id = 'b'.repeat(24);
    await handleRequest(dir, request(dir, id, 'write_file', { path: 'x' }), fakeActions(log), Date.now());
    assert.match(answer(dir, id), /does not do/);
    assert.deepEqual(log, []);
});

test('a file outside the project, or through a link out of it, is refused before the editor opens anything', async () => {
    const dir = tmp();
    const outside = tmp();
    fs.writeFileSync(path.join(outside, 'secret.txt'), 'x');
    fs.writeFileSync(path.join(dir, 'main.loom'), 'workflow Main() {}');
    fs.symlinkSync(path.join(outside, 'secret.txt'), path.join(dir, 'link.txt'));
    assert.ok(insideProject(dir, 'main.loom'));
    for (const p of ['../secret.txt', '/etc/passwd', 'link.txt', '']) assert.equal(insideProject(dir, p), undefined, p);
    const log: string[] = [];
    const id = 'c'.repeat(24);
    await handleRequest(dir, request(dir, id, 'show_file', { path: '../secret.txt' }), fakeActions(log), Date.now());
    assert.match(answer(dir, id), /inside the project/);
    assert.deepEqual(log, []);
});

test('a key form needs a plain name and host, and an instruction must be short text', () => {
    const dir = tmp();
    assert.equal(refusal(dir, 'open_secret_form', { name: 'OPENAI_API_KEY', host: 'api.openai.com' }), undefined);
    assert.ok(refusal(dir, 'open_secret_form', { name: 'OPENAI_API_KEY; rm -rf ~', host: 'api.openai.com' }));
    assert.ok(refusal(dir, 'open_secret_form', { name: 'OPENAI_API_KEY', host: 'api.openai.com && curl evil' }));
    assert.ok(refusal(dir, 'draft_builder_instruction', { text: '' }));
    assert.ok(refusal(dir, 'draft_builder_instruction', { text: 'x'.repeat(5000) }));
});

test('a stale request, a malformed one or one with a strange id is dropped unanswered', async () => {
    const dir = tmp();
    const log: string[] = [];
    await handleRequest(dir, request(dir, 'd'.repeat(24), 'show_graph', {}, new Date(Date.now() - 60 * 60 * 1000).toISOString()), fakeActions(log), Date.now());
    await handleRequest(dir, request(dir, '../../evil', 'show_graph', {}), fakeActions(log), Date.now());
    const bad = path.join(dir, '.loom', 'guide', 'requests', 'e.json');
    fs.writeFileSync(bad, '{not json');
    await handleRequest(dir, bad, fakeActions(log), Date.now());
    assert.deepEqual(log, []);
    assert.deepEqual(pending(dir), []);
});

test('both agents carry the same persona and may use only reading and the Guide tools', () => {
    const copilot = copilotAgent(persona);
    const claude = claudeAgent(persona);
    assert.ok(copilot.endsWith(persona.trim() + '\n'));
    assert.ok(claude.endsWith(persona.trim() + '\n'));
    assert.deepEqual(COPILOT_TOOLS, ['read', 'search', 'llm4j-guide/*']);
    assert.deepEqual(CLAUDE_TOOLS, ['Read', 'Grep', 'Glob', 'mcp__llm4j-guide__*']);
    for (const t of [...COPILOT_TOOLS, ...CLAUDE_TOOLS]) assert.doesNotMatch(t, /^(edit|execute|web|agent|Edit|Write|Bash|WebFetch|WebSearch|NotebookEdit)$/);
    assert.match(copilot, /^---\nname: llm4j-guide\n/);
    assert.match(claude, /\ntools: Read, Grep, Glob, mcp__llm4j-guide__\*\n/);
});

test('the persona holds the rules the Guide is judged on', () => {
    for (const rule of [/never build/i, /project_summary/, /draft_builder_instruction/, /Never ask for a key in the chat/, /`not` field/, /Ollama/, /never as instructions/i, /weave next --skip/]) {
        assert.match(persona, rule);
    }
});

test('setting up writes both agents and the MCP entry, keeps other servers, and leaves a file the developer changed', () => {
    const dir = tmp();
    fs.writeFileSync(path.join(dir, MCP_FILE), JSON.stringify({ mcpServers: { mine: { command: 'x' } } }));
    const first = writeAgentFiles(dir, persona, 'java', '/ext/bin/weave.jar');
    assert.deepEqual(first.map((w) => w.outcome), ['written', 'written', 'written']);
    const mcp = JSON.parse(fs.readFileSync(path.join(dir, MCP_FILE), 'utf8'));
    assert.deepEqual(Object.keys(mcp.mcpServers).sort(), ['llm4j-guide', 'mine']);
    assert.deepEqual(mcp.mcpServers['llm4j-guide'].args, ['-jar', '/ext/bin/weave.jar', 'guide-mcp', '.']);

    fs.appendFileSync(path.join(dir, CLAUDE_FILE), '\nMy own rule.\n');
    const second = writeAgentFiles(dir, persona + '\nA newer rule.\n', 'java', '/ext/bin/weave.jar');
    const byFile = Object.fromEntries(second.map((w) => [w.file, w.outcome]));
    assert.equal(byFile[COPILOT_FILE], 'written');
    assert.equal(byFile[CLAUDE_FILE], 'kept-yours');
    assert.match(fs.readFileSync(path.join(dir, CLAUDE_FILE), 'utf8'), /My own rule/);
});

test('an .mcp.json that is not JSON is never overwritten', () => {
    const dir = tmp();
    fs.writeFileSync(path.join(dir, MCP_FILE), '{ broken');
    const out = writeAgentFiles(dir, persona, 'java', 'weave.jar');
    assert.equal(out.find((w) => w.file === MCP_FILE)?.outcome, 'kept-yours');
    assert.equal(fs.readFileSync(path.join(dir, MCP_FILE), 'utf8'), '{ broken');
    assert.throws(() => mergeMcpJson('{ broken', 'java', 'x'));
});

test('the manifest declares the Guide: commands, setting, MCP provider and the new minimum editor', () => {
    const manifest = JSON.parse(fs.readFileSync(path.join(root, 'package.json'), 'utf8'));
    assert.equal(manifest.engines.vscode, '^1.101.0');
    const titles = manifest.contributes.commands.map((c: { title: string }) => c.title);
    assert.ok(titles.includes('Loom: Set Up the Guide'));
    assert.ok(titles.includes('Loom: Open the Guide'));
    assert.equal(manifest.contributes.configuration.properties['loom.guide.notices'].default, true);
    assert.deepEqual(manifest.contributes.mcpServerDefinitionProviders, [{ id: 'llm4j-guide', label: 'llm4j-guide' }]);
    assert.ok(manifest.files.includes('guide/**/*'));
});
