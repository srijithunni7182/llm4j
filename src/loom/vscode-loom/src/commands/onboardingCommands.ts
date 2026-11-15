import * as path from 'path';
import * as vscode from 'vscode';
import { HandoffHost, STARTER_PROMPT } from '../guide/agentSkills';
import { copyStarter, findStartersDir, pickItems, Starter } from '../starters/starterGallery';

export const WALKTHROUGH_ID = 'srijithunni7182.vscode-llm4j-loom#loom.gettingStarted';
const SHOWN_KEY = 'loom.walkthroughShown';

/** The editor's side of the hand-off to a coding agent. */
export function vscodeHandoffHost(): HandoffHost {
    return {
        copyToClipboard: (text) => Promise.resolve(vscode.env.clipboard.writeText(text)),
        async openChat(query) {
            try {
                // isPartialQuery fills the input without sending it; editors without this chat command throw, and the clipboard still has the prompt
                await vscode.commands.executeCommand('workbench.action.chat.open', { query, isPartialQuery: true });
                return true;
            } catch {
                return false;
            }
        },
        notify: (message, buttons) => Promise.resolve(vscode.window.showInformationMessage(message, ...buttons)),
    };
}

/** "Loom: Open Walkthrough", "Loom: Start a Workflow in Agent Chat" and "Loom: New Workflow from Starter...". The walkthrough also opens once, the first time the extension runs. */
export function registerOnboardingCommands(context: vscode.ExtensionContext): void {
    const handoff = vscodeHandoffHost();
    context.subscriptions.push(
        vscode.commands.registerCommand('loom.openWalkthrough', () => vscode.commands.executeCommand('workbench.action.openWalkthrough', WALKTHROUGH_ID, false)),
        vscode.commands.registerCommand('loom.startWorkflowInChat', async () => {
            await handoff.copyToClipboard(STARTER_PROMPT);
            if (!(await handoff.openChat(STARTER_PROMPT))) {
                void vscode.window.showInformationMessage('Could not open the chat in this editor. The starter prompt is on your clipboard: paste it into your agent.');
            }
        }),
        vscode.commands.registerCommand('loom.newFromStarter', () => newFromStarter(context)),
    );
    if (!context.globalState.get<boolean>(SHOWN_KEY, false)) {
        void context.globalState.update(SHOWN_KEY, true);
        void vscode.commands.executeCommand('loom.openWalkthrough');
    }
}

async function newFromStarter(context: vscode.ExtensionContext): Promise<void> {
    const workspace = vscode.workspace.workspaceFolders?.[0]?.uri.fsPath;
    if (!workspace) {
        void vscode.window.showInformationMessage('Open a folder first; the starter is copied into it.');
        return;
    }
    const dir = findStartersDir(context.extensionPath);
    if (!dir) {
        void vscode.window.showErrorMessage('Loom: the starters are missing from this installation. Reinstall the extension.');
        return;
    }
    const rows = pickItems().map((i) =>
        i.kind === 'separator' ? { label: i.label, kind: vscode.QuickPickItemKind.Separator } : { label: i.label, description: i.description, starter: i.starter },
    );
    const picked = (await vscode.window.showQuickPick(rows, { placeHolder: 'Pick a starter: each runs with scripted models, no API key', matchOnDescription: true })) as
        | { starter?: Starter }
        | undefined;
    if (!picked?.starter) {
        return;
    }
    try {
        const main = copyStarter(dir, picked.starter, workspace);
        await vscode.window.showTextDocument(await vscode.workspace.openTextDocument(vscode.Uri.file(main)));
        void vscode.window.showInformationMessage(`Copied ${path.basename(path.dirname(main))}. Run "weave test" on it: no key needed.`);
    } catch (e) {
        void vscode.window.showErrorMessage(`Loom: ${(e as Error).message}`);
    }
}
