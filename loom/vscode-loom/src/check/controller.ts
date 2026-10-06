import { GraphAbortedError, RuntimeMissingError } from '../graph/errors';
import { samePath } from '../graph/paths';
import { Scheduler, TimerHandle } from '../graph/controller';
import { CheckDiagnostic, parseCheck } from './model';
import { CheckRunner } from './runner';

/** What the controller needs from the editor; the real one wraps a VS Code diagnostic collection, tests use a fake. */
export interface DiagnosticsHost {
    /** Shows these as the problems of `file`, replacing what was shown before. */
    set(file: string, diagnostics: CheckDiagnostic[]): void;
    clear(file: string): void;
    /** Says something once to the person (the runtime is missing, for example). */
    notify(message: string): void;
}

export interface CheckControllerOptions {
    runner: CheckRunner;
    host: DiagnosticsHost;
    scheduler: Scheduler;
    /** Wait this long after the last request for a file before running. */
    debounceMs?: number;
}

export const CHECK_DEBOUNCE_MS = 300;

/**
 * Shows the problems `weave check` finds in .loom files, as the editor's own problems list. The parser is the language's own, so the
 * editor and `weave check` cannot disagree. It checks the file on disk: ask when a file is opened or saved. Requests for one file are
 * debounced and a newer run stops an older one; a run that fails to start leaves what was shown as it was, and says so once.
 */
export class CheckDiagnosticsController {
    private readonly timers = new Map<string, TimerHandle>();
    private readonly running = new Map<string, AbortController>();
    private told = new Set<string>();
    private disposed = false;

    constructor(private readonly options: CheckControllerOptions) {}

    /** The file was opened or saved. */
    request(file: string): void {
        if (this.disposed) {
            return;
        }
        const key = this.key(file);
        this.timers.get(key)?.cancel();
        this.timers.set(
            key,
            this.options.scheduler.after(this.options.debounceMs ?? CHECK_DEBOUNCE_MS, () => {
                this.timers.delete(key);
                void this.check(file);
            }),
        );
    }

    /** The file was closed: its problems go away. */
    closed(file: string): void {
        const key = this.key(file);
        this.timers.get(key)?.cancel();
        this.timers.delete(key);
        this.running.get(key)?.abort();
        this.running.delete(key);
        this.options.host.clear(file);
    }

    dispose(): void {
        this.disposed = true;
        this.timers.forEach((t) => t.cancel());
        this.running.forEach((r) => r.abort());
        this.timers.clear();
        this.running.clear();
    }

    private key(file: string): string {
        return file.replace(/\\/g, '/');
    }

    private async check(file: string): Promise<void> {
        const key = this.key(file);
        this.running.get(key)?.abort();
        const run = new AbortController();
        this.running.set(key, run);
        try {
            const text = await this.options.runner.run({ file, signal: run.signal });
            if (run.signal.aborted || this.disposed) {
                return;
            }
            const result = parseCheck(text);
            // problems in other files (an import) are shown where they are, and the rest here
            const mine = result.diagnostics.filter((d) => samePath(d.file, file));
            this.options.host.set(file, mine);
        } catch (e) {
            if (e instanceof GraphAbortedError || run.signal.aborted) {
                return;
            }
            this.failed(file, e as Error);
        } finally {
            if (this.running.get(key) === run) {
                this.running.delete(key);
            }
        }
    }

    private failed(file: string, error: Error): void {
        // what was shown stays: a check that could not run says nothing about the script
        const what = error instanceof RuntimeMissingError ? error.what : error.name;
        if (this.told.has(what)) {
            return;
        }
        this.told.add(what);
        this.options.host.notify(`Loom could not check ${file.split(/[\\/]/).pop()}: ${error.message}`);
    }
}
