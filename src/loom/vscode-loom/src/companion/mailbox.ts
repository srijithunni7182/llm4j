import * as fs from 'fs';
import * as path from 'path';
import { guideFolder } from './model';

/**
 * The editor's end of the Guide's channel. `weave guide-mcp` writes a request file to
 * `.loom/guide/requests/`; the editor reads it, does only the actions listed here, and writes the
 * answer to `.loom/guide/answers/`. No network: the folder belongs to the developer.
 */

/** The only things the Guide may ask the editor to do: show something, or show a draft the person sends. */
export const ACTIONS = [
    'show_graph',
    'show_review',
    'show_eval_report',
    'show_file',
    'show_run',
    'start_observed_run',
    'open_secret_form',
    'open_walkthrough',
    'draft_builder_instruction',
] as const;
export type Action = (typeof ACTIONS)[number];

export type Args = Record<string, unknown>;
export type EditorActions = Record<Action, (args: Args) => Promise<string>>;

const ID = /^[0-9a-f]{24}$/;
const KEY_NAME = /^[A-Z][A-Z0-9_]{1,63}$/;
const HOST = /^(?=.{1,253}$)[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)+$/;
/** A request older than this is stale (the Guide gave up on it) and is removed unanswered. */
export const STALE_MS = 10 * 60 * 1000;

/** The file a path names when it is inside the project (symbolic links resolved), else undefined. */
export function insideProject(root: string, relative: string): string | undefined {
    if (typeof relative !== 'string' || relative === '' || path.isAbsolute(relative)) return undefined;
    const target = path.resolve(root, relative);
    const rel = path.relative(path.resolve(root), target);
    if (rel.startsWith('..') || path.isAbsolute(rel)) return undefined;
    try {
        const realRoot = fs.realpathSync(root);
        const real = fs.realpathSync(target);
        if (real !== realRoot && !real.startsWith(realRoot + path.sep)) return undefined;
    } catch {
        return undefined;
    }
    return target;
}

/** Checks a request's arguments before any action runs; a reason when they are refused. */
export function refusal(root: string, action: string, args: Args): string | undefined {
    if (!(ACTIONS as readonly string[]).includes(action)) return `The editor does not do "${action}".`;
    if (action === 'show_file' && !insideProject(root, String(args.path ?? ''))) return 'Only files inside the project can be shown.';
    if (action === 'open_secret_form' && (!KEY_NAME.test(String(args.name ?? '')) || !HOST.test(String(args.host ?? '')))) {
        return 'A key needs a plain name (OPENAI_API_KEY) and a plain host (api.openai.com).';
    }
    if (action === 'draft_builder_instruction' && (typeof args.text !== 'string' || args.text.trim() === '' || args.text.length > 4000)) {
        return 'An instruction is non-empty text of at most 4000 characters.';
    }
    return undefined;
}

/** Handles one request file: checks it, runs the action, writes the answer, removes the request. */
export async function handleRequest(root: string, file: string, actions: EditorActions, now: number): Promise<void> {
    const folder = guideFolder(root);
    let request: { id?: unknown; action?: unknown; args?: unknown; at?: unknown };
    try {
        request = JSON.parse(fs.readFileSync(file, 'utf8'));
    } catch {
        fs.rmSync(file, { force: true });
        return;
    }
    fs.rmSync(file, { force: true });
    const id = String(request.id ?? '');
    if (!ID.test(id)) return;
    const at = Date.parse(String(request.at ?? ''));
    if (!Number.isNaN(at) && now - at > STALE_MS) return;
    const action = String(request.action ?? '');
    const args = (request.args && typeof request.args === 'object' ? request.args : {}) as Args;
    const refused = refusal(root, action, args);
    let result: string;
    try {
        result = refused ?? (await actions[action as Action](args));
    } catch (e) {
        result = `The editor could not do it: ${(e as Error).message}`;
    }
    const answers = path.join(folder, 'answers');
    fs.mkdirSync(answers, { recursive: true });
    const tmp = path.join(answers, `${id}.tmp`);
    fs.writeFileSync(tmp, JSON.stringify({ result }));
    fs.renameSync(tmp, path.join(answers, `${id}.json`));
}

/** The request files waiting now, oldest name first. */
export function pending(root: string): string[] {
    const dir = path.join(guideFolder(root), 'requests');
    try {
        return fs.readdirSync(dir).filter((f) => f.endsWith('.json')).sort().map((f) => path.join(dir, f));
    } catch {
        return [];
    }
}
