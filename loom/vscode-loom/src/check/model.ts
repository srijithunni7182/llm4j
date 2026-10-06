/** One thing `weave check` found: a problem in the script (an error stops it running, a warning does not). Lines start at 1; 0 means "no line known". */
export interface CheckDiagnostic {
    severity: 'error' | 'warning';
    file: string;
    line: number;
    message: string;
}

/** What `weave check --format json` prints. */
export interface CheckResult {
    version: 1;
    file: string;
    ok: boolean;
    diagnostics: CheckDiagnostic[];
    /** Names a run needs that are not set in the environment yet; not problems. */
    notSetYet: string[];
}

export class CheckParseError extends Error {
    constructor(message: string) {
        super(message);
        this.name = 'CheckParseError';
    }
}

const SUPPORTED_VERSION = 1;
export const MAX_CHECK_BYTES = 2 * 1024 * 1024;

function fail(message: string): never {
    throw new CheckParseError(message);
}

function isObject(v: unknown): v is Record<string, unknown> {
    return typeof v === 'object' && v !== null && !Array.isArray(v);
}

/** Reads the JSON `weave check --format json` printed and checks its shape; throws {@link CheckParseError} with what is wrong. */
export function parseCheck(text: string): CheckResult {
    if (Buffer.byteLength(text, 'utf8') > MAX_CHECK_BYTES) {
        fail('The check result is larger than 2 MB and was not read');
    }
    let json: unknown;
    try {
        json = JSON.parse(text);
    } catch (e) {
        fail(`weave check did not print JSON: ${(e as Error).message}`);
    }
    if (!isObject(json)) {
        fail('weave check did not print a JSON object');
    }
    if (json.version !== SUPPORTED_VERSION) {
        fail(`weave check version ${String(json.version)} is not supported here (needs ${SUPPORTED_VERSION}). Update the extension or weave.jar so they match`);
    }
    if (typeof json.file !== 'string') {
        fail('file should be a string');
    }
    if (typeof json.ok !== 'boolean') {
        fail('ok should be true or false');
    }
    if (!Array.isArray(json.diagnostics)) {
        fail('diagnostics should be a list');
    }
    json.diagnostics.forEach((d: unknown, i: number) => {
        if (!isObject(d)) {
            fail(`diagnostics[${i}] should be an object`);
        }
        if (d.severity !== 'error' && d.severity !== 'warning') {
            fail(`diagnostics[${i}].severity should be "error" or "warning"`);
        }
        if (typeof d.file !== 'string' || typeof d.message !== 'string' || typeof d.line !== 'number') {
            fail(`diagnostics[${i}] needs a file, a line and a message`);
        }
    });
    const notSetYet = json.notSetYet === undefined ? [] : json.notSetYet;
    if (!Array.isArray(notSetYet) || notSetYet.some((n) => typeof n !== 'string')) {
        fail('notSetYet should be a list of names');
    }
    return { ...(json as unknown as CheckResult), notSetYet: notSetYet as string[] };
}
