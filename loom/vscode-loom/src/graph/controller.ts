import { chooseWorkflow } from './chooseWorkflow';
import { GraphAbortedError, GraphExitError } from './errors';
import { GraphResult, parseGraph } from './model';
import { samePath } from './paths';
import { HostMessage, parseWebviewMessage } from './protocol';
import { GraphRunner } from './runner';

/** What the controller needs from the editor; the real one wraps VS Code, tests use a fake. */
export interface PanelHost {
    post(message: HostMessage): void;
    openSource(file: string, line: number, beside: boolean): void;
    report(message: string, kind: 'error' | 'warning' | 'info'): void;
    copy(text: string): Promise<void>;
}

export interface TimerHandle {
    cancel(): void;
}

export interface Scheduler {
    after(ms: number, run: () => void): TimerHandle;
}

export interface ControllerOptions {
    /** The script the panel was opened for. */
    entry: string;
    /** Where the cursor was when the panel opened, to choose the first workflow. */
    cursor: { file: string; line: number };
    runner: GraphRunner;
    host: PanelHost;
    scheduler: Scheduler;
    /** Wait this long after the last save before running again. */
    debounceMs?: number;
}

export const DEFAULT_DEBOUNCE_MS = 300;

export type LoadOutcome = { ok: true; result: GraphResult } | { ok: false; error: Error };

/**
 * The behaviour of one graph panel, apart from VS Code: it loads the graph, refreshes it when a file in the import
 * closure is saved (debounced, and a newer run stops an older one), keeps the last good graph when a refresh fails,
 * follows the cursor, and answers the panel's messages. Messages from the panel are checked before they are acted on.
 */
export class GraphPanelController {
    private current: GraphResult | undefined;
    private selected: string | undefined;
    private cursor: { file: string; line: number };
    private timer: TimerHandle | undefined;
    private running: AbortController | undefined;
    private lastHighlight: string | null = null;
    /** The file a failed refresh pointed at: it may not be in the last good graph, but the person asked to see it. */
    private errorFile: string | undefined;
    private disposed = false;

    constructor(private readonly options: ControllerOptions) {
        this.cursor = options.cursor;
    }

    get result(): GraphResult | undefined {
        return this.current;
    }

    get selectedWorkflow(): string | undefined {
        return this.selected;
    }

    /** The first run. The caller decides what to do when it fails; the controller posts nothing then. */
    async load(): Promise<LoadOutcome> {
        const run = this.begin();
        try {
            const result = await this.fetch(run);
            this.current = result;
            this.selected = chooseWorkflow(result, this.cursor.file, this.cursor.line);
            this.postGraph();
            return { ok: true, result };
        } catch (e) {
            return { ok: false, error: e as Error };
        }
    }

    /** Asks for a refresh; several requests close together become one run. */
    refresh(): void {
        if (this.disposed) {
            return;
        }
        this.timer?.cancel();
        this.timer = this.options.scheduler.after(this.options.debounceMs ?? DEFAULT_DEBOUNCE_MS, () => {
            this.timer = undefined;
            void this.reload();
        });
    }

    /** A file was saved: refresh when it is the entry or one of its imports. */
    fileSaved(file: string): void {
        if (this.current?.files.some((f) => samePath(f.path, file)) || samePath(this.options.entry, file) || this.isKnown(file)) {
            this.refresh();
        }
    }

    /** The cursor moved in the editor: highlight the step written on that line, or clear the highlight. */
    cursorMoved(file: string, line: number): void {
        this.cursor = { file, line };
        if (!this.current || this.disposed) {
            return;
        }
        const workflow = this.current.workflows.find((w) => w.name === this.selected);
        const node = workflow?.nodes.find((n) => n.source && samePath(n.source.file, file) && n.source.line === line);
        const id = node ? node.id : null;
        if (id !== this.lastHighlight) {
            this.lastHighlight = id;
            this.options.host.post({ type: 'highlight', id });
        }
    }

