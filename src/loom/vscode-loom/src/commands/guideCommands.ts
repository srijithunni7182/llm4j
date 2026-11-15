import * as path from 'path';
import * as vscode from 'vscode';
import { AGENTS, GuideHost, installSkill, openGuide, openTutorial } from '../guide/guideCommands';
import { GraphExitError } from '../graph/errors';
import { WeaveGraphRunner } from '../graph/runner';
import { handOffToAgent, syncAgentFiles } from '../guide/agentSkills';
import { vscodeHandoffHost } from './onboardingCommands';
import { readGraphSettings } from '../views/GraphPanel';

/** "Loom: Open Guide" and : both just run `weave guide` from the bundled jar. */
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
        pickAgents: async () => {
            const items = await vscode.window.showQuickPick(
                AGENTS.map((a) => ({ label: a.label, description: a.detail, picked: a.picked, id: a.id })),
                { canPickMany: true, placeHolder: 'Which coding agents do you use in this project?' },
            );
            return items?.map((i) => i.id);
        },
        confirm: async (message, action) => (await vscode.window.showWarningMessage(message, { modal: true }, action)) === action,
        async afterSetUp(folder) {
            const installed = await syncAgentFiles(folder, (args) => host.weave(args));
            await handOffToAgent(vscodeHandoffHost(), installed);
        },
        report(message, level) {
            const show = level === 'error' ? vscode.window.showErrorMessage : level === 'warning' ? vscode.window.showWarningMessage : vscode.window.showInformationMessage;
            void show(message);
        },
    };
    context.subscriptions.push(
        vscode.commands.registerCommand('loom.openGuide', () => openGuide(host)),
        vscode.commands.registerCommand('loom.installSkill', () => installSkill(host)),
        vscode.commands.registerCommand('loom.openTutorial', () =>
            openTutorial({
                weave: host.weave,
                tutorialFile: () => path.join(context.globalStorageUri.fsPath, 'loom-tutorial.html'),
                openInBrowser: async (file) => Promise.resolve(vscode.env.openExternal(vscode.Uri.file(file))),
                report: host.report,
            }),
        ),
    );
}
