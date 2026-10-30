import { randomBytes } from 'crypto';
import * as path from 'path';
import * as vscode from 'vscode';
import { GraphPanelController, PanelHost, Scheduler, TimerHandle } from '../graph/controller';
import { newNonce, renderPanelHtml } from '../graph/html';
import { HostMessage } from '../graph/protocol';
import { WeaveGraphRunner } from '../graph/runner';
import { EngineGraphRunner } from '../engine/routing';
import { configuredJavaPath, engineHost } from '../engine/vscodeHost';
import { samePath } from '../graph/paths';
import { TimeoutScheduler } from '../graph/timeoutScheduler';
import { GraphAbortedError, GraphExitError, GraphTimeoutError, RuntimeMissingError } from '../graph/errors';

/** Settings of the graph panel (package.json contributes.configuration). */
export function readGraphSettings(): { javaPath: string; timeoutMs: number; autoRefresh: boolean } {
    const config = vscode.workspace.getConfiguration('loom.graph');
    return {
        javaPath: configuredJavaPath(),
        timeoutMs: config.get<number>('timeoutMs', 30000),
        autoRefresh: config.get<boolean>('autoRefresh', true),
    };
}

/** The text of a script, from the open editor when it has unsaved changes there, otherwise from disk. */
async function readSource(file: string): Promise<string | undefined> {
    const uri = vscode.Uri.file(file);
    const open = vscode.workspace.textDocuments.find((d) => d.uri.fsPath === uri.fsPath);
    if (open) {
        return open.getText();
    }
    return Buffer.from(await vscode.workspace.fs.readFile(uri)).toString('utf8');
}

export { TimeoutScheduler };

/** The editor side of a panel: what the controller asks for, done with the VS Code API. */
class VsCodeHost implements PanelHost {
    constructor(private readonly panel: vscode.WebviewPanel, private readonly sourceColumn: vscode.ViewColumn, private readonly entry: string) {}

    post(message: HostMessage): void {
        void this.panel.webview.postMessage(message);
    }

    openSource(file: string, line: number, beside: boolean): void {
        const position = new vscode.Position(Math.max(0, line - 1), 0);
        void vscode.window.showTextDocument(vscode.Uri.file(file), {
            viewColumn: beside ? this.sourceColumn : vscode.ViewColumn.Active,
            selection: new vscode.Range(position, position),
            preserveFocus: beside,
        });
    }

    report(message: string, kind: 'error' | 'warning' | 'info'): void {
        const show = kind === 'error' ? vscode.window.showErrorMessage : kind === 'warning' ? vscode.window.showWarningMessage : vscode.window.showInformationMessage;
        void show(message);
    }

    copy(text: string): Promise<void> {
        return Promise.resolve(vscode.env.clipboard.writeText(text));
    }

    async savePng(name: string, bytes: Uint8Array): Promise<string | undefined> {
        const target = await vscode.window.showSaveDialog({
            defaultUri: vscode.Uri.file(path.join(path.dirname(this.entry), name)),
            filters: { 'PNG image': ['png'] },
            saveLabel: 'Save picture',
        });
        if (!target) {
            return undefined;
        }
        await vscode.workspace.fs.writeFile(target, bytes);
        return path.basename(target.fsPath);
    }
}

/** One panel per script; opening the command again for the same script shows that panel. */
export class GraphPanel {
    private static readonly open = new Map<string, GraphPanel>();

    private constructor(private readonly panel: vscode.WebviewPanel, private readonly controller: GraphPanelController, readonly entry: string) {}

