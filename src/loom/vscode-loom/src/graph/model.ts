/** The result of `weave graph`, version 1 (see loom/ai-agent4j-loom graph-result.schema.json). */

export interface SourceRef {
    file: string;
    line: number;
}

export interface GraphNode {
    id: string;
    kind: string;
    label: string;
    agent?: string;
    bound?: number;
    source?: SourceRef;
    call?: { workflow: string; file?: string };
    unresolved?: boolean;
    parent?: string;
    branch?: string;
    attrs?: Record<string, unknown>;
}

export interface GraphEdge {
    from: string;
    to: string;
    label?: string;
}

export interface WorkflowGraph {
    name: string;
    file: string;
    line?: number;
    params: string[];
    nodes: GraphNode[];
    edges: GraphEdge[];
}

export interface ImportFile {
    path: string;
    imports: string[];
}

export interface AgentInfo {
    name: string;
    model?: string;
    temperature?: number;
    persona?: string;
    tools?: string[];
    mcp?: string[];
    skills?: string[];
    knowledge?: string[];
    approve?: string[];
    approveAll?: boolean;
    budget?: Record<string, unknown>;
    maxIterations?: number;
    source?: SourceRef;
    /** The prompt file the agent runs, when it names one. */
    prompt?: { ref: string; version?: string; file?: string };
}

export interface Diagnostic {
    severity: 'error' | 'warning';
    file: string;
    line: number;
    message: string;
}

export interface GraphResult {
    version: 1;
    entry: string;
    files: ImportFile[];
    workflows: WorkflowGraph[];
    agents: AgentInfo[];
    runBudget?: Record<string, unknown>;
    diagnostics: Diagnostic[];
}

/** Output beyond this size is refused before it is parsed (a runaway script cannot stall the editor). */
export const MAX_GRAPH_BYTES = 8 * 1024 * 1024;

export const SUPPORTED_VERSION = 1;

export class GraphParseError extends Error {
    constructor(message: string) {
        super(message);
        this.name = 'GraphParseError';
    }
}

function fail(message: string): never {
    throw new GraphParseError(message);
}

function isObject(value: unknown): value is Record<string, unknown> {
    return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function str(value: unknown, where: string): string {
    if (typeof value !== 'string') {
        fail(`${where} should be a string`);
    }
    return value;
}

function list<T>(value: unknown, where: string, each: (item: unknown, at: string) => T): T[] {
    if (!Array.isArray(value)) {
        fail(`${where} should be a list`);
    }
    return value.map((item, i) => each(item, `${where}[${i}]`));
}

function node(value: unknown, where: string): GraphNode {
    if (!isObject(value)) {
        fail(`${where} should be an object`);
    }
    str(value.id, `${where}.id`);
    str(value.kind, `${where}.kind`);
    str(value.label, `${where}.label`);
    return value as unknown as GraphNode;
}

function edge(value: unknown, where: string): GraphEdge {
    if (!isObject(value)) {
        fail(`${where} should be an object`);
    }
    str(value.from, `${where}.from`);
    str(value.to, `${where}.to`);
    return value as unknown as GraphEdge;
}

function workflow(value: unknown, where: string): WorkflowGraph {
    if (!isObject(value)) {
        fail(`${where} should be an object`);
    }
    str(value.name, `${where}.name`);
    str(value.file, `${where}.file`);
    list(value.params, `${where}.params`, (p, at) => str(p, at));
    list(value.nodes, `${where}.nodes`, node);
    list(value.edges, `${where}.edges`, edge);
    return value as unknown as WorkflowGraph;
}

function importFile(value: unknown, where: string): ImportFile {
    if (!isObject(value)) {
        fail(`${where} should be an object`);
    }
    str(value.path, `${where}.path`);
    list(value.imports, `${where}.imports`, (p, at) => str(p, at));
    return value as unknown as ImportFile;
}

function diagnostic(value: unknown, where: string): Diagnostic {
    if (!isObject(value)) {
        fail(`${where} should be an object`);
    }
    if (value.severity !== 'error' && value.severity !== 'warning') {
        fail(`${where}.severity should be "error" or "warning"`);
    }
    str(value.file, `${where}.file`);
    if (typeof value.line !== 'number') {
        fail(`${where}.line should be a number`);
    }
    str(value.message, `${where}.message`);
    return value as unknown as Diagnostic;
}

/** Parses the text `weave graph` prints and checks its shape. Throws {@link GraphParseError} with what is wrong. */
export function parseGraph(text: string): GraphResult {
    if (Buffer.byteLength(text, 'utf8') > MAX_GRAPH_BYTES) {
        fail(`The graph is larger than ${MAX_GRAPH_BYTES / (1024 * 1024)} MB and was not read`);
    }
    let json: unknown;
    try {
        json = JSON.parse(text);
    } catch (e) {
        fail(`weave graph did not print JSON: ${(e as Error).message}`);
    }
    if (!isObject(json)) {
        fail('weave graph did not print a JSON object');
    }
    if (json.version !== SUPPORTED_VERSION) {
        fail(`weave graph version ${String(json.version)} is not supported here (needs ${SUPPORTED_VERSION}). Update the extension or weave.jar so they match`);
    }
    str(json.entry, 'entry');
    list(json.files, 'files', importFile);
    list(json.workflows, 'workflows', workflow);
    list(json.agents, 'agents', (a, at) => {
        if (!isObject(a)) {
            fail(`${at} should be an object`);
        }
        str(a.name, `${at}.name`);
        if (a.prompt !== undefined) {
            if (!isObject(a.prompt)) {
                fail(`${at}.prompt should be an object`);
            }
            str(a.prompt.ref, `${at}.prompt.ref`);
        }
        return a;
    });
    list(json.diagnostics, 'diagnostics', diagnostic);
    return json as unknown as GraphResult;
}
