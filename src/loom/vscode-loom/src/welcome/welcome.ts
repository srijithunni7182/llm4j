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

export type ShellKind = 'posix' | 'powershell' | 'cmd';

/** Which quoting a terminal's shell needs, from the shell's path (vscode.env.shell). */
export function shellKind(shellPath: string | undefined, platform: string): ShellKind {
    const name = (shellPath ?? '').toLowerCase().replace(/\\/g, '/').split('/').pop() ?? '';
    if (name.startsWith('pwsh') || name.startsWith('powershell')) return 'powershell';
    if (name === 'cmd' || name === 'cmd.exe') return 'cmd';
    return !shellPath && platform === 'win32' ? 'powershell' : 'posix';
}

/**
 * The line a button types into the integrated terminal, quoted for that terminal's shell. It is typed
 * into the user's shell, and not started as the terminal's own process, so the terminal stays open
 * with its output when the command ends.
 */
export function terminalCommand(javaPath: string, jarPath: string, args: string[], kind: ShellKind): string {
    const plain = /^[A-Za-z0-9_\/.:\\-]+$/;
    const q = (s: string): string => {
        if (kind === 'powershell') return `'${s.replace(/'/g, "''")}'`;
        if (kind === 'cmd') return plain.test(s) ? s : `"${s.replace(/"/g, '')}"`;
        return plain.test(s) ? s : `"${s.replace(/(["\\$`])/g, '\\$1')}"`;
    };
    const line = [javaPath, '-jar', jarPath, ...args].map(q).join(' ');
    return kind === 'powershell' ? `& ${line}` : line;
}
