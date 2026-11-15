import test from 'node:test';
import assert from 'node:assert/strict';
import * as fs from 'fs';
import * as os from 'os';
import * as path from 'path';
import {
    BUTTON_COPY_PROMPT, BUTTON_OPEN_CHAT, HandoffHost, PIPELINE_SKILLS, SECTION_BEGIN, SECTION_END, SKILL_ROOTS, STARTER_PROMPT,
    agentsSection, handOffToAgent, syncAgentFiles, upsertSection,
} from '../src/guide/agentSkills';
import { GuideHost, installSkill } from '../src/guide/guideCommands';

const tmp = (): string => fs.mkdtempSync(path.join(os.tmpdir(), 'loom-sync-'));

/** What the jar writes: the four skills under .claude/skills of the given folder. */
function fakeWeave(calls: string[][] = []): (args: string[]) => Promise<string> {
    return async (args) => {
        calls.push(args);
        const target = args[args.indexOf('--install-skill') + 1];
        for (const s of PIPELINE_SKILLS) {
            const dir = path.join(target, '.claude', 'skills', s);
            fs.mkdirSync(path.join(dir, 'references'), { recursive: true });
            fs.writeFileSync(path.join(dir, 'SKILL.md'), `# ${s}\n`);
            fs.writeFileSync(path.join(dir, 'references', 'a.md'), 'ref');
        }
        return 'ok';
    };
}

test('the skills are written to all three harness folders, with their references', async () => {
    const dir = tmp();
    const written = await syncAgentFiles(dir, fakeWeave());
    for (const root of SKILL_ROOTS) {
        for (const s of PIPELINE_SKILLS) {
            assert.ok(fs.existsSync(path.join(dir, root, s, 'SKILL.md')), `${root}/${s}`);
            assert.ok(fs.existsSync(path.join(dir, root, s, 'references', 'a.md')));
        }
    }
    assert.deepEqual(written, [...SKILL_ROOTS, 'AGENTS.md']);
});

test('skills already installed under .claude/skills are mirrored without asking the jar again, and other skills there are kept', async () => {
    const dir = tmp();
    await fakeWeave()(['guide', '--install-skill', dir]);
    fs.mkdirSync(path.join(dir, '.agents', 'skills', 'mine'), { recursive: true });
    fs.writeFileSync(path.join(dir, '.agents', 'skills', 'mine', 'SKILL.md'), 'mine');
    const calls: string[][] = [];
    await syncAgentFiles(dir, fakeWeave(calls));
    assert.deepEqual(calls, []);
    assert.equal(fs.readFileSync(path.join(dir, '.agents', 'skills', 'mine', 'SKILL.md'), 'utf8'), 'mine');
    assert.ok(fs.existsSync(path.join(dir, '.github', 'skills', 'llm4j-eval-design', 'SKILL.md')));
});

test('AGENTS.md: created when missing, appended after the user text, and the user text is never changed', async () => {
    const fresh = tmp();
    await syncAgentFiles(fresh, fakeWeave());
    assert.ok(fs.readFileSync(path.join(fresh, 'AGENTS.md'), 'utf8').startsWith(SECTION_BEGIN));

    const dir = tmp();
    const mine = '# My project\n\nAlways use tabs.\n';
    fs.writeFileSync(path.join(dir, 'AGENTS.md'), mine);
    await syncAgentFiles(dir, fakeWeave());
    const text = fs.readFileSync(path.join(dir, 'AGENTS.md'), 'utf8');
    assert.ok(text.startsWith(mine.trimEnd()));
    assert.ok(text.includes('Always use tabs.'));
    for (const s of PIPELINE_SKILLS) {
        assert.ok(text.includes(s));
    }
});

test('AGENTS.md: running it again changes nothing, and an edited block is repaired while text around it stays', async () => {
    const dir = tmp();
    fs.writeFileSync(path.join(dir, 'AGENTS.md'), 'before\n');
    await syncAgentFiles(dir, fakeWeave());
    const once = fs.readFileSync(path.join(dir, 'AGENTS.md'), 'utf8');
    await syncAgentFiles(dir, fakeWeave());
    assert.equal(fs.readFileSync(path.join(dir, 'AGENTS.md'), 'utf8'), once);
    assert.equal(once.split(SECTION_BEGIN).length, 2, 'one block only');

    const damaged = once.replace('## Loom workflows', '## stale').trimEnd() + '\n\nafter\n';
    fs.writeFileSync(path.join(dir, 'AGENTS.md'), damaged);
    await syncAgentFiles(dir, fakeWeave());
    const healed = fs.readFileSync(path.join(dir, 'AGENTS.md'), 'utf8');
    assert.ok(healed.includes('## Loom workflows') && !healed.includes('## stale'));
    assert.ok(healed.startsWith('before') && healed.trimEnd().endsWith('after'));
});

