import test from 'node:test';
import assert from 'node:assert/strict';
import * as fs from 'fs';
import * as os from 'os';
import * as path from 'path';
import { GUIDE_PAGES, GuideHost, installSkill, openGuide } from '../src/guide/guideCommands';
import { WeaveGraphRunner } from '../src/graph/runner';

interface Fake {
    host: GuideHost;
    calls: string[][];
    shown: string[];
    reports: { message: string; level: string }[];
    confirms: string[];
}

function fake(opts: { folder?: string | undefined; pick?: string; confirm?: boolean; weave?: (args: string[]) => Promise<string> } = {}): Fake {
    const f: Fake = { calls: [], shown: [], reports: [], confirms: [], host: undefined as unknown as GuideHost };
    f.host = {
        async weave(args) {
            f.calls.push(args);
            return opts.weave ? opts.weave(args) : 'ok';
        },
        workspaceFolder: () => ('folder' in opts ? opts.folder : '/proj'),
        pick: async (choices) => choices.find((c) => c.page === opts.pick),
        async showMarkdown(text) {
            f.shown.push(text);
        },
        confirm: async (message) => {
            f.confirms.push(message);
            return opts.confirm ?? false;
        },
        report: (message, level) => f.reports.push({ message, level }),
    };
    return f;
}

test('the menu lists the readme, the ten chapters and the Loom reference', () => {
    assert.deepEqual(GUIDE_PAGES.map((p) => p.page), ['readme', '1', '2', '3', '4', '5', '6', '7', '8', '9', '10', 'loom']);
});

test('Open Guide prints the page that was picked and shows it', async () => {
    const f = fake({ pick: '6', weave: async () => '# Build the workflow' });
    assert.equal(await openGuide(f.host), true);
    assert.deepEqual(f.calls, [['guide', '6']]);
    assert.deepEqual(f.shown, ['# Build the workflow']);
});

test('Open Guide does nothing when the pick is cancelled', async () => {
    const f = fake({ pick: 'none' });
    assert.equal(await openGuide(f.host), false);
    assert.deepEqual(f.calls, []);
    assert.deepEqual(f.shown, []);
});

test('Open Guide says so when weave cannot be run', async () => {
    const f = fake({ pick: 'loom', weave: async () => Promise.reject(new Error('java is not installed')) });
    assert.equal(await openGuide(f.host), false);
    assert.equal(f.reports[0].level, 'error');
    assert.match(f.reports[0].message, /java is not installed/);
    assert.deepEqual(f.shown, []);
});

test('Install Skill needs an open folder and runs nothing without one', async () => {
    const f = fake({ folder: undefined });
    assert.equal(await installSkill(f.host), false);
    assert.deepEqual(f.calls, []);
    assert.match(f.reports[0].message, /open a folder/);
});

test('Install Skill runs weave in the open folder and reports what it said', async () => {
    const f = fake({ weave: async () => 'Installed the skill in /proj/.claude/skills/llm4j-workflow-guide (14 files).' });
    assert.equal(await installSkill(f.host), true);
    assert.deepEqual(f.calls, [['guide', '--install-skill', '/proj']]);
    assert.match(f.reports[0].message, /Installed the skill/);
    assert.deepEqual(f.confirms, []);
});

test('Install Skill asks before replacing an older copy, and replaces it only when told to', async () => {
    const already = async (args: string[]) => (args.includes('--force') ? 'Installed the skill' : Promise.reject(new Error('The skill is already in /proj/x. Use --force to replace it.')));
    const yes = fake({ confirm: true, weave: already });
    assert.equal(await installSkill(yes.host), true);
    assert.deepEqual(yes.calls, [['guide', '--install-skill', '/proj'], ['guide', '--install-skill', '/proj', '--force']]);

    const no = fake({ confirm: false, weave: already });
    assert.equal(await installSkill(no.host), false);
    assert.deepEqual(no.calls, [['guide', '--install-skill', '/proj']]);
    assert.equal(no.confirms.length, 1);
});

test('Install Skill reports any other failure and does not ask', async () => {
    const f = fake({ weave: async () => Promise.reject(new Error('disk full')) });
    assert.equal(await installSkill(f.host), false);
    assert.deepEqual(f.confirms, []);
    assert.match(f.reports[0].message, /disk full/);
});

// ---- with the real weave.jar, as the extension runs it -------------------------------------------------------------

const jar = path.join(__dirname, '..', '..', 'bin', 'weave.jar');
const hasJava = require('child_process').spawnSync('java', ['-version']).status === 0;
const skip = !fs.existsSync(jar) ? 'no weave.jar: run scripts/build-vsix.sh' : !hasJava ? 'java is not installed' : false;

function realHost(folder: string): { host: GuideHost; shown: string[]; reports: string[]; confirms: number } {
    const runner = new WeaveGraphRunner({ javaPath: 'java', jarPath: jar, timeoutMs: 60000 });
    const state = { shown: [] as string[], reports: [] as string[], confirms: 0 };
    const base = fake({ folder, pick: 'readme' }).host;
    const host: GuideHost = {
        ...base,
        weave: (args) => runner.runWeave(args, new AbortController().signal, [0]),
        showMarkdown: async (t) => void state.shown.push(t),
        confirm: async () => (state.confirms++, true),
        report: (m) => void state.reports.push(m),
    };
    return { host, ...state, get confirms() { return state.confirms; } } as never;
}

test('with the real jar: every page in the menu prints, with nothing from the repository', { skip }, async () => {
    const runner = new WeaveGraphRunner({ javaPath: 'java', jarPath: jar, timeoutMs: 60000 });
    for (const { page } of GUIDE_PAGES) {
        const text = await runner.runWeave(['guide', page], new AbortController().signal, [0]);
        assert.ok(text.length > 200, `page ${page} is not empty`);
    }
});

test('with the real jar: Open Guide shows the readme', { skip }, async () => {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'loom-guide-'));
    const r = realHost(dir);
    assert.equal(await openGuide(r.host), true);
    assert.match(r.shown[0], /tests/i);
});

test('with the real jar: the skill is installed, then replaced only after the question is answered', { skip }, async () => {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'loom-skill-'));
    const r = realHost(dir);
    assert.equal(await installSkill(r.host), true);
    const skillFile = path.join(dir, '.claude', 'skills', 'llm4j-workflow-guide', 'SKILL.md');
    assert.ok(fs.existsSync(skillFile));
    assert.ok(fs.existsSync(path.join(dir, '.claude', 'skills', 'llm4j-workflow-guide', 'references', '06-build-the-workflow.md')));
    assert.ok(!/docs\/guide\//.test(fs.readFileSync(skillFile, 'utf8')), 'the installed skill points at references/, not at the repository');
    fs.writeFileSync(skillFile, 'edited');
    assert.equal(await installSkill(r.host), true);
    assert.equal(r.confirms, 1);
    assert.match(fs.readFileSync(skillFile, 'utf8'), /llm4j workflow guide/);
});
