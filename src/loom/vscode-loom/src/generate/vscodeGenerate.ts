import { ChildProcess, spawn } from 'child_process';
import * as fs from 'fs';
import * as os from 'os';
import * as path from 'path';
import * as vscode from 'vscode';
import { configuredJavaPath } from '../engine/vscodeHost';
import {
    applyArgs,
    buildArgs,
    editArgs,
    EventReader,
    exitMeaning,
    folderFor,
    GenerateEvent,
    GenerateSettings,
    isWorse,
    patchedFiles,
    planArgs,
    progressText,
    questionIn,
    requestWith,
    resultOf,
} from './generateFlow';

function settings(): GenerateSettings {
    const c = vscode.workspace.getConfiguration('loom.generate');
    return { model: c.get<string>('model', ''), maxCost: c.get<number>('maxCost', 0), maxTokens: c.get<number>('maxTokens', 0), prices: c.get<string>('prices', '') };
}

/**
 * Generate Workflow... and Change Workflow...: the person describes, reads the plan or the diff, and decides; `weave generate` (its own
 * process, with the person's key) does the work. All the choices about the command lines and the words are in generateFlow.ts.
 */
export function registerGenerate(context: vscode.ExtensionContext): void {
    const jar = context.asAbsolutePath(path.join('bin', 'weave.jar'));
    const output = vscode.window.createOutputChannel('Loom Generate');

    async function run(args: string[], title: string, cwd: string): Promise<{ events: GenerateEvent[]; exit: number } | undefined> {
        if (!fs.existsSync(jar)) {
            void vscode.window.showErrorMessage('Loom: the engine (bin/weave.jar) is missing from the extension, so nothing can be generated.');
            return undefined;
        }
        return vscode.window.withProgress({ location: vscode.ProgressLocation.Notification, title, cancellable: true }, (progress, token) => {
            return new Promise((resolve) => {
                const child: ChildProcess = spawn(configuredJavaPath(), ['-jar', jar, ...args], { cwd, stdio: ['ignore', 'pipe', 'pipe'] });
                const reader = new EventReader();
                const events: GenerateEvent[] = [];
                token.onCancellationRequested(() => child.kill());
                child.stdout?.on('data', (chunk: Buffer) => {
                    for (const e of reader.push(chunk.toString('utf8'))) {
                        events.push(e);
                        const words = progressText(e);
                        if (words) {
                            progress.report({ message: words });
                        }
                    }
                });
                child.stderr?.on('data', (chunk: Buffer) => output.append(chunk.toString('utf8')));
                child.once('error', (error) => {
                    output.appendLine(`Could not start Java: ${error.message}`);
                    resolve({ events, exit: 2 });
                });
                child.once('exit', (code) => resolve({ events, exit: code ?? 2 }));
            });
        });
    }

    function report(result: { events: GenerateEvent[]; exit: number }): string {
        const r = resultOf(result.events);
        const text = r?.text ?? '';
        output.appendLine(text || `weave generate: ${exitMeaning(result.exit)}`);
        return text;
    }

    const generate = async (): Promise<void> => {
        const description = await vscode.window.showInputBox({ title: 'Generate Workflow', prompt: 'What should the workflow do?', ignoreFocusOut: true });
        if (!description?.trim()) {
            return;
        }
        const folder = vscode.workspace.workspaceFolders?.[0]?.uri.fsPath ?? os.homedir();
        const planDir = fs.mkdtempSync(path.join(os.tmpdir(), 'loom-plan-'));
        const planned = await run(planArgs(description, planDir, settings()), 'Loom: planning…', folder);
        if (!planned) {
            return;
        }
        report(planned);
        const planFile = path.join(planDir, 'plan.json');
        if (!fs.existsSync(planFile)) {
            output.show(true);
            void vscode.window.showErrorMessage(`Loom: no plan was made (${exitMeaning(planned.exit)}). See the Loom Generate output.`);
            return;
        }
        // the plan opens as a document to read and edit; nothing is written until the person says Generate
        await vscode.window.showTextDocument(vscode.Uri.file(path.join(planDir, 'PLAN.md')), { preview: false });
        await vscode.window.showTextDocument(vscode.Uri.file(planFile), { preview: false, viewColumn: vscode.ViewColumn.Beside });
        const go = 'Generate';
        const choice = await vscode.window.showInformationMessage(planned.exit === 0 ? 'Read the plan, edit plan.json if you want, then Generate.' : 'The plan has problems (see the output); edit plan.json, then Generate.', go, 'Cancel');
        if (choice !== go) {
            return;
        }
        await vscode.workspace.saveAll(false);
        const plan = JSON.parse(fs.readFileSync(planFile, 'utf8')) as { name?: string };
        const target = path.join(folder, folderFor(plan.name ?? 'workflow'));
        const built = await run(buildArgs(planFile, target, settings()), 'Loom: generating…', folder);
        if (!built) {
            return;
        }
        const text = report(built);
        if (built.exit !== 0) {
            output.show(true);
            void vscode.window.showErrorMessage(`Loom: ${exitMeaning(built.exit)}. The gates and the reason are in the Loom Generate output.`);
            return;
        }
        const script = [path.join(target, 'main.loom'), path.join(target, 'src', 'main', 'resources', 'main.loom')].find((f) => fs.existsSync(f));
        if (script) {
            await vscode.window.showTextDocument(vscode.Uri.file(script));
            void vscode.commands.executeCommand('loom.review', script);
        }
        void vscode.window.showInformationMessage(`Loom: made ${path.basename(target)}. ${text.includes('Not checked') ? 'See what was not checked in the output.' : ''}`);
    };

    const change = async (): Promise<void> => {
        const editor = vscode.window.activeTextEditor;
        if (!editor || !editor.document.uri.fsPath.endsWith('.loom')) {
            void vscode.window.showErrorMessage('Loom: open the .loom script you want to change.');
            return;
        }
        await editor.document.save();
        const script = editor.document.uri.fsPath;
        const selection = editor.selection.isEmpty ? undefined : editor.document.getText(editor.selection);
        const request = await vscode.window.showInputBox({ title: 'Change Workflow', prompt: selection ? 'What should change in the selected part?' : 'What should change?', ignoreFocusOut: true });
        if (!request?.trim()) {
            return;
        }
        const patchFile = path.join(fs.mkdtempSync(path.join(os.tmpdir(), 'loom-edit-')), 'patch.json');
        const folder = vscode.workspace.getWorkspaceFolder(editor.document.uri)?.uri.fsPath ?? path.dirname(script);
        let answer: string | undefined;
        for (;;) {
            const asked = requestWith(request, selection) + (answer ? `\n(Answer to your question: ${answer})` : '');
            const made = await run(editArgs(script, asked, patchFile, settings()), 'Loom: working out the change…', folder);
            if (!made) {
                return;
            }
            const text = report(made);
            const q = questionIn(text);
            if (q) {
                answer = await vscode.window.showInputBox({ title: 'Change Workflow', prompt: q, ignoreFocusOut: true });
                if (!answer?.trim()) {
                    return;
                }
                continue;
            }
            if (made.exit !== 0 || !fs.existsSync(patchFile)) {
                output.show(true);
                void vscode.window.showErrorMessage(`Loom: no change was made (${exitMeaning(made.exit)}). See the Loom Generate output.`);
                return;
            }
            const files = patchedFiles(fs.readFileSync(patchFile, 'utf8')) ?? {};
            const root = path.dirname(patchFile);
            for (const [name, content] of Object.entries(files)) {
                const after = path.join(root, 'after', name);
                fs.mkdirSync(path.dirname(after), { recursive: true });
                fs.writeFileSync(after, content, 'utf8');
            }
            // the changed script beside the original, as an editor's diff
            const afterScript = path.join(root, 'after', 'main.loom');
            if (fs.existsSync(afterScript)) {
                await vscode.commands.executeCommand('vscode.diff', vscode.Uri.file(script), vscode.Uri.file(afterScript), 'Loom: the change (right) against your script (left)');
            }
            const worse = isWorse(text);
            const apply = worse ? 'Apply anyway (it is worse)' : 'Apply';
            const choice = await vscode.window.showInformationMessage(
                `${worse ? 'This change makes the review or the cost worse. ' : ''}Changed: ${Object.keys(files).join(', ')}.`,
                { modal: worse },
                apply,
                'Cancel',
            );
            if (choice !== apply) {
                return;
            }
            const applied = await run(applyArgs(script, patchFile, worse), 'Loom: applying…', folder);
            if (applied && applied.exit === 0) {
                void vscode.window.showInformationMessage('Loom: the change is applied. Ctrl+Z in the editor undoes it, and your version control has the rest.');
            } else {
                output.show(true);
                void vscode.window.showErrorMessage('Loom: the change could not be applied. See the Loom Generate output.');
            }
            return;
        }
    };

    context.subscriptions.push(output, vscode.commands.registerCommand('loom.generate', generate), vscode.commands.registerCommand('loom.change', change));
}
