import { EngineClient } from '../engine/client';
import { EngineRpcError } from '../engine/errors';
import { singleEdit, TextEdit } from './edits';
import { FindingData, ReviewData } from './model';
import { Memory } from './runNotice';

/* What a person can do with a finding, and doing it. Pure apart from two things it is given: the engine (which computes the text of a
 * fix, an accept or a baseline and writes nothing) and an EditHost (which shows a change and applies it to the editor as one undo
 * step). So the whole flow, including the refusals, is tested without an editor. */

export type FixKind = 'safe' | 'review' | null;

export interface FindingActions {
    /** `safe`: one click. `review`: shows the change first. null: no automatic fix. */
    fix: FixKind;
    /** The finding offers more than one way to resolve it, each possibly with questions. */
    options: boolean;
    accept: { allowed: boolean; reason?: string };
    dismiss: { allowed: boolean; reason?: string };
    explain: true;
    /** Said in place of a Fix button when there is no fix: never a dead button without a reason (R35). */
    note?: string;
}

/** The lens and severity of findings the engine would not let a person wave away: validity errors are the script not working. */
export function isValidityError(f: FindingData): boolean {
    return f.lens === 'validity' && f.severity === 'high';
}

export function actionsFor(f: FindingData): FindingActions {
    const fix: FixKind = f.fix ? (f.fix.safety === 'safe' ? 'safe' : 'review') : (f as FindingWithOptions).options?.length ? 'review' : null;
    const trifecta = f.rule === 'LA01';
    return {
        fix,
        options: Array.isArray((f as FindingWithOptions).options) && (f as FindingWithOptions).options!.length > 0,
        accept: trifecta
            ? { allowed: false, reason: 'The lethal trifecta is accepted in the script itself, where audit and Fabric read it. Add an approval or split the agent instead.' }
            : isValidityError(f)
            ? { allowed: false, reason: 'A script that does not load or check cannot be accepted: fix it.' }
            : { allowed: true },
        dismiss: isValidityError(f) ? { allowed: false, reason: 'Errors in the script cannot be dismissed.' } : { allowed: true },
        explain: true,
        note: fix ? undefined : 'No automatic fix: see the hint.',
    };
}

interface FindingWithOptions extends FindingData {
    options?: Array<{ id: string; label: string; explain: string; safety: string; ask: Array<{ name: string; prompt: string; default?: string }> }>;
}

/** "Fix all safe (n of m)". */
export function safeCount(findings: FindingData[]): { safe: number; total: number } {
    return { safe: findings.filter((f) => actionsFor(f).fix === 'safe').length, total: findings.length };
}

// ---- dismissing (local; never in the repository)

const DISMISS_KEY = 'loom.review.dismissed';

/**
 * What a person has dismissed on this machine, per script. It lives in the editor's workspace storage and nowhere else: not in the
 * repository, not in CI, and it never changes what the engine reports. A dismissed finding is still counted and can be restored.
 */
export class DismissStore {
    constructor(private readonly memory: Memory) {}

    dismissed(file: string): Set<string> {
        const all = this.memory.get<Record<string, string[]>>(DISMISS_KEY) ?? {};
        return new Set(all[key(file)] ?? []);
    }

    count(file: string): number {
        return this.dismissed(file).size;
    }

    /** Dismisses these findings; the ones that cannot be dismissed are left alone and returned. */
    dismiss(file: string, findings: FindingData[]): { dismissed: string[]; refused: FindingData[] } {
        const current = this.dismissed(file);
        const refused = findings.filter((f) => !actionsFor(f).dismiss.allowed);
        const ok = findings.filter((f) => actionsFor(f).dismiss.allowed).map((f) => f.fingerprint);
        ok.forEach((id) => current.add(id));
        this.save(file, current);
        return { dismissed: ok, refused };
    }

    restore(file: string, ids: string[]): void {
        const current = this.dismissed(file);
        ids.forEach((id) => current.delete(id));
        this.save(file, current);
    }

    restoreAll(file: string): string[] {
        const ids = [...this.dismissed(file)];
        this.save(file, new Set());
        return ids;
    }