    /** Shows the panel for `entry`, creating it when needed. Reports problems itself; never opens an empty panel on a missing runtime. */
    static async show(context: vscode.ExtensionContext, entry: string, cursor: { file: string; line: number }, sourceColumn: vscode.ViewColumn): Promise<void> {
        for (const [key, existing] of GraphPanel.open) {
            if (samePath(key, entry)) {
                existing.panel.reveal(vscode.ViewColumn.Beside, true);
                existing.controller.refresh();
                return;
            }
        }
        const settings = readGraphSettings();
        const runner = new WeaveGraphRunner({
            javaPath: settings.javaPath,
            jarPath: context.asAbsolutePath(path.join('bin', 'weave.jar')),
            timeoutMs: settings.timeoutMs,
        });
        try {
            await runner.verifyRuntime();
        } catch (e) {
            await GraphPanel.explain(e as Error);
            return;
        }

        const media = vscode.Uri.joinPath(context.extensionUri, 'media');
        const panel = vscode.window.createWebviewPanel(
            'loomGraph',
            `Loom Graph: ${path.basename(entry)}`,
            { viewColumn: vscode.ViewColumn.Beside, preserveFocus: true },
            { enableScripts: true, localResourceRoots: [media], retainContextWhenHidden: true },
        );
        panel.iconPath = vscode.Uri.joinPath(media, 'loom-mark-128.png');
        panel.webview.html = renderPanelHtml({
            cspSource: panel.webview.cspSource,
            nonce: newNonce(randomBytes),
            uri: (name) => panel.webview.asWebviewUri(vscode.Uri.joinPath(media, name)).toString(),
        });

        const controller = new GraphPanelController({
            entry,
            cursor,
            runner: engineHost() ? new EngineGraphRunner(engineHost()!, runner) : runner,
            host: new VsCodeHost(panel, sourceColumn, entry),
            readText: readSource,
            scheduler: new TimeoutScheduler(),
            groupSets: () => vscode.workspace.getConfiguration('loom.graph').get<boolean>('groupSets', true),
        });
        const graphPanel = new GraphPanel(panel, controller, entry);
        GraphPanel.open.set(entry, graphPanel);
        graphPanel.wire(settings.autoRefresh);

        const delay = Number(process.env.LOOM_GRAPH_DELAY_MS ?? 0); // lets a person see the loading state
        if (delay > 0) {
            await new Promise((resolve) => setTimeout(resolve, delay));
        }
        const outcome = await controller.load();
        if (!outcome.ok) {
            panel.dispose();
            if (!(outcome.error instanceof GraphAbortedError)) {
                await GraphPanel.explain(outcome.error); // an aborted load means the panel was closed: nothing to say
            }
        }
    }

    private wire(autoRefresh: boolean): void {
        const subscriptions: vscode.Disposable[] = [
            this.panel.webview.onDidReceiveMessage((raw) => this.controller.handle(raw)),
            vscode.window.onDidChangeTextEditorSelection((event) => {
                const active = event.selections[0]?.active;
                if (active) {
                    this.controller.cursorMoved(event.textEditor.document.uri.fsPath, active.line + 1);
                }
            }),
        ];
        if (autoRefresh) {
            subscriptions.push(vscode.workspace.onDidSaveTextDocument((document) => this.controller.fileSaved(document.uri.fsPath)));
        }
        this.panel.onDidDispose(() => {
            this.controller.dispose();
            subscriptions.forEach((s) => s.dispose());
            GraphPanel.open.delete(this.entry);
        });
    }

    /** Says what went wrong and offers the fix that fits. */
    private static async explain(error: Error): Promise<void> {
        if (error instanceof RuntimeMissingError && error.what === 'java') {
            const choice = await vscode.window.showErrorMessage(`Loom: ${error.message}`, 'Open Settings');
            if (choice === 'Open Settings') {
                await vscode.commands.executeCommand('workbench.action.openSettings', 'loom.graph.javaPath');
            }
            return;
        }
        if (error instanceof GraphExitError) {
            const where = error.location();
            const choice = await vscode.window.showErrorMessage(`Loom: ${error.message}`, ...(where ? ['Go to error'] : []));
            if (where && choice === 'Go to error') {
                const position = new vscode.Position(Math.max(0, where.line - 1), 0);
                await vscode.window.showTextDocument(vscode.Uri.file(where.file), { selection: new vscode.Range(position, position) });
            }
            return;
        }
        if (error instanceof GraphTimeoutError || error instanceof RuntimeMissingError) {
            await vscode.window.showErrorMessage(`Loom: ${error.message}`);
            return;
        }
        await vscode.window.showErrorMessage(`Loom: the workflow graph could not be shown. ${error.message}`);
    }
}
