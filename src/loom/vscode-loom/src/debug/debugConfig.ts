/* Pure: the launch configuration of a Loom debug session, and the questions to settle before one starts. The VS Code glue
   (vscodeDebug.ts) only hands these the active file and shows what they say. */

export const DEBUG_TYPE = 'loom';

export interface LoomLaunch {
    type: 'loom';
    request: 'launch';
    name: string;
    script: string;
    workflow?: string;
    input?: Record<string, string>;
    /** Only `simulate`: nothing is sent or written while debugging. */
    effects: 'simulate';
    /** `mock` (fixed, well-formed replies), `scripted` (the same here) or `live` (real models, under a cap). */
    models: 'mock' | 'scripted' | 'live';
    maxCost?: number | string;
    maxTokens?: number;
    prices?: string;
    /** Set by the editor after the person agreed to spend. */
    confirmed?: boolean;
    stopOnEntry?: boolean;
    mask?: string[];
}

export interface ActiveScript {
    file?: string;
}

export type Resolved = { config: LoomLaunch; problem?: undefined } | { config?: undefined; problem: string };

const baseName = (file: string): string => file.replace(/\\/g, '/').split('/').pop() ?? file;

/** The configuration that F5 creates for a script (and for one workflow of it, from the CodeLens). */
export function launchFor(file: string, workflow?: string): LoomLaunch {
    return {
        type: DEBUG_TYPE,
        request: 'launch',
        name: workflow ? `Debug ${workflow} (${baseName(file)})` : `Debug ${baseName(file)}`,
        script: file,
        ...(workflow ? { workflow } : {}),
        effects: 'simulate',
        models: 'mock',
        stopOnEntry: true,
    };
}

const positive = (n: unknown): boolean => (typeof n === 'number' || typeof n === 'string') && Number.isFinite(Number(n)) && Number(n) > 0;

/**
 * Turns whatever launch.json (or nothing) gave into a configuration that can start, or says what is wrong in words for the person.
 * An empty configuration means "F5 on this file".
 */
export function resolveLaunch(given: Record<string, unknown> | undefined, active: ActiveScript): Resolved {
    const raw = given ?? {};
    const empty = !raw.type && !raw.request && !raw.script;
    if (empty) {
        if (!active.file || !active.file.endsWith('.loom')) {
            return { problem: 'Open a .loom file to debug it, or give "script" in launch.json.' };
        }
        return { config: launchFor(active.file) };
    }
    if (raw.type !== DEBUG_TYPE || raw.request !== 'launch') {
        return { problem: 'A Loom debug configuration has "type": "loom" and "request": "launch".' };
    }
    const script = typeof raw.script === 'string' && raw.script.trim() ? raw.script : active.file;
    if (!script || !script.endsWith('.loom')) {
        return { problem: 'The "script" of a Loom debug configuration is a .loom file.' };
    }
    const effects = raw.effects ?? 'simulate';
    if (effects !== 'simulate') {
        return { problem: 'Only simulated effects can be debugged: nothing is sent or written. Use "effects": "simulate".' };
    }
    const models = raw.models ?? 'mock';
    if (models !== 'mock' && models !== 'scripted' && models !== 'live') {
        return { problem: '"models" is mock, scripted or live.' };
    }
    if (models === 'live') {
        if (!positive(raw.maxCost) && !positive(raw.maxTokens)) {
            return { problem: 'Live models spend money: give "maxCost" (with "prices") or "maxTokens" in the launch configuration.' };
        }
        if (positive(raw.maxCost) && !(typeof raw.prices === 'string' && raw.prices.trim())) {
            return { problem: '"maxCost" needs "prices", the price table that turns tokens into money.' };
        }
    }
    const input: Record<string, string> = {};
    if (raw.input && typeof raw.input === 'object') {
        for (const [k, v] of Object.entries(raw.input as Record<string, unknown>)) {
            input[k] = String(v);
        }
    }
    const workflow = typeof raw.workflow === 'string' && raw.workflow.trim() ? raw.workflow.trim() : undefined;
    const config: LoomLaunch = {
        type: DEBUG_TYPE,
        request: 'launch',
        name: typeof raw.name === 'string' && raw.name ? raw.name : launchFor(script, workflow).name,
        script,
        ...(workflow ? { workflow } : {}),
        ...(Object.keys(input).length ? { input } : {}),
        effects: 'simulate',
        models,
        ...(positive(raw.maxCost) ? { maxCost: raw.maxCost as number | string } : {}),
        ...(positive(raw.maxTokens) ? { maxTokens: Number(raw.maxTokens) } : {}),
        ...(typeof raw.prices === 'string' && raw.prices ? { prices: raw.prices } : {}),
        stopOnEntry: raw.stopOnEntry === false ? false : true,
        ...(Array.isArray(raw.mask) ? { mask: (raw.mask as unknown[]).map(String) } : {}),
    };
    return { config };
}

/** What to ask the person before real models are called, or undefined when the run costs nothing. */
export function liveWarning(config: LoomLaunch): string | undefined {
    if (config.models !== 'live') {
        return undefined;
    }
    const cap = [config.maxCost !== undefined ? `$${config.maxCost}` : undefined, config.maxTokens !== undefined ? `${config.maxTokens} tokens` : undefined].filter(Boolean).join(' or ');
    return `Debugging with live models calls the real models and may spend up to ${cap}. Tools that change things are only described, never run.`;
}

/** The command line of the debug adapter: arguments are array elements, never a shell string. */
export function adapterArgs(jarPath: string): string[] {
    return ['-jar', jarPath, 'dap'];
}
