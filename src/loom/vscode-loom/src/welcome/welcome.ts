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

/** The command a button runs in the integrated terminal: always the extension's own jar, never a guess. */
export function terminalCommand(javaPath: string, jarPath: string, args: string[]): string {
    const q = (s: string) => (/^[A-Za-z0-9_\/.:\\-]+$/.test(s) ? s : `"${s.replace(/(["\\$`])/g, '\\$1')}"`);
    return [q(javaPath), '-jar', q(jarPath), ...args.map(q)].join(' ');
}
