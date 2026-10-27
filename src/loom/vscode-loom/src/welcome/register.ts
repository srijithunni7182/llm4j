import * as path from 'path';
import * as vscode from 'vscode';
import { shellKind, terminalCommand } from './welcome';

/** The welcome view's buttons. They type the extension's own jar command into an integrated terminal (so the output stays on screen); the extension itself starts no process for them. */
export function registerWelcome(context: vscode.ExtensionContext): void {
    const jar = context.asAbsolutePath(path.join('bin', 'weave.jar'));
    const java = (): string => vscode.workspace.getConfiguration('loom.graph').get<string>('javaPath', 'java');
    const run = (name: string, args: string[]): void => {
        const terminal = vscode.window.createTerminal({ name, cwd: vscode.workspace.workspaceFolders?.[0]?.uri.fsPath });
        terminal.show();
        terminal.sendText(terminalCommand(java(), jar, args, shellKind(vscode.env.shell, process.platform)));
    };
    context.subscriptions.push(
        vscode.commands.registerCommand('loom.try', () => run('Loom demo', ['try', '--step'])),
        vscode.commands.registerCommand('loom.doctor', () => run('Loom setup check', ['doctor'])),
        vscode.commands.registerCommand('loom.openExample', async () => {
            const folder = vscode.workspace.workspaceFolders?.[0]?.uri.fsPath;
            if (!folder) {
                void vscode.window.showInformationMessage('Open a folder first; the example is written into it.');
                return;
            }
            run('Loom example', ['try', 'triage', '--keep', path.join(folder, 'loom-demo')]);
        }),
    );
    const setHas = async (): Promise<void> => {
        const files = await vscode.workspace.findFiles('**/*.loom', '**/node_modules/**', 1);
        await vscode.commands.executeCommand('setContext', 'loom.hasLoom', files.length > 0);
    };
    void setHas();
    const watcher = vscode.workspace.createFileSystemWatcher('**/*.loom');
    watcher.onDidCreate(() => void setHas());
    watcher.onDidDelete(() => void setHas());
    context.subscriptions.push(watcher);
}
