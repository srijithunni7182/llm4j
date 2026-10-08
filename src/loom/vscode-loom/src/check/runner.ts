import { WeaveGraphRunner } from '../graph/runner';

export interface CheckRequest {
    file: string;
    signal: AbortSignal;
}

/** What the extension needs from `weave check`; tests supply fakes. */
export interface CheckRunner {
    run(request: CheckRequest): Promise<string>;
}

/**
 * Runs `java -jar weave.jar check <file> --format json --no-env`. `--no-env` because a person who has not set their keys yet is not
 * making a mistake in the script. Exit 0 (fine) and 2 (problems) both print the JSON; any other exit is an error.
 */
export class WeaveCheckRunner implements CheckRunner {
    constructor(private readonly weave: WeaveGraphRunner) {}

    run(request: CheckRequest): Promise<string> {
        return this.weave.runWeave(['check', request.file, '--format', 'json', '--no-env'], request.signal, [0, 2]);
    }
}
