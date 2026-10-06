import * as path from 'path';

/** A `prompt: "id"` (or `system_template: "id"`) in a script: which prompt, and where it is written (1-based line). */
export interface PromptReference {
    id: string;
    version?: string;
    line: number;
}

const REFERENCE = /^\s*(?:prompt|system_template)\s*:\s*"([a-z0-9][a-z0-9_-]*)(?:@(v[0-9]+))?"/;
const INLINE_REFERENCE = /\b(?:prompt|system_template)\s*:\s*"([a-z0-9][a-z0-9_-]*)(?:@(v[0-9]+))?"/g;
const PROMPTS_DIR = /^\s*prompts\s*:\s*"([^"]+)"/m;

/** Every prompt the script names, in order. Text after `//` is a comment and is ignored. */
export function promptReferences(text: string): PromptReference[] {
    const out: PromptReference[] = [];
    text.split('\n').forEach((raw, index) => {
        const line = raw.replace(/\/\/.*$/, '');
        const whole = REFERENCE.exec(line);
        if (whole) {
            out.push({ id: whole[1], version: whole[2], line: index + 1 });
            return;
        }
        for (const m of line.matchAll(INLINE_REFERENCE)) {
            out.push({ id: m[1], version: m[2], line: index + 1 });
        }
    });
    return out;
}

/** The folder a script names with `prompts: "./dir"`, resolved against the script's folder; `prompts/` beside the script when it names none. */
export function promptsFolder(scriptFile: string, text: string): string {
    const named = PROMPTS_DIR.exec(text.replace(/^\s*\/\/.*$/gm, ''));
    const base = path.dirname(scriptFile);
    return named ? path.resolve(base, named[1]) : path.join(base, 'prompts');
}

/** Where a new prompt file for `ref` goes: `<folder>/<id>.md`, or `<folder>/<id>/<version>.md` when a version is asked for. */
export function promptFilePath(folder: string, ref: PromptReference): string {
    return ref.version ? path.join(folder, ref.id, `${ref.version}.md`) : path.join(folder, `${ref.id}.md`);
}

/** The words a new prompt file starts with. */
export function starterText(ref: PromptReference): string {
    return [
        '---',
        `description: What the ${ref.id} agent is for, in a line`,
        '---',
        `You are ${ref.id}. Say here who the agent is, what it does, and what it must never do.`,
        '',
    ].join('\n');
}
