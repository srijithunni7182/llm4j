import * as path from 'path';
import * as vscode from 'vscode';
import { shellKind, terminalCommand } from '../welcome/welcome';
import { DEPLOY_TARGETS, DeployTarget, EVAL_MODES, EvalMode, Plan, chooseScript, deployPlan, evalPlan, manPlan, nextPlan, observePlan, testPlan } from './tools';

/** Test, evaluate, run with the live page, write deployment files, what next, the command reference: one `weave` command each, typed into an integrated terminal so the output stays on screen; the extension itself starts no process for them. */
export function registerTools(context: vscode.ExtensionContext): void {
    const jar = context.asAbsolutePath(path.join('bin', 'weave.jar'));
    const java = (): string => vscode.workspace.getConfiguration('loom.graph').get<string>('javaPath', 'java');
    const folder = (): string | undefined => vscode.workspace.workspaceFolders?.[0]?.uri.fsPath;

    const type = (plan: Plan): void => {
        const terminal = vscode.window.createTerminal({ name: plan.name, cwd: folder() });
        terminal.show();
        terminal.sendText(terminalCommand(java(), jar, plan.args, shellKind(vscode.env.shell, process.platform)));
    };

    const script = async (): Promise<string | undefined> => {
        const editor = vscode.window.activeTextEditor;
        const active = editor && editor.document.languageId === 'loom' && editor.document.uri.scheme === 'file' ? editor.document.uri.fsPath : undefined;
        const found = active ? [] : (await vscode.workspace.findFiles('**/*.loom', '**/{node_modules,target,out}/**', 50)).map((u) => u.fsPath);
        const chosen = chooseScript(active, found);
        if (chosen.script) return chosen.script;
        if (chosen.pick) {
            const root = folder() ?? '';
            const item = await vscode.window.showQuickPick(
                chosen.pick.map((p) => ({ label: path.relative(root, p) || p, path: p })),
                { placeHolder: 'Which workflow?' },
            );
            return item?.path;
        }
        void vscode.window.showInformationMessage('Open a .loom file first, or generate one: Loom: Generate Workflow...');
        return undefined;
    };

    context.subscriptions.push(
        vscode.commands.registerCommand('loom.test', async () => {
            const s = await script();
            if (s) type(testPlan(s));
        }),
        vscode.commands.registerCommand('loom.eval', async () => {
            const s = await script();
            if (!s) return;
            const item = await vscode.window.showQuickPick(
                EVAL_MODES.map((m) => ({ label: m.label, description: m.detail, mode: m.mode as EvalMode })),
                { placeHolder: 'Evaluate this workflow' },
            );
            if (item) type(evalPlan(s, item.mode));
        }),
        vscode.commands.registerCommand('loom.observe', async () => {
            const s = await script();
            if (!s) return;
            const plan = observePlan(s, vscode.workspace.getConfiguration('loom.observe').get<number>('maxTokens', 50000));
            if ((await vscode.window.showWarningMessage(plan.confirm ?? '', { modal: true }, 'Run')) === 'Run') type(plan);
        }),
        vscode.commands.registerCommand('loom.deploy', async () => {
            const s = await script();
            if (!s) return;
            const item = await vscode.window.showQuickPick(
                DEPLOY_TARGETS.map((t) => ({ label: t.label, description: t.detail, target: t.target as DeployTarget })),
                { placeHolder: 'Write deployment files for' },
            );
            if (item) type(deployPlan(s, item.target));
        }),
        vscode.commands.registerCommand('loom.next', () => {
            const f = folder();
            if (!f) {
                void vscode.window.showInformationMessage('Open a folder first.');
                return;
            }
            type(nextPlan(f));
        }),
        vscode.commands.registerCommand('loom.commands', () => type(manPlan())),
    );
}
