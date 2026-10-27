/** The first-run path in the editor: try a demo, check the setup, and notice an engine and a CLI that disagree. */

/** "weave 6.0.0 (built …), serve protocol 1" -> 1; undefined when the line has none. */
export function protocolOf(versionLine: string): number | undefined {
    const m = /serve protocol (\d+)/.exec(versionLine);
    return m ? Number(m[1]) : undefined;
}

/** A sentence for the person when the bundled engine and a `weave` on the PATH speak different protocols; otherwise undefined. */
export function protocolMismatch(bundled: string, onPath: string): string | undefined {
    const a = protocolOf(bundled);
    const b = protocolOf(onPath);
    if (a === undefined || b === undefined || a === b) return undefined;
    return `The weave on your PATH speaks protocol ${b} but this extension's engine speaks ${a}. Update one so they match (weave --version shows it); the extension keeps using its own engine.`;
}

/**
 * How a button starts Java in the integrated terminal: the executable and its arguments, with no
 * shell in between, so no quoting is needed (and none can be wrong for PowerShell, cmd or bash).
 */
export function terminalOptions(javaPath: string, jarPath: string, args: string[], name: string, cwd?: string): { name: string; shellPath: string; shellArgs: string[]; cwd?: string } {
    return { name, shellPath: javaPath, shellArgs: ['-jar', jarPath, ...args], ...(cwd ? { cwd } : {}) };
}
