import * as vscode from 'vscode';
import { engineHost } from '../engine/vscodeHost';
import { parseReview } from '../review/model';
import { worstCost } from '../review/presentation';
import { RunOnceHost, runOnceWithCap } from './runOnceWithCap';

/** Types a command into an integrated terminal and leaves Enter to the person, so the output stays on screen and nothing starts unseen. */
export function typeIntoTerminal(command: string): void {
    const terminal = vscode.window.createTerminal({ name: 'Loom', cwd: vscode.workspace.workspaceFolders?.[0]?.uri.fsPath });
    terminal.show();
    terminal.sendText(command, false);
}

function activeLoomFile(): string | undefined {
    const editor = vscode.window.activeTextEditor;
    return editor && editor.document.languageId === 'loom' ? editor.document.uri.fsPath : undefined;
}

const host: RunOnceHost = {
    scriptFile: activeLoomFile,
    async estimate(script) {
        try {
            const answer = await engineHost()?.engine()?.request('loom/review', { file: script, tier: 'full' });
            const review = answer ? parseReview(answer.result) : undefined;
            return review ? worstCost(review) : undefined;
        } catch {
            return undefined;
        }
    },
    askCap: (prompt) => Promise.resolve(vscode.window.showInputBox({ prompt, placeHolder: '$2   or   50000 tokens', ignoreFocusOut: true })),
    askInput: (prompt) => Promise.resolve(vscode.window.showInputBox({ prompt, ignoreFocusOut: true })),
    type: typeIntoTerminal,
    say: (message) => void vscode.window.showWarningMessage(message),
};

export function registerProgressCommands(context: vscode.ExtensionContext): void {
    context.subscriptions.push(
        vscode.commands.registerCommand('loom.progress.type', (command: unknown) => {
            if (typeof command === 'string' && /^weave [\w -./"=:$]+$/.test(command)) {
                typeIntoTerminal(command);
            }
        }),
        vscode.commands.registerCommand('loom.runOnceWithCap', () => runOnceWithCap(host)),
    );
}