    /** Forgets dismissals of findings that are gone, so the store does not grow for ever. */
    prune(file: string, review: ReviewData): void {
        const live = new Set(review.findings.map((f) => f.fingerprint));
        const current = this.dismissed(file);
        const kept = new Set([...current].filter((id) => live.has(id)));
        if (kept.size !== current.size) {
            this.save(file, kept);
        }
    }

    private save(file: string, ids: Set<string>): void {
        const all = { ...(this.memory.get<Record<string, string[]>>(DISMISS_KEY) ?? {}) };
        if (ids.size === 0) {
            delete all[key(file)];
        } else {
            all[key(file)] = [...ids];
        }
        void this.memory.update(DISMISS_KEY, all);
    }
}

function key(file: string): string {
    return file.replace(/\\/g, '/');
}

/** The review without what was dismissed: the findings still open, and how many were put away. */
export function withoutDismissed(review: ReviewData, dismissed: Set<string>): { open: FindingData[]; dismissed: number } {
    const open = review.findings.filter((f) => !dismissed.has(f.fingerprint));
    return { open, dismissed: review.findings.length - open.length };
}

// ---- the actions

/** A file to change: its text now (what the engine reviewed) and after. A new file has `existed` false. */
export interface FileChange {
    file: string;
    existed: boolean;
    before: string;
    after: string;
}

/** What the editor does for the actions. The extension supplies VS Code's; tests supply a fake. */
export interface EditHost {
    /** The text of a file now: the open buffer if there is one, else the file; undefined when it does not exist. */
    text(file: string): Promise<string | undefined>;
    /**
     * Applies all changes as one undoable edit. Refuses (false) when any file no longer holds the `before` text, so a change prepared
     * on text that has moved on is never applied on top of newer typing.
     */
    apply(changes: FileChange[]): Promise<boolean>;
    /** Shows the change beside the current text and asks whether to apply it. */
    confirm(title: string, change: FileChange): Promise<boolean>;
    /** Undoes the last change to these files. */
    undo(files: string[]): Promise<void>;
}

export interface Outcome {
    ok: boolean;
    message: string;
    /** Takes the change back (the toast's Undo). */
    undo?: () => Promise<void>;
    /** Ids of the findings the action handled, so the panel can move them out of the list at once. */
    handled?: string[];
    /** The file changed under the person: the review must run again. */
    rerun?: boolean;
}

export interface AcceptRequest {
    ids: string[];
    scope: 'finding' | 'thing' | 'script' | 'project';
    reason: string;
    until?: string;
    on?: string;
    /** The number typed back to accept several serious findings at once. */
    confirm?: number;
}

export class TriageActions {
    constructor(
        private readonly engine: () => EngineClient | undefined,
        private readonly host: EditHost,
        private readonly dismissals: DismissStore,
    ) {}

    // ---- fixing

    /**
     * Applies every safe fix of `findings` (or of the whole script) as one change. The change is shown first, because a fix that
     * runs on a whole script deserves one look; fixes that need judgement are listed, never applied here.
     */
    async fixAllSafe(file: string, findings: FindingData[]): Promise<Outcome> {
        const safe = findings.filter((f) => actionsFor(f).fix === 'safe');
        if (safe.length === 0) {
            const review = findings.filter((f) => actionsFor(f).fix === 'review').length;
            return { ok: false, message: review ? `None of these has a safe fix. ${review} need your judgement: use Review fix… on each.` : 'None of these has an automatic fix.' };
        }
        return this.fix(file, safe.map((f) => f.fingerprint), 'Fix all safe', true, findings.length - safe.length);
    }

    /** One finding's own fix. A safe one is applied at once; one that needs judgement is shown first. */
    async fixOne(file: string, finding: FindingData): Promise<Outcome> {
        const kind = actionsFor(finding).fix;
        if (!kind) {
            return { ok: false, message: 'No automatic fix for this finding: see the hint.' };
        }
        return this.fix(file, [finding.fingerprint], finding.fix?.label ?? 'Fix', kind === 'review', 0);
    }

