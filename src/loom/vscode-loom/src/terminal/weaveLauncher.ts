import * as fs from 'fs';
import * as path from 'path';

/** The file that puts `weave` in a terminal: its name, what is in it. */
export interface Launcher {
    fileName: string;
    content: string;
}

/** The part of VS Code's terminal environment collection this feature uses, so it can be tested without VS Code. */
export interface PathCollection {
    /** VS Code accepts text or markdown here; this feature only ever sets text. */
    description: unknown;
    clear(): void;
    append(variable: string, value: string): void;
}

const EXECUTABLE = 0o755;
const WINDOWS_LINE_END = '\r\n';
const COLLECTION_DESCRIPTION = 'Adds the `weave` command (the Loom command line, carried by the Loom extension) to the PATH of integrated terminals.';

function refuseUnsafe(what: string, value: string, platform: NodeJS.Platform): void {
    if (/[\0\r\n]/.test(value)) throw new Error(`The ${what} cannot be used: it holds a control character (a line break or NUL).`);
    if (platform === 'win32' && value.includes('"')) throw new Error(`The ${what} cannot be used: it holds a double quote.`);
}

/** Single-quoted for /bin/sh: nothing inside is expanded, and a quote inside is closed, escaped and reopened. */
function shellQuoted(value: string): string {
    return `'${value.replace(/'/g, `'\\''`)}'`;
}

/**
 * The launcher for a platform. It runs the jar the extension carries with the Java the person configured and hands every
 * argument over untouched, so `weave` in a terminal is the same engine the extension's own commands use. The two paths come
 * from a setting and from where the extension is installed; a path that could end the quoting and add a command is refused.
 */
export function launcherFor(platform: NodeJS.Platform, javaPath: string, jarPath: string): Launcher {
    refuseUnsafe('Java path', javaPath, platform);
    refuseUnsafe('weave.jar path', jarPath, platform);
    if (platform === 'win32') {
        const quoted = (value: string): string => `"${value.replace(/%/g, '%%')}"`;
        const lines = ['@echo off', 'rem Written by the Loom extension: runs the weave command line it carries. Rewritten when the extension starts.', `${quoted(javaPath)} -jar ${quoted(jarPath)} %*`, ''];
        return { fileName: 'weave.cmd', content: lines.join(WINDOWS_LINE_END) };
    }
    const lines = ['#!/bin/sh', '# Written by the Loom extension: runs the weave command line it carries. Rewritten when the extension starts.', `exec ${shellQuoted(javaPath)} -jar ${shellQuoted(jarPath)} "$@"`, ''];
    return { fileName: 'weave', content: lines.join('\n') };
}

/**
 * Writes the launcher into `folder` (made if missing) and returns its path. A file that already says the same is left alone, so
 * most starts write nothing; a changed one is written beside the old and renamed over it, so a terminal that is running it
 * never reads half a script.
 */
export function ensureLauncher(folder: string, platform: NodeJS.Platform, javaPath: string, jarPath: string): string {
    const launcher = launcherFor(platform, javaPath, jarPath);
    const target = path.join(folder, launcher.fileName);
    fs.mkdirSync(folder, { recursive: true });
    if (fs.existsSync(target) && fs.readFileSync(target, 'utf8') === launcher.content) return target;
    const staging = `${target}.${process.pid}.tmp`;
    fs.writeFileSync(staging, launcher.content, { mode: EXECUTABLE });
    fs.chmodSync(staging, EXECUTABLE);
    fs.renameSync(staging, target);
    return target;
}

/**
 * Applies the setting to the terminal environment. On: the launcher folder goes after the existing PATH entries, so a `weave`
 * the person installed themselves still wins. Off, or when the launcher cannot be written: the PATH is left as it was, and a
 * failure is said once with its reason.
 */
export function pathCollectionFor(collection: PathCollection, enabled: boolean, delimiter: string, writeLauncherFolder: () => string, warn: (message: string) => void): void {
    collection.clear();
    if (!enabled) return;
    try {
        const folder = writeLauncherFolder();
        collection.description = COLLECTION_DESCRIPTION;
        collection.append('PATH', `${delimiter}${folder}`);
    } catch (e) {
        warn(`Loom could not add the weave command to your terminals: ${e instanceof Error ? e.message : String(e)}`);
    }
}
