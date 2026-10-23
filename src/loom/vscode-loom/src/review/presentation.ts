import { atLeast, countsOf, countsText, FindingData, ReviewData, Severity } from './model';

/* Pure builders of what the editor shows for a review: squiggles, the status bar, the CodeLens. The VS Code glue only hands these
 * to the API, so what is shown is tested without an editor. */

export const DIAGNOSTIC_SOURCE = 'loom-review';

export type DiagnosticLevel = 'warning' | 'information' | 'hint';

export interface DiagnosticSpec {
    /** 0-based, as VS Code wants. */
    line: number;
    endLine: number;
    column: number;
    endColumn: number;
    level: DiagnosticLevel;
    /** The rule id: it opens the rule's explanation. */
    code: string;
    message: string;
    source: typeof DIAGNOSTIC_SOURCE;
    severity: Severity;
    fingerprint: string;
}

/** Findings are advice, not errors: the worst are warnings, the rest information and hints. Errors stay `weave check`'s. */
const LEVEL: Record<Severity, DiagnosticLevel> = { high: 'warning', medium: 'information', low: 'hint', info: 'hint' };

/** Squiggles for the findings in `file` at or above `minimum`. A finding with no line (a whole-script finding) sits on line 1. */
export function diagnosticsFor(review: ReviewData | undefined, file: string, minimum: Severity, lineCount: number): DiagnosticSpec[] {
    if (!review) {
        return [];
    }
    const last = Math.max(0, lineCount - 1);
    const out: DiagnosticSpec[] = [];
    for (const f of review.findings) {
        if (!atLeast(f.severity, minimum) || !sameFile(f, file)) {
            continue;
        }
        const line = Math.min(last, Math.max(0, f.location.line - 1));
        const endLine = Math.min(last, Math.max(line, (f.location.endLine || f.location.line) - 1));
        out.push({
            line,
            endLine,
            column: f.location.line > 0 ? Math.max(0, f.location.column - 1) : 0,
            endColumn: f.location.line > 0 && f.location.endColumn > 0 ? f.location.endColumn - 1 : Number.MAX_SAFE_INTEGER,
            level: LEVEL[f.severity],
            code: f.rule,
            message: f.why ? `${f.title}\n${f.why}` : f.title,
            source: DIAGNOSTIC_SOURCE,
            severity: f.severity,
            fingerprint: f.fingerprint,
        });
    }
    return out;
}

/** The engine names a finding's file by its base name; the open file matches when the base names are the same. */
function sameFile(f: FindingData, file: string): boolean {
    const a = base(f.location.file);
    return a === '' || a === base(file);
}

function base(p: string): string {
    const parts = p.split(/[\\/]/);
    return parts[parts.length - 1];
}

export interface StatusSpec {
    text: string;
    tooltip: string;
    /** `warning` colours the item when findings at or above `failOn` are open; otherwise neutral. */
    tone: 'ok' | 'neutral' | 'warning';
}

/**
 * The standing indicator: `Loom ⚠ 2 high · $0.004/run`, or `Loom ✓` when nothing at or above the level is open. Never a gate: it is
 * only a count, and clicking it opens the panel. A count of findings the person dismissed is always shown.
 */
export function statusFor(review: ReviewData | undefined, options: { failOn: Severity | 'none'; dismissed: number; stale: boolean }): StatusSpec {
    if (!review) {
        return { text: 'Loom', tooltip: 'Loom review has not run for this script yet. Click to open it.', tone: 'neutral' };
    }
    const counts = countsOf(review);
    const open = counts.high + counts.medium + counts.low + counts.info;
    const interesting = (['high', 'medium'] as Severity[]).filter((s) => counts[s] > 0).map((s) => `${counts[s]} ${s}`).join(' · ');
    const cost = typicalCost(review);
    const lenses = lensCounts(review.findings);
    const dismissed = options.dismissed > 0 ? ` · ${options.dismissed} dismissed` : '';
    const tooltip = [
        open === 0 ? 'No open findings.' : countsText(counts),
        lenses.length ? lenses.map(([l, n]) => `${l} ${n}`).join(', ') : '',
        cost ? `Typical cost ${cost} per run.` : '',
        options.stale ? 'The script changed after this review.' : '',
        options.dismissed > 0 ? `${options.dismissed} dismissed on this machine.` : '',
        'Open findings never block Run.',
    ].filter(Boolean).join('\n');
    const tone: StatusSpec['tone'] = options.failOn !== 'none' && (Object.keys(counts) as Severity[]).some((s) => counts[s] > 0 && atLeast(s, options.failOn as Severity)) ? 'warning' : open === 0 ? 'ok' : 'neutral';
    if (open === 0) {
        return { text: `Loom ✓${dismissed}`, tooltip, tone: 'ok' };
    }
    const head = interesting || countsText(counts);
    return { text: `Loom ⚠ ${head}${cost ? ` · ${cost}/run` : ''}${dismissed}`, tooltip, tone };
}

