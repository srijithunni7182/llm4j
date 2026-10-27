/** The `weave serve` protocol this extension speaks (docs/serve/PROTOCOL.md). */
export const PROTOCOL_VERSION = 1;

/** The kinds of error the server gives (`error.data.kind`). */
export type ServerErrorKind =
    | 'parse-error'
    | 'invalid-request'
    | 'bad-frame'
    | 'unknown-method'
    | 'invalid-params'
    | 'outside-workspace'
    | 'timeout'
    | 'cancelled'
    | 'engine-failure'
    | 'internal';

export interface InitializeResult {
    protocol: number;
    weave: string;
    methods: string[];
    roots: string[];
}

/** Every file-based request takes these; `text` is an unsaved buffer, `version` comes back as `requestVersion`. */
export interface FileParams {
    file: string;
    text?: string;
    /** The other open files with unsaved changes (file to text), so imports are read as the editor has them. */
    buffers?: Record<string, string>;
    version?: number;
}

export interface CheckParams extends FileParams {}

export interface GraphParams extends FileParams {
    workflow?: string;
}

export interface ReviewParams extends FileParams {
    tier?: 'instant' | 'full';
    lenses?: string;
    minSeverity?: string;
    prices?: string;
    assume?: string[];
}

/** What `loom/check`, `loom/graph` and `loom/review` answer; `result` is the command's own JSON. */
export interface CommandAnswer {
    file: string;
    status: number;
    result: unknown;
    stderr?: string;
    cached: boolean;
    requestVersion?: number;
}

export interface ExplainAnswer {
    rule: string;
    text: string;
}

export interface ApplyFixParams extends FileParams {
    fingerprints?: string[];
    safe?: boolean;
    option?: string;
    answers?: Record<string, string>;
}

export interface ApplyFixAnswer {
    file: string;
    original: string;
    text: string;
    changed: boolean;
    applied: Array<{ rule?: string; line?: number; label?: string; pass?: number; option?: string; fingerprint?: string }>;
    withheld: string[];
}

export interface AcceptParams extends FileParams {
    fingerprints: string[];
    reason: string;
    scope?: 'finding' | 'thing' | 'script' | 'project';
    on?: string;
    until?: string;
    by?: string;
    confirm?: number;
}

/** A file the editor should write: the whole new text, and whether the file exists now. */
export interface FileText {
    file: string;
    existed: boolean;
    text: string;
}

export interface AcceptAnswer extends FileText {
    original: string;
    written: number;
    lines: string[];
}

export interface BaselineParams extends FileParams {
    includeSerious?: boolean;
    note?: string;
}

export interface BaselineAnswer extends FileText {
    recorded: number;
    leftOut: number;
}

/** Method name → params and answer, so `request` is typed at the call. */
export interface Methods {
    'loom/check': { params: CheckParams; answer: CommandAnswer };
    'loom/graph': { params: GraphParams; answer: CommandAnswer };
    'loom/symbols': { params: FileParams; answer: CommandAnswer };
    'loom/review': { params: ReviewParams; answer: CommandAnswer };
    'loom/explainRule': { params: { rule: string }; answer: ExplainAnswer };
    'loom/applyFix': { params: ApplyFixParams; answer: ApplyFixAnswer };
    'loom/accept': { params: AcceptParams; answer: AcceptAnswer };
    'loom/baseline': { params: BaselineParams; answer: BaselineAnswer };
}

export type MethodName = keyof Methods;
