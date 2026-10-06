import * as path from 'path';
import * as vscode from 'vscode';
import { GuideHost, installSkill, openGuide } from '../guide/guideCommands';
import { GraphExitError } from '../graph/errors';
import { WeaveGraphRunner } from '../graph/runner';
import { readGraphSettings } from '../views/GraphPanel';

/** "Loom: Open Guide" and "Loom: Install the Loom Skill in This Project": both just run `weave guide` from the bundled jar. */
export function registerGuideCommands(context: vscode.ExtensionContext): void {
    const settings = readGraphSettings();
    const runner = new WeaveGraphRunner({
        javaPath: settings.javaPath,
        jarPath: context.asAbsolutePath(path.join('bin', 'weave.jar')),
        timeoutMs: settings.timeoutMs,
    });
    const host: GuideHost = {
        async weave(args) {
            try {
                return await runner.runWeave(args, new AbortController().signal, [0]);
            } catch (e) {
                throw e instanceof GraphExitError ? new Error(e.message) : e;
            }
        },
        workspaceFolder: () => vscode.workspace.workspaceFolders?.[0]?.uri.fsPath,
        pick: async (choices) => {
            const item = await vscode.window.showQuickPick(
                choices.map((c) => ({ label: c.label, page: c.page })),
                { placeHolder: 'Which page of the guide?' },
            );
            return item ? { page: item.page, label: item.label } : undefined;
        },
        async showMarkdown(text) {
            const doc = await vscode.workspace.openTextDocument({ language: 'markdown', content: text });
            await vscode.commands.executeCommand('markdown.showPreview', doc.uri);
        },
        confirm: async (message, action) => (await vscode.window.showWarningMessage(message, { modal: true }, action)) === action,
        report(message, level) {
            const show = level === 'error' ? vscode.window.showErrorMessage : level === 'warning' ? vscode.window.showWarningMessage : vscode.window.showInformationMessage;
            void show(message);
        },
    };
    context.subscriptions.push(
        vscode.commands.registerCommand('loom.openGuide', () => openGuide(host)),
        vscode.commands.registerCommand('loom.installSkill', () => installSkill(host)),
    );
}
