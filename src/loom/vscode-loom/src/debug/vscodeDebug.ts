import * as fs from 'fs';
import * as path from 'path';
import * as vscode from 'vscode';
import { configuredJavaPath } from '../engine/vscodeHost';
import { adapterArgs, DEBUG_TYPE, launchFor, liveWarning, resolveLaunch } from './debugConfig';

/**
 * Wires the debugger into the editor: the `loom` debug type (F5 on a .loom file with no launch.json creates a configuration), the
 * adapter (`weave dap` from the bundled engine), and the "Loom: Debug Workflow" command the CodeLens and the palette run. All decisions
 * about a configuration are in debugConfig.ts; this only asks the person and starts the session.
 */
export function registerDebug(context: vscode.ExtensionContext): void {
    const jar = context.asAbsolutePath(path.join('bin', 'weave.jar'));

    const provider: vscode.DebugConfigurationProvider = {
        provideDebugConfigurations() {
            return [{ ...launchFor('${file}'), name: 'Debug the open Loom workflow' }];
        },
        async resolveDebugConfiguration(_folder, config) {
            const file = vscode.window.activeTextEditor?.document.uri.fsPath;
            const resolved = resolveLaunch(config as Record<string, unknown>, { file });
            if (!resolved.config) {
                void vscode.window.showErrorMessage(`Loom: ${resolved.problem}`);
                return undefined;
            }
            const warning = liveWarning(resolved.config);
            if (warning) {
                const go = 'Spend and debug';
                const answer = await vscode.window.showWarningMessage(warning, { modal: true }, go);
                if (answer !== go) {
                    return undefined;
                }
                return { ...resolved.config, confirmed: true };
            }
            return resolved.config;
        },
    };

    const factory: vscode.DebugAdapterDescriptorFactory = {
        createDebugAdapterDescriptor() {
            if (!fs.existsSync(jar)) {
                void vscode.window.showErrorMessage('Loom: the engine (bin/weave.jar) is missing from the extension, so workflows cannot be debugged.');
                return undefined;
            }
            return new vscode.DebugAdapterExecutable(configuredJavaPath(), adapterArgs(jar));
        },
    };

    context.subscriptions.push(
        vscode.debug.registerDebugConfigurationProvider(DEBUG_TYPE, provider),
        vscode.debug.registerDebugAdapterDescriptorFactory(DEBUG_TYPE, factory),
        vscode.commands.registerCommand('loom.debug', async (target?: vscode.Uri | string, workflow?: string) => {
            const file = typeof target === 'string' ? target : target instanceof vscode.Uri ? target.fsPath : vscode.window.activeTextEditor?.document.uri.fsPath;
            if (!file || !file.endsWith('.loom')) {
                void vscode.window.showErrorMessage('Loom: open a .loom file to debug it.');
                return;
            }
            const folder = vscode.workspace.getWorkspaceFolder(vscode.Uri.file(file));
            await vscode.debug.startDebugging(folder, launchFor(file, typeof workflow === 'string' ? workflow : undefined));
        }),
    );
}
