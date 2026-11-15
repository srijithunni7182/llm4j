import * as fs from 'fs';
import * as os from 'os';
import * as path from 'path';

/** The four skills of the gated pipeline, in the order an agent should use them. */
export const PIPELINE_SKILLS = ['llm4j-harness-design', 'llm4j-workflow-guide', 'llm4j-eval-design', 'llm4j-loom-deployer'] as const;

/** Where the agent harnesses look for skills: the shared `.agents` convention, Claude Code, and GitHub Copilot. */
export const SKILL_ROOTS = ['.agents/skills', '.claude/skills', '.github/skills'] as const;

/** The prompt that starts the pipeline; it ends in a space so the person can type the goal straight after it. */
export const STARTER_PROMPT = 'Use the llm4j-harness-design skill to design and build a Loom workflow for: ';

export const SECTION_BEGIN = '<!-- loom:skills:begin (written by the Loom extension; edit outside this block) -->';
export const SECTION_END = '<!-- loom:skills:end -->';

/** The text between the markers in AGENTS.md. */
export function agentsSection(): string {
    return [
        SECTION_BEGIN,
        '## Loom workflows',
        '',
        'This project builds multi-agent workflows with Loom (`.loom` scripts, `.loot` tests). Four skills guide the work, in this order:',
        '',
        ...PIPELINE_SKILLS.map((s, i) => `${i + 1}. \`${s}\` (in ${SKILL_ROOTS.map((r) => `\`${r}/${s}/SKILL.md\``).join(', ')})`),
        '',
        'Start with `llm4j-harness-design`. After you create or edit a `.loom` file, run `weave check` and `weave review` (the skills explain how to',
        'read and fix its findings), and tell the person what is still open before you say you are done.',
        SECTION_END,
    ].join('\n');
}

/**
 * Puts `section` into `existing`: replaces a block already between the markers, otherwise appends one after a blank line.
 * Everything outside the markers is returned unchanged, and applying it twice gives the same text.
 */
export function upsertSection(existing: string, section: string): string {
    const begin = existing.indexOf(SECTION_BEGIN);
    const end = begin < 0 ? -1 : existing.indexOf(SECTION_END, begin);
    if (begin >= 0 && end >= 0) {
        return existing.slice(0, begin) + section + existing.slice(end + SECTION_END.length);
    }
    if (existing.trim() === '') {
        return section + '\n';
    }
    return existing.replace(/\s*$/, '') + '\n\n' + section + '\n';
}

/** Writes or updates the Loom block in `<folder>/AGENTS.md`, leaving the rest of the file alone. */
export function writeAgentsSection(folder: string): string {
    const file = path.join(folder, 'AGENTS.md');
    const before = fs.existsSync(file) ? fs.readFileSync(file, 'utf8') : '';
    const after = upsertSection(before, agentsSection());
    if (after !== before) {
        fs.writeFileSync(file, after);
    }
    return file;
}

function hasAllSkills(root: string): boolean {
    return PIPELINE_SKILLS.every((s) => fs.existsSync(path.join(root, s, 'SKILL.md')));
}

/** Copies each pipeline skill from `source` into every root under `folder`, replacing only those four skill directories. */
export function mirrorSkills(source: string, folder: string): string[] {
    const written: string[] = [];
    for (const rel of SKILL_ROOTS) {
        const root = path.join(folder, rel);
        if (path.resolve(root) !== path.resolve(source)) {
            for (const skill of PIPELINE_SKILLS) {
                const target = path.join(root, skill);
                fs.rmSync(target, { recursive: true, force: true });
                fs.cpSync(path.join(source, skill), target, { recursive: true });
            }
        }
        written.push(rel);
    }
    return written;
}

/**
 * Makes the four skills available to every agent harness: copies them into `.agents/skills`, `.claude/skills` and `.github/skills` and
 * adds the delimited Loom section to AGENTS.md. The skills come from the jar (`weave guide --install-skill`), so they match this release.
 * Returns the folders and files written, relative to `folder`.
 */
export async function syncAgentFiles(folder: string, weave: (args: string[]) => Promise<string>): Promise<string[]> {
    let source = path.join(folder, '.claude', 'skills');
    let scratch: string | undefined;
    try {
        if (!hasAllSkills(source)) {
            scratch = fs.mkdtempSync(path.join(os.tmpdir(), 'loom-skills-'));
            await weave(['guide', '--install-skill', scratch]);
            source = path.join(scratch, '.claude', 'skills');
        }
        const roots = mirrorSkills(source, folder);
        writeAgentsSection(folder);
        return [...roots, 'AGENTS.md'];
    } finally {
        if (scratch) {
            fs.rmSync(scratch, { recursive: true, force: true });
        }
    }
}

/** What the hand-off needs from the editor; the extension gives the real ones, tests give fakes. */
export interface HandoffHost {
    copyToClipboard(text: string): Promise<void>;
    /** Opens the chat input with the text filled in and not sent; false when this editor cannot. */
    openChat(query: string): Promise<boolean>;
    /** Shows a notification and resolves with the button pressed. */
    notify(message: string, buttons: string[]): Promise<string | undefined>;
}

export const BUTTON_OPEN_CHAT = 'Open Agent Chat';
export const BUTTON_COPY_PROMPT = 'Copy Starter Prompt';

/** Copies the starter prompt, opens the chat input with it, and says what was installed, so the person never meets a blank chat box. */
export async function handOffToAgent(host: HandoffHost, installed: string[]): Promise<void> {
    await host.copyToClipboard(STARTER_PROMPT);
    const opened = await host.openChat(STARTER_PROMPT);
    const message =
        `Loom skills installed in ${installed.join(', ')}. ` +
        (opened ? 'The chat is open with a starter prompt: add what you want to build.' : 'A starter prompt is on your clipboard: paste it into your agent chat.');
    const choice = await host.notify(message, [BUTTON_OPEN_CHAT, BUTTON_COPY_PROMPT]);
    if (choice === BUTTON_OPEN_CHAT) {
        await host.openChat(STARTER_PROMPT);
    } else if (choice === BUTTON_COPY_PROMPT) {
        await host.copyToClipboard(STARTER_PROMPT);
    }
}