test('upsertSection leaves an unclosed marker alone by appending, and keeps the jar block that has a different marker', () => {
    const jar = '<!-- loom:begin (written by weave guide --install-skill; edit outside this block) -->\nx\n<!-- loom:end -->\n';
    const out = upsertSection(jar, agentsSection());
    assert.ok(out.startsWith(jar.trimEnd()));
    assert.ok(out.includes(SECTION_END));
    const unclosed = `${SECTION_BEGIN}\nhalf written`;
    assert.ok(upsertSection(unclosed, agentsSection()).startsWith(unclosed));
});

test('the section tells the agent to run weave check and weave review', () => {
    assert.match(agentsSection(), /weave check/);
    assert.match(agentsSection(), /weave review/);
});

function handoffHost(opts: { chat: boolean; choice?: string }): HandoffHost & { clip: string[]; chats: string[]; messages: string[]; buttons: string[][] } {
    const h = {
        clip: [] as string[], chats: [] as string[], messages: [] as string[], buttons: [] as string[][],
        async copyToClipboard(t: string) { h.clip.push(t); },
        async openChat(q: string) { h.chats.push(q); return opts.chat; },
        async notify(m: string, b: string[]) { h.messages.push(m); h.buttons.push(b); return opts.choice; },
    };
    return h;
}

test('the hand-off copies the starter prompt, opens the chat with it, and names what was installed', async () => {
    const h = handoffHost({ chat: true });
    await handOffToAgent(h, ['.agents/skills', 'AGENTS.md']);
    assert.equal(STARTER_PROMPT, 'Use the llm4j-harness-design skill to design and build a Loom workflow for: ');
    assert.deepEqual(h.clip, [STARTER_PROMPT]);
    assert.deepEqual(h.chats, [STARTER_PROMPT]);
    assert.match(h.messages[0], /\.agents\/skills, AGENTS\.md/);
    assert.deepEqual(h.buttons[0], [BUTTON_OPEN_CHAT, BUTTON_COPY_PROMPT]);
});

test('when the chat cannot be opened the message points at the clipboard', async () => {
    const h = handoffHost({ chat: false });
    await handOffToAgent(h, ['.claude/skills']);
    assert.match(h.messages[0], /clipboard/);
});

test('the two buttons open the chat again and copy the prompt again', async () => {
    const open = handoffHost({ chat: true, choice: BUTTON_OPEN_CHAT });
    await handOffToAgent(open, ['x']);
    assert.equal(open.chats.length, 2);
    const copy = handoffHost({ chat: true, choice: BUTTON_COPY_PROMPT });
    await handOffToAgent(copy, ['x']);
    assert.equal(copy.clip.length, 2);
});

function guideHost(afterSetUp: GuideHost['afterSetUp']): { host: GuideHost; reports: Array<[string, string]> } {
    const reports: Array<[string, string]> = [];
    const host: GuideHost = {
        weave: async () => 'ok', workspaceFolder: () => '/proj', pick: async () => undefined, showMarkdown: async () => undefined,
        pickAgents: async () => ['claude'], confirm: async () => true, report: (m, l) => void reports.push([m, l]), afterSetUp,
    };
    return { host, reports };
}

test('Set Up My Coding Agent runs the hand-off after the install, and a failing hand-off is a warning, not a failed install', async () => {
    let ran = 0;
    assert.equal(await installSkill(guideHost(async (f) => { assert.equal(f, '/proj'); ran++; }).host), true);
    assert.equal(ran, 1);
    const bad = guideHost(async () => { throw new Error('disk full'); });
    assert.equal(await installSkill(bad.host), true);
    assert.equal(bad.reports[1][1], 'warning');
    assert.match(bad.reports[1][0], /disk full/);
});
