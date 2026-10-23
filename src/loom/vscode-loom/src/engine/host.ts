import { Scheduler } from '../graph/controller';
import { EngineClient, EngineProcess, EngineState, ProcessEngineClient } from './client';
import { JavaCheck, MIN_JAVA } from './javaProbe';
import { EngineSource } from './routing';

/** What the engine host needs from the editor and the machine; the extension supplies VS Code's, tests supply fakes. */
export interface EngineEnvironment {
    javaPath(): string;
    jarPath(): string;
    jarExists(): boolean;
    /** The folders to serve: the workspace folders, else the folders of the open scripts. */
    roots(): string[];
    unsavedText(file: string): string | undefined;
    startProcess(javaPath: string, jarPath: string, serve: ServeSettings): EngineProcess;
    /** Says whether the Java at `javaPath` can run the engine. Optional: without it the engine is simply started. */
    probeJava?(javaPath: string): Promise<JavaCheck>;
    /** The limits the engine is started with (`loom.serve.*`). */
    serve?(): ServeSettings;
    scheduler: Scheduler;
    /** Shows (or, with undefined, hides) the one line that says the editor is working in a limited way. */
    showStatus(status: EngineStatus | undefined): void;
    log(line: string): void;
}

/** How much the engine may keep, and how long it may take over one request. */
export interface ServeSettings {
    memoryMb: number;
    timeoutMs: number;
}

export const DEFAULT_SERVE: ServeSettings = { memoryMb: 64, timeoutMs: 10_000 };

/** How long the client waits beyond the engine's own limit, so the engine's answer arrives first. */
export const REQUEST_MARGIN_MS = 2_000;

export interface EngineStatus {
    text: string;
    tooltip: string;
    /** The command a click runs; the engine log when absent. */
    command?: string;
}

/** The one line for a Java that cannot run the engine (spec 10, R33): what works without it is in the tooltip. */
export function javaStatus(check: Extract<JavaCheck, { ok: false }>, javaPath: string): EngineStatus {
    const works = 'Syntax colours, snippets and the simpler completions still work.';
    if (check.reason === 'too-old') {
        return { text: `Loom: Java ${check.major} is too old (needs ${MIN_JAVA}+, limited mode)`, tooltip: `${check.detail}. Install Java ${MIN_JAVA} or newer, or point loom.javaPath at it. ${works}`, command: 'loom.javaHelp' };
    }
    if (check.reason === 'unreadable') {
        return { text: `Loom: Java ${MIN_JAVA}+ not found (limited mode)`, tooltip: `${check.detail}. Set loom.javaPath to a Java ${MIN_JAVA}+ executable. ${works}`, command: 'loom.javaHelp' };
    }
    return { text: `Loom: Java ${MIN_JAVA}+ not found (limited mode)`, tooltip: `Could not run "${javaPath}" (${check.detail}). Install Java ${MIN_JAVA} or newer, or set loom.javaPath. ${works}`, command: 'loom.javaHelp' };
}

/** What to tell the person, in one place, for a state that limits what works (spec 10, R33). `undefined` means nothing to say. */
export function statusFor(state: EngineState, reason: string): EngineStatus | undefined {
    switch (state) {
        case 'starting':
        case 'restarting':
            return { text: 'Loom: starting engine…', tooltip: 'The Loom engine is starting; checks use the command line until it is ready.' };
        case 'unavailable':
            return { text: 'Loom: engine unavailable (using commands)', tooltip: `${reason}. Checks and graphs still work by running weave for each request. Run "Loom: Restart Engine" to try again.` };
        case 'mismatch': {
            const versions = /protocol (\d+).*speaks (\d+)/.exec(reason);
            return { text: versions ? `Loom: reinstall the extension (engine v${versions[1]}, extension v${versions[2]})` : 'Loom: reinstall the extension', tooltip: reason };
        }
        default:
            return undefined;
    }
}

/**
 * The engine for one window: starts `weave serve` when the bundled jar is there, restarts it when settings change, and tells the rest
 * of the extension whether it can be asked. With no jar, or no Java, the engine stays absent and everything uses the command line.
 */
export class EngineHost implements EngineSource {
    private client: ProcessEngineClient | undefined;
    private subscription: { dispose(): void } | undefined;

    constructor(private readonly env: EngineEnvironment) {}

    /** Starts the engine; harmless to call again (it replaces the running one). */
    start(): void {
        this.stop();
        if (!this.env.jarExists()) {
            this.env.log(`weave.jar not found at ${this.env.jarPath()}; using the command line`);
            this.env.showStatus({
                text: 'Loom: reinstall the extension',
                tooltip: 'The bundled weave.jar is missing, so the Loom engine cannot start.',
            });
            return;
        }
        const javaPath = this.env.javaPath();
        const jarPath = this.env.jarPath();
        const serve = this.env.serve?.() ?? DEFAULT_SERVE;
        const client = new ProcessEngineClient({
            start: () => this.env.startProcess(javaPath, jarPath, serve),
            roots: () => this.env.roots(),
            scheduler: this.env.scheduler,
            requestTimeoutMs: serve.timeoutMs + REQUEST_MARGIN_MS,
            log: (line) => this.env.log(line),
        });
        this.client = client;
        this.subscription = client.onDidChangeState((state) => this.env.showStatus(statusFor(state, client.reason)));
        client.start();
        this.probe(javaPath, client);
    }

    /**
     * Asks whether the Java is one the engine can run on. If not, the engine is stopped and the one line says why; the answer comes
     * after the start, so a working Java never waits for it.
     */
    private probe(javaPath: string, client: ProcessEngineClient): void {
        const probe = this.env.probeJava;
        if (!probe) {
            return;
        }
        void probe.call(this.env, javaPath).then(
            (check) => {
                if (check.ok || this.client !== client) {
                    return;
                }
                this.env.log(`${check.detail}; the engine is not started`);
                this.stop();
                this.env.showStatus(javaStatus(check, javaPath));
            },
            (e: unknown) => this.env.log(`could not check the Java version: ${e instanceof Error ? e.message : String(e)}`),
        );
    }

    /** Stops the engine (before a restart, or when the window closes). */
    stop(): void {
        this.subscription?.dispose();
        this.subscription = undefined;
        this.client?.dispose();
        this.client = undefined;
        this.env.showStatus(undefined);
    }

    engine(): EngineClient | undefined {
        const c = this.client;
        // a client that cannot answer is not asked: the caller goes straight to the command line
        return c && (c.state === 'ready' || c.state === 'starting' || c.state === 'restarting') ? c : undefined;
    }

    unsavedText(file: string): string | undefined {
        return this.env.unsavedText(file);
    }

    /** The lines the engine wrote to standard error. */
    log(): string[] {
        return this.client?.log() ?? [];
    }

    get state(): EngineState | undefined {
        return this.client?.state;
    }

    dispose(): void {
        this.stop();
    }
}