    /** The option a person chose for a finding, with their answers; always shown before it is applied. */
    async fixOption(file: string, finding: FindingData, option: string, answers: Record<string, string>): Promise<Outcome> {
        const engine = this.engine();
        if (!engine) {
            return unavailable();
        }
        try {
            const answer = await engine.request('loom/applyFix', { file, fingerprints: [finding.fingerprint], option, answers });
            if (!answer.changed) {
                return { ok: false, message: 'That option changes nothing in the script.' };
            }
            return this.confirmAndApply(`${finding.rule}: ${option}`, { file, existed: true, before: answer.original, after: answer.text }, [finding.fingerprint], `Applied ${option} to ${finding.rule}`, true);
        } catch (e) {
            return failure(e);
        }
    }

    private async fix(file: string, ids: string[], label: string, preview: boolean, left: number): Promise<Outcome> {
        const engine = this.engine();
        if (!engine) {
            return unavailable();
        }
        try {
            const answer = await engine.request('loom/applyFix', { file, fingerprints: ids });
            if (!answer.changed) {
                const why = answer.withheld.length ? ` Withheld: ${answer.withheld.join('; ')}` : '';
                return { ok: false, message: `That fix no longer applies; the script was reviewed again.${why}`, rerun: true };
            }
            const applied = answer.applied.length;
            const message = `Fixed ${applied}${left > 0 ? ` (${left} need${left === 1 ? 's' : ''} review)` : ''}${answer.withheld.length ? `, ${answer.withheld.length} withheld` : ''}`;
            return this.confirmAndApply(label, { file, existed: true, before: answer.original, after: answer.text }, ids, message, preview);
        } catch (e) {
            return failure(e);
        }
    }

    private async confirmAndApply(title: string, change: FileChange, ids: string[], message: string, preview: boolean): Promise<Outcome> {
        if (preview && !(await this.host.confirm(title, change))) {
            return { ok: false, message: 'Not applied.' };
        }
        if (!(await this.host.apply([change]))) {
            return { ok: false, message: 'The script changed while this was prepared, so nothing was applied. Reviewing again.', rerun: true };
        }
        return { ok: true, message, handled: ids, undo: () => this.host.undo([change.file]) };
    }

    // ---- accepting

    /**
     * Accepts findings with a reason: writes `review.properties` (with who, when and optionally until). The engine refuses what
     * must not be accepted this way (the trifecta, a missing reason, several serious findings without their number typed back).
     */
    async accept(file: string, request: AcceptRequest): Promise<Outcome> {
        const engine = this.engine();
        if (!engine) {
            return unavailable();
        }
        try {
            const answer = await engine.request('loom/accept', {
                file,
                fingerprints: request.ids,
                scope: request.scope,
                reason: request.reason,
                until: request.until,
                on: request.on,
                confirm: request.confirm,
            });
            if (answer.written === 0) {
                return { ok: false, message: 'These are already accepted.' };
            }
            const change: FileChange = { file: answer.file, existed: answer.existed, before: answer.original, after: answer.text };
            if (!(await this.host.apply([change]))) {
                return { ok: false, message: `${answer.file} changed while this was prepared, so nothing was written. Try again.` };
            }
            const where = request.scope === 'script' ? ' in this script' : request.scope === 'project' ? ' in the project' : request.scope === 'thing' ? ` on ${request.on ?? 'this agent'}` : '';
            return { ok: true, message: `Accepted ${answer.written}${where}`, handled: request.ids, undo: () => this.host.undo([change.file]), rerun: true };
        } catch (e) {
            return failure(e);
        }
    }

    // ---- the baseline

    /** Records today's open findings as known, after showing what is in and what stays open. */
    async baseline(file: string, includeSerious: boolean): Promise<Outcome> {
        const engine = this.engine();
        if (!engine) {
            return unavailable();
        }
        try {
            const answer = await engine.request('loom/baseline', { file, includeSerious });
            const change: FileChange = { file: answer.file, existed: answer.existed, before: (await this.host.text(answer.file)) ?? '', after: answer.text };
            const stays = answer.leftOut > 0 ? ` ${answer.leftOut} serious ${answer.leftOut === 1 ? 'finding stays' : 'findings stay'} open.` : '';
            if (!(await this.host.confirm(`Record ${answer.recorded} findings as known.${stays}`, change))) {
                return { ok: false, message: 'Not recorded.' };
            }
            if (!(await this.host.apply([change]))) {
                return { ok: false, message: `${answer.file} changed while this was prepared. Try again.` };
            }
            return { ok: true, message: `Recorded ${answer.recorded} as known.${stays}`, undo: () => this.host.undo([change.file]), rerun: true };
        } catch (e) {
            return failure(e);
        }
    }

