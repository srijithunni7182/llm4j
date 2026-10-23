import * as fs from 'fs';
import * as path from 'path';
import * as vscode from 'vscode';
import { samePath } from '../graph/paths';
import { TimeoutScheduler } from '../graph/timeoutScheduler';
import { EngineEnvironment, EngineHost } from './host';
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

let host: EngineHost | undefined;

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
        scheduler: new TimeoutScheduler(),
        showStatus: (s) => {
            if (s) {
                status.text = s.text;
                status.tooltip = s.tooltip;
                status.command = 'loom.showEngineLog';
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
        vscode.commands.registerCommand('loom.showEngineLog', () => {
            host?.log().forEach((l) => output.appendLine(l));
            output.show(true);
        }),
        vscode.workspace.onDidChangeConfiguration((e) => {
            if (e.affectsConfiguration('loom.javaPath') || e.affectsConfiguration('loom.graph.javaPath')) {
                host?.start();
            }
        }),
    );
    return host;
}
