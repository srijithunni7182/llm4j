/* Pure: the command lines, the progress lines and the questions of Generate Workflow... and Change Workflow.... The VS Code glue
   (vscodeGenerate.ts) asks the person, starts the process and shows what these say. The model is only ever called by `weave generate`,
   a process of its own, never by the engine that checks and reviews; nothing here sends anything. */

export interface GenerateSettings {
    /** The model that writes (`loom.generate.model`); empty means the one `weave` finds in LOOM_GENERATE_MODEL. */
    model: string;
    /** The most a generation may spend, in dollars (0 = leave the default of the command). */
    maxCost: number;
    /** The most tokens it may use (0 = leave the default). */
    maxTokens: number;
    /** A price table, so that the dollars can be counted (empty = tokens only). */
    prices: string;
}

export const DEFAULT_SETTINGS: GenerateSettings = { model: '', maxCost: 0, maxTokens: 0, prices: '' };

function common(s: GenerateSettings): string[] {
    const out: string[] = [];
    if (s.model.trim()) {
        out.push('--model', s.model.trim());
    }
    if (s.maxCost > 0) {
        out.push('--max-cost', String(s.maxCost));
    }
    if (s.maxTokens > 0) {
        out.push('--max-tokens', String(Math.floor(s.maxTokens)));
    }
    if (s.prices.trim()) {
        out.push('--prices', s.prices.trim());
    }
    out.push('--format', 'json');
    return out;
}

/** The plan alone, into a folder, with the proposed answers (the person edits the plan, not an interview). */
export function planArgs(description: string, planDir: string, s: GenerateSettings): string[] {
    return ['generate', description, '--plan-only', '--no-ask', '--out', planDir, ...common(s)];
}

/** The project from an edited plan, into a new folder. */
export function buildArgs(planFile: string, target: string, s: GenerateSettings): string[] {
    return ['generate', '--from-plan', planFile, '--out', target, '--yes', ...common(s)];
}

/** A change, proposed and checked but not applied; the patch is kept in a file. */
export function editArgs(script: string, request: string, patchFile: string, s: GenerateSettings): string[] {
    return ['generate', '--edit', script, request, '--patch-out', patchFile, ...common(s)];
}

/** Applies a saved patch (checked again, no model). */
export function applyArgs(script: string, patchFile: string, acceptWorse: boolean): string[] {
    return ['generate', '--edit', script, '--apply-patch', patchFile, '--apply', ...(acceptWorse ? ['--accept-worse'] : []), '--format', 'json'];
}

/** The request with the text the person selected, which the model is told is the part to change. */
export function requestWith(request: string, selection: string | undefined): string {
    const text = selection?.trim();
    return text ? `${request.trim()}\n\nThe part of the script this is about:\n${text}` : request.trim();
}

export interface GenerateEvent {
    event: string;
    [key: string]: unknown;
}

/** Splits what the process printed (in whatever pieces) into events; a line that is not an event is kept as text. */
export class EventReader {
    private pending = '';
    readonly text: string[] = [];

    push(chunk: string): GenerateEvent[] {
        this.pending += chunk;
        const out: GenerateEvent[] = [];
        let at: number;
        while ((at = this.pending.indexOf('\n')) >= 0) {
            const line = this.pending.slice(0, at).trim();
            this.pending = this.pending.slice(at + 1);
            if (!line) {
                continue;
            }
            try {
                const parsed = JSON.parse(line) as unknown;
                if (parsed && typeof parsed === 'object' && typeof (parsed as GenerateEvent).event === 'string') {
                    out.push(parsed as GenerateEvent);
                    continue;
                }
            } catch {
                // not an event
            }
            this.text.push(line);
        }
        return out;
    }
}

/** What to say while it works, or undefined for an event that needs no words. */
export function progressText(e: GenerateEvent): string | undefined {
    switch (e.event) {
        case 'stage':
            return { clarify: 'Thinking about what to ask…', plan: 'Planning…', write: 'Writing the files…', edit: 'Working out the change…' }[String(e.stage)];
        case 'attempt':
            return Number(e.n) > 1 ? `Repairing (attempt ${e.n} of ${e.of})…` : undefined;
        case 'gate':
            return e.passed ? undefined : `${e.gate} ${e.name} did not pass (${e.problems})`;
        default:
            return undefined;
    }
}

export interface Result {
    exit: number;
    text: string;
}

export function resultOf(events: GenerateEvent[]): Result | undefined {
    const last = [...events].reverse().find((e) => e.event === 'result');
    return last ? { exit: Number(last.exit), text: String(last.text ?? '') } : undefined;
}

/** What the exit code of `weave generate` means to the person. */
export function exitMeaning(code: number): string {
    switch (code) {
        case 0:
            return 'done';
        case 1:
            return 'the command was used wrongly';
        case 2:
            return 'it could not run (no model or key, an unreadable file, or a question it needs answered)';
        case 3:
            return 'a gate did not pass';
        case 4:
            return 'it stopped at the spend cap';
        default:
            return `it ended with ${code}`;
    }
}

/** A folder name for a new project from the workflow's name. */
export function folderFor(workflowName: string): string {
    return workflowName.replace(/([a-z0-9])([A-Z])/g, '$1-$2').toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '') || 'workflow';
}

/** Whether a change is held back as worse, from the text the command printed. */
export function isWorse(text: string): boolean {
    return /makes the project WORSE/.test(text);
}

/** Whether the command asked a question instead of guessing. */
export function questionIn(text: string): string | undefined {
    const m = /nothing was guessed\. The question:\n\s+(.+)/.exec(text);
    return m ? m[1].trim() : undefined;
}

/** The files a saved patch writes, from the JSON the command saved. */
export function patchedFiles(patchJson: string): Record<string, string> | undefined {
    try {
        const parsed = JSON.parse(patchJson) as { files?: Record<string, unknown> };
        if (!parsed.files || typeof parsed.files !== 'object') {
            return undefined;
        }
        const out: Record<string, string> = {};
        for (const [k, v] of Object.entries(parsed.files)) {
            if (typeof v === 'string') {
                out[k] = v;
            }
        }
        return out;
    } catch {
        return undefined;
    }
}