    // ---- dismissing

    dismiss(file: string, findings: FindingData[]): Outcome {
        const { dismissed, refused } = this.dismissals.dismiss(file, findings);
        if (dismissed.length === 0) {
            return { ok: false, message: refused.length ? actionsFor(refused[0]).dismiss.reason ?? 'These cannot be dismissed.' : 'Nothing to dismiss.' };
        }
        const note = refused.length ? ` (${refused.length} error${refused.length === 1 ? '' : 's'} cannot be dismissed)` : '';
        return { ok: true, message: `Dismissed ${dismissed.length}${note}`, handled: dismissed, undo: async () => this.dismissals.restore(file, dismissed) };
    }

    restore(file: string, ids: string[]): Outcome {
        this.dismissals.restore(file, ids);
        return { ok: true, message: `Restored ${ids.length}`, handled: ids };
    }

    restoreAll(file: string): Outcome {
        const ids = this.dismissals.restoreAll(file);
        return { ok: true, message: ids.length ? `Restored ${ids.length}` : 'Nothing was dismissed', handled: ids };
    }
}

function unavailable(): Outcome {
    return { ok: false, message: 'The Loom engine is not running, so this cannot be prepared. Try "Loom: Restart Engine".' };
}

function failure(e: unknown): Outcome {
    if (e instanceof EngineRpcError) {
        return { ok: false, message: e.message };
    }
    return { ok: false, message: e instanceof Error ? e.message : String(e) };
}

/** The edit that makes a change, for an editor that applies edits; undefined when nothing changes. */
export function editOf(change: FileChange): TextEdit | undefined {
    return singleEdit(change.before, change.after);
}

// ---- what the panel is sent, and the lightbulb

/**
 * The review as the panel shows it: findings still open (not dismissed), each with the actions that exist for it, and the dismissed
 * ones apart so the Handled section can list them. The engine's JSON is otherwise passed through untouched.
 */
export function prepareForPanel(review: ReviewData, dismissed: Set<string>): { review: ReviewData; dismissed: FindingData[] } {
    const open = review.findings.filter((f) => !dismissed.has(f.fingerprint)).map((f) => ({ ...f, actions: actionsFor(f) }));
    const away = review.findings.filter((f) => dismissed.has(f.fingerprint));
    return { review: { ...review, findings: open }, dismissed: away };
}

export interface QuickFixSpec {
    title: string;
    command: string;
    args: unknown[];
    /** `quickfix` for the lightbulb's main group; `preferred` for the one a click on Ctrl+. would pick. */
    preferred: boolean;
}

/**
 * The lightbulb of a finding: its fix (a safe one first, labelled as such; one to review says "Review:"), Explain, and Suppress with a
 * reason. Nothing is offered that cannot be done: no fix, no Fix; the trifecta has no Suppress.
 */
export function quickFixesFor(file: string, f: FindingData): QuickFixSpec[] {
    const a = actionsFor(f);
    const out: QuickFixSpec[] = [];
    if (a.fix === 'safe') {
        out.push({ title: `Fix: ${f.fix?.label ?? f.title}`, command: 'loom.review.fixFinding', args: [file, f.fingerprint], preferred: true });
    } else if (a.fix === 'review') {
        out.push({ title: `Review: ${f.fix?.label ?? f.title}…`, command: 'loom.review.fixFinding', args: [file, f.fingerprint], preferred: false });
    }
    out.push({ title: `Explain ${f.rule}`, command: 'loom.review.explain', args: [f.rule], preferred: false });
    if (a.accept.allowed) {
        out.push({ title: `Suppress ${f.rule} with a reason…`, command: 'loom.review.accept', args: [file, f.fingerprint], preferred: false });
    }
    return out;
}
