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

/** Method name → params and answer, so `request` is typed at the call. */
export interface Methods {
    'loom/check': { params: CheckParams; answer: CommandAnswer };
    'loom/graph': { params: GraphParams; answer: CommandAnswer };
    'loom/review': { params: ReviewParams; answer: CommandAnswer };
    'loom/explainRule': { params: { rule: string }; answer: ExplainAnswer };
}

export type MethodName = keyof Methods;
