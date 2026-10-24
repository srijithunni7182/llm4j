/** The oldest Java the engine runs on (the Loom module is compiled for release 17). */
export const MIN_JAVA = 17;

export type JavaCheck = { ok: true; major: number } | { ok: false; reason: 'missing' | 'too-old' | 'unreadable'; major?: number; detail: string };

/** The major version in the text `java -version` prints (`17.0.9`, `1.8.0_392` is 8, `21-ea` is 21), or undefined. */
export function parseJavaMajor(text: string): number | undefined {
    const m = /version "(\d+)(?:\.(\d+))?/.exec(text);
    if (!m) {
        return undefined;
    }
    const first = Number(m[1]);
    return first === 1 && m[2] !== undefined ? Number(m[2]) : first;
}

/** What running `java -version` gave: its output (it prints to standard error), or why it could not run. */
export type JavaRun = { output: string } | { error: string };

/** Says whether the Java at `javaPath` can run the engine. `run` is the part that touches the machine. */
export async function checkJava(javaPath: string, run: (javaPath: string) => Promise<JavaRun>): Promise<JavaCheck> {
    const result = await run(javaPath);
    if ('error' in result) {
        return { ok: false, reason: 'missing', detail: result.error };
    }
    const major = parseJavaMajor(result.output);
    if (major === undefined) {
        return { ok: false, reason: 'unreadable', detail: `\`${javaPath} -version\` printed something this extension cannot read` };
    }
    if (major < MIN_JAVA) {
        return { ok: false, reason: 'too-old', major, detail: `Java ${major} is too old; the engine needs ${MIN_JAVA} or newer` };
    }
    return { ok: true, major };
}