    /** A message from the panel. Anything that is not exactly one of the known messages is ignored. */
    handle(raw: unknown): void {
        const message = parseWebviewMessage(raw);
        if (!message || this.disposed) {
            return;
        }
        switch (message.type) {
            case 'ready':
                if (this.current) {
                    this.postGraph();
                } else {
                    this.options.host.post({ type: 'loading', file: this.options.entry });
                }
                return;
            case 'selectWorkflow':
                if (this.current?.workflows.some((w) => w.name === message.name)) {
                    this.selected = message.name;
                }
                return;
            case 'openSource':
                if (this.isKnown(message.file)) {
                    this.options.host.openSource(message.file, message.line, message.beside);
                } else {
                    this.options.host.report(`Loom: ${message.file} is not part of this graph, so it was not opened.`, 'warning');
                }
                return;
            case 'copyMermaid':
                void this.copyMermaid(message.name);
                return;
        }
    }

    /** Stops any run in flight and every timer; nothing is posted afterwards. */
    dispose(): void {
        this.disposed = true;
        this.timer?.cancel();
        this.timer = undefined;
        this.running?.abort();
        this.running = undefined;
    }

    private async reload(): Promise<void> {
        const run = this.begin();
        try {
            const result = await this.fetch(run);
            this.current = result;
            if (!result.workflows.some((w) => w.name === this.selected)) {
                this.selected = chooseWorkflow(result, this.cursor.file, this.cursor.line);
            }
            this.lastHighlight = null;
            this.errorFile = undefined;
            this.postGraph();
        } catch (e) {
            if (run.signal.aborted || e instanceof GraphAbortedError) {
                return; // a newer run took over, or the panel was closed: not an error to show
            }
            const described = this.describe(e as Error);
            this.errorFile = described.file;
            this.options.host.post({ type: 'stale', ...described });
        }
    }

    /** Starts a run: any run still going is stopped, so only the newest one can change what is shown. */
    private begin(): AbortController {
        this.running?.abort();
        const run = new AbortController();
        this.running = run;
        return run;
    }

    /** Runs weave graph for one run; throws {@link GraphAbortedError} when the run was stopped in the meantime. */
    private async fetch(run: AbortController): Promise<GraphResult> {
        const text = await this.options.runner.run({ file: this.options.entry, format: 'json', signal: run.signal });
        if (run.signal.aborted || this.disposed) {
            throw new GraphAbortedError();
        }
        return parseGraph(text);
    }

    /** Files the panel may ask to open: the entry, its imports, files that have a problem reported, and the file of the last error. */
    private isKnown(file: string): boolean {
        return (
            samePath(this.options.entry, file) ||
            (this.errorFile !== undefined && samePath(this.errorFile, file)) ||
            !!this.current?.files.some((f) => samePath(f.path, file)) ||
            !!this.current?.diagnostics.some((d) => samePath(d.file, file))
        );
    }

    private postGraph(): void {
        if (this.current) {
            this.options.host.post({ type: 'graph', result: this.current, workflow: this.selected ?? '' });
        }
    }

    private describe(error: Error): { message: string; file?: string; line?: number } {
        if (error instanceof GraphExitError) {
            return { message: error.message, ...(error.location() ?? {}) };
        }
        return { message: error.message };
    }

    private async copyMermaid(name: string): Promise<void> {
        if (!this.current?.workflows.some((w) => w.name === name)) {
            return;
        }
        try {
            const text = await this.options.runner.run({
                file: this.options.entry,
                format: 'mermaid',
                workflow: name,
                signal: new AbortController().signal,
            });
            await this.options.host.copy(text);
            this.options.host.report(`Copied the Mermaid diagram of ${name}.`, 'info');
        } catch (e) {
            this.options.host.report(`Loom: could not copy the diagram. ${(e as Error).message}`, 'error');
        }
    }
}
