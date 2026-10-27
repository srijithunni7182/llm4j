/** The parts of `weave review --format json` the extension reads. Everything else in the JSON is passed to the panel untouched. */

export type Severity = 'high' | 'medium' | 'low' | 'info';

export interface FindingData {
    rule: string;
    fingerprint: string;
    lens: string;
    severity: Severity;
    title: string;
    why: string;
    fixHint: string;
    location: { file: string; line: number; column: number; endLine: number; endColumn: number; node: string | null };
    fix?: { safety: 'safe' | 'review'; label: string; edits: Array<{ file: string; startLine: number; startColumn: number; endLine: number; endColumn: number; newText: string }> };
}

export interface ReviewData {
    version: number;
    file: string;
    summary: { total: number; high: number; medium: number; low: number; info: number; accepted?: number; baselined?: number; lenses?: { ran: string[]; off: string[] } };
    findings: FindingData[];
    cost?: {
        workflows: Array<{ name: string; typical?: CostBlock; worst?: CostBlock }>;
    };
    notes?: Array<{ kind: string; rule?: string; message: string }>;
}

export interface CostBlock {
    calls?: number;
    tokensIn?: number;
    tokensOut?: number;
    money?: number | null;
    unpriced?: string[];
}

export const SEVERITIES: Severity[] = ['high', 'medium', 'low', 'info'];

const SEVERITY_RANK: Record<Severity, number> = { high: 0, medium: 1, low: 2, info: 3 };

/** Whether a finding of `severity` is at least `minimum`. */
export function atLeast(severity: Severity, minimum: Severity): boolean {
    return SEVERITY_RANK[severity] <= SEVERITY_RANK[minimum];
}

function isObject(v: unknown): v is Record<string, unknown> {
    return typeof v === 'object' && v !== null && !Array.isArray(v);
}

/** Accepts the engine's review JSON when it has the shape the extension depends on; anything else is not a review. */
export function parseReview(raw: unknown): ReviewData | undefined {
    if (!isObject(raw) || !Array.isArray(raw.findings) || !isObject(raw.summary)) {
        return undefined;
    }
    for (const f of raw.findings) {
        if (!isObject(f) || typeof f.rule !== 'string' || typeof f.severity !== 'string' || !(f.severity in SEVERITY_RANK) || typeof f.title !== 'string' || !isObject(f.location)) {
            return undefined;
        }
    }
    return raw as unknown as ReviewData;
}

/** "3 high · 2 medium" style counts, only the non-zero ones. */
export function countsText(counts: Record<Severity, number>): string {
    const parts = SEVERITIES.filter((s) => counts[s] > 0).map((s) => `${counts[s]} ${s}`);
    return parts.length ? parts.join(' · ') : 'no open findings';
}

export function countsOf(review: ReviewData | undefined): Record<Severity, number> {
    const c: Record<Severity, number> = { high: 0, medium: 0, low: 0, info: 0 };
    for (const f of review?.findings ?? []) {
        c[f.severity] += 1;
    }
    return c;
}
