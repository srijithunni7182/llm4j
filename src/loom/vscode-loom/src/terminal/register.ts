import * as path from 'path';
import * as vscode from 'vscode';
import { configuredJavaPath } from '../engine/vscodeHost';
import { ensureLauncher, pathCollectionFor } from './weaveLauncher';

const SETTING = 'loom.terminal.addWeaveToPath';

/**
 * Puts `weave` on the PATH of the integrated terminals, so the command the guides and the docs show works in them with nothing
 * else installed. The launcher lives in the extension's own storage (it survives an update, which moves the extension folder)
 * and is rewritten at start-up to point at the jar of the version that is running.
 */
export function registerWeaveOnPath(context: vscode.ExtensionContext): void {
    const jar = context.asAbsolutePath(path.join('bin', 'weave.jar'));
    const folder = path.join(context.globalStorageUri.fsPath, 'bin');
    let told = false;
    const warnOnce = (message: string): void => {
        if (told) return;
        told = true;
        void vscode.window.showWarningMessage(message);
    };
    const apply = (): void => {
        const enabled = vscode.workspace.getConfiguration().get<boolean>(SETTING, true);
        pathCollectionFor(context.environmentVariableCollection, enabled, path.delimiter, () => path.dirname(ensureLauncher(folder, process.platform, configuredJavaPath(), jar)), warnOnce);
    };
    apply();
    context.subscriptions.push(
        vscode.workspace.onDidChangeConfiguration((e) => {
            if (e.affectsConfiguration(SETTING) || e.affectsConfiguration('loom.javaPath') || e.affectsConfiguration('loom.graph.javaPath')) apply();
        }),
    );
}