function lensCounts(findings: FindingData[]): Array<[string, number]> {
    const m = new Map<string, number>();
    findings.forEach((f) => m.set(f.lens, (m.get(f.lens) ?? 0) + 1));
    return [...m.entries()];
}

/** Money when the models are priced, otherwise tokens; undefined when the review has no cost. */
export function typicalCost(review: ReviewData, workflow?: string): string | undefined {
    const wf = review.cost?.workflows.find((w) => w.name === workflow) ?? review.cost?.workflows[0];
    return costBlockText(wf?.typical);
}

export function worstCost(review: ReviewData, workflow?: string): string | undefined {
    const wf = review.cost?.workflows.find((w) => w.name === workflow) ?? review.cost?.workflows[0];
    return costBlockText(wf?.worst);
}

function costBlockText(c: { money?: number | null; tokensIn?: number; tokensOut?: number } | undefined): string | undefined {
    if (!c) {
        return undefined;
    }
    if (c.money != null) {
        return money(c.money);
    }
    const tokens = (c.tokensIn ?? 0) + (c.tokensOut ?? 0);
    return tokens < 1000 ? `${tokens} tok` : tokens < 100000 ? `${Math.round(tokens / 100) / 10}k tok` : `${Math.round(tokens / 1000)}k tok`;
}

function money(m: number): string {
    if (m === 0) {
        return '$0';
    }
    if (m < 0.01) {
        return '$' + String(Math.round(m * 10000) / 10000);
    }
    if (m < 1) {
        return '$' + String(Math.round(m * 1000) / 1000);
    }
    return '$' + m.toFixed(2);
}

export interface LensSpec {
    /** 0-based line of the `workflow` keyword. */
    line: number;
    title: string;
    command: string;
    args: unknown[];
    tooltip: string;
}

export interface GraphWorkflow {
    name: string;
    file: string;
    line: number;
}

/**
 * The lenses above each `workflow X(...)`: its findings and cost (opens the panel), then Graph and Run. Only workflows of this file
 * get them. The findings counted are those on lines the workflow covers (up to the next workflow); whole-script findings are not any
 * one workflow's.
 */
export function codeLensFor(review: ReviewData | undefined, workflows: GraphWorkflow[], file: string): LensSpec[] {
    const mine = workflows.filter((w) => base(w.file) === base(file)).sort((a, b) => a.line - b.line);
    const out: LensSpec[] = [];
    mine.forEach((w, i) => {
        const end = mine[i + 1] ? mine[i + 1].line : Number.MAX_SAFE_INTEGER;
        const line = Math.max(0, w.line - 1);
        const findings = (review?.findings ?? []).filter((f) => f.location.line >= w.line && f.location.line < end);
        const counts = countsOf({ findings } as ReviewData);
        const typical = review ? typicalCost(review, w.name) : undefined;
        const worst = review ? worstCost(review, w.name) : undefined;
        const head = review ? (findings.length ? `Review: ${countsText(counts)}` : 'Review: no findings') : 'Review';
        const costPart = typical ? `  ·  ~${typical} typical${worst ? ` / ${worst} worst` : ''}` : '';
        out.push({ line, title: head + costPart, command: 'loom.review', args: [file, w.name], tooltip: 'Open the Loom review of this script' });
        out.push({ line, title: 'Graph', command: 'loom.showGraph', args: [], tooltip: 'Show the workflow graph' });
        out.push({ line, title: 'Run', command: 'loom.runWorkflow', args: [], tooltip: 'Run this script' });
    });
    return out;
}
