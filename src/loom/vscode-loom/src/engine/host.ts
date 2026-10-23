import { Scheduler } from '../graph/controller';
import { EngineClient, EngineProcess, EngineState, ProcessEngineClient } from './client';
import { EngineSource } from './routing';

/** What the engine host needs from the editor and the machine; the extension supplies VS Code's, tests supply fakes. */
export interface EngineEnvironment {
    javaPath(): string;
    jarPath(): string;
    jarExists(): boolean;
    /** The folders to serve: the workspace folders, else the folders of the open scripts. */
    roots(): string[];
    unsavedText(file: string): string | undefined;
    startProcess(javaPath: string, jarPath: string): EngineProcess;
    scheduler: Scheduler;
    /** Shows (or, with undefined, hides) the one line that says the editor is working in a limited way. */
    showStatus(status: EngineStatus | undefined): void;
    log(line: string): void;
}

export interface EngineStatus {
    text: string;
    tooltip: string;
}

/** What to tell the person, in one place, for a state that limits what works (spec 10, R33). `undefined` means nothing to say. */
export function statusFor(state: EngineState, reason: string): EngineStatus | undefined {
    switch (state) {
        case 'starting':
        case 'restarting':
            return { text: 'Loom: starting engine…', tooltip: 'The Loom engine is starting; checks use the command line until it is ready.' };
        case 'unavailable':
            return { text: 'Loom: engine unavailable (using commands)', tooltip: `${reason}. Checks and graphs still work by running weave for each request. Run "Loom: Restart Engine" to try again.` };
        case 'mismatch':
            return { text: 'Loom: reinstall the extension', tooltip: reason };
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
        const client = new ProcessEngineClient({
            start: () => this.env.startProcess(javaPath, jarPath),
            roots: () => this.env.roots(),
            scheduler: this.env.scheduler,
            log: (line) => this.env.log(line),
        });
        this.client = client;
        this.subscription = client.onDidChangeState((state) => this.env.showStatus(statusFor(state, client.reason)));
        client.start();
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
