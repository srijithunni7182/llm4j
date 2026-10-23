import * as fs from 'fs';
import * as path from 'path';
import * as vscode from 'vscode';
import { samePath } from '../graph/paths';
import { TimeoutScheduler } from '../graph/timeoutScheduler';
import { execFile } from 'child_process';
import { DEFAULT_SERVE, EngineEnvironment, EngineHost, ServeSettings } from './host';
import { checkJava, JavaRun } from './javaProbe';
import { spawnEngineProcess } from './spawn';

/** The java executable: `loom.javaPath` when the person set it, else the older `loom.graph.javaPath`, else `java`. */
export function configuredJavaPath(): string {
    const inspected = vscode.workspace.getConfiguration('loom').inspect<string>('javaPath');
    const chosen = inspected?.workspaceFolderValue ?? inspected?.workspaceValue ?? inspected?.globalValue;
    return chosen?.trim() || vscode.workspace.getConfiguration('loom.graph').get<string>('javaPath', 'java');
}

function isLoom(document: vscode.TextDocument): boolean {
    return document.uri.scheme === 'file' && document.uri.fsPath.endsWith('.loom');
}

/** The folders the engine may read: the workspace folders, or, with none open, the folders of the open scripts. */
export function engineRoots(): string[] {
    const folders = (vscode.workspace.workspaceFolders ?? []).map((f) => f.uri.fsPath);
    if (folders.length > 0) {
        return folders;
    }
    return [...new Set(vscode.workspace.textDocuments.filter(isLoom).map((d) => path.dirname(d.uri.fsPath)))];
}

/** `loom.serve.*`, kept inside the range the engine can honour. */
export function serveSettings(): ServeSettings {
    const c = vscode.workspace.getConfiguration('loom.serve');
    const within = (value: number, low: number, high: number, fallback: number): number => (Number.isFinite(value) ? Math.min(high, Math.max(low, Math.round(value))) : fallback);
    return {
        memoryMb: within(c.get<number>('memoryMb', DEFAULT_SERVE.memoryMb), 8, 2048, DEFAULT_SERVE.memoryMb),
        timeoutMs: within(c.get<number>('timeoutMs', DEFAULT_SERVE.timeoutMs), 1000, 120_000, DEFAULT_SERVE.timeoutMs),
    };
}

function runJavaVersion(javaPath: string): Promise<JavaRun> {
    return new Promise((resolve) => {
        // `java -version` prints to standard error
        execFile(javaPath, ['-version'], { timeout: 8000 }, (error, stdout, stderr) => {
            if (error && !stderr && !stdout) {
                resolve({ error: error.message });
            } else {
                resolve({ output: `${stderr}\n${stdout}` });
            }
        });
    });
}

let host: EngineHost | undefined;
let channel: vscode.OutputChannel | undefined;

/** Writes a line to the "Loom Engine" output, for the parts of the extension that talk to the engine. */
export function engineLog(line: string): void {
    channel?.appendLine(line);
}

/** The window's engine host, once {@link registerEngine} has run. */
export function engineHost(): EngineHost | undefined {
    return host;
}

/**
 * Starts the engine for this window and registers "Restart Engine" and "Show Engine Log". The one status-bar item appears only while
 * something limits what works, and says what.
 */
export function registerEngine(context: vscode.ExtensionContext): EngineHost {
    const status = vscode.window.createStatusBarItem(vscode.StatusBarAlignment.Right, 90);
    const output = vscode.window.createOutputChannel('Loom Engine');
    channel = output;
    const jar = context.asAbsolutePath(path.join('bin', 'weave.jar'));
    const env: EngineEnvironment = {
        javaPath: configuredJavaPath,
        jarPath: () => jar,
        jarExists: () => fs.existsSync(jar),
        roots: engineRoots,
        unsavedText: (file) => {
            const d = vscode.workspace.textDocuments.find((x) => x.isDirty && samePath(x.uri.fsPath, file));
            return d?.getText();
        },
        startProcess: spawnEngineProcess,
        probeJava: (javaPath) => checkJava(javaPath, runJavaVersion),
        serve: serveSettings,
        scheduler: new TimeoutScheduler(),
        showStatus: (s) => {
            if (s) {
                status.text = s.text;
                status.tooltip = s.tooltip;
                status.command = s.command ?? 'loom.showEngineLog';
                status.show();
            } else {
                status.hide();
            }
        },
        log: (line) => output.appendLine(line),
    };
    host = new EngineHost(env);
    host.start();
    context.subscriptions.push(
        status,
        output,
        { dispose: () => host?.dispose() },
        vscode.commands.registerCommand('loom.restartEngine', () => host?.start()),
        vscode.commands.registerCommand('loom.javaHelp', async () => {
            const choice = await vscode.window.showInformationMessage(
                'Loom needs Java 17 or newer to run checks, the review and the editor features that use the engine. Install a JDK 17 or newer, or point the loom.javaPath setting at one; then run "Loom: Restart Engine".',
                'Open setting',
            );
            if (choice === 'Open setting') {
                await vscode.commands.executeCommand('workbench.action.openSettings', 'loom.javaPath');
            }
        }),
        vscode.commands.registerCommand('loom.showEngineLog', () => {
            host?.log().forEach((l) => output.appendLine(l));
            output.show(true);
        }),
        vscode.workspace.onDidChangeConfiguration((e) => {
            if (e.affectsConfiguration('loom.javaPath') || e.affectsConfiguration('loom.graph.javaPath') || e.affectsConfiguration('loom.serve')) {
                host?.start();
            }
        }),
    );
    return host;
}
