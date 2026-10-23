import * as path from 'path';
import * as vscode from 'vscode';
import { engineHost, configuredJavaPath } from '../engine/vscodeHost';
import { samePath } from '../graph/paths';
import { WeaveGraphRunner } from '../graph/runner';
import { TimeoutScheduler } from '../graph/timeoutScheduler';
import { ReviewController, ReviewSettings, ReviewState } from './controller';
import { atLeast, Severity } from './model';
import { codeLensFor, diagnosticsFor, GraphWorkflow, statusFor } from './presentation';
import { RunNotice } from './runNotice';
import { ReviewPanel } from './ReviewPanel';
import { EngineReviewSource, ProcessReviewSource } from './source';

function isLoom(document: vscode.TextDocument): boolean {
    return document.uri.scheme === 'file' && document.uri.fsPath.endsWith('.loom');
}

function readSettings(): ReviewSettings {
    const c = vscode.workspace.getConfiguration('loom.review');
    const on = c.get<string>('on', 'save');
    const lenses = c.get<string>('lenses', '').trim();
    return { on: on === 'type' || on === 'manual' ? on : 'save', lenses: lenses || undefined, minSeverity: undefined };
}

function minSeverity(): Severity {
    const v = vscode.workspace.getConfiguration('loom.review').get<string>('minSeverity', 'low');
    return v === 'high' || v === 'medium' || v === 'info' ? v : 'low';
}

let controller: ReviewController | undefined;
let notice: RunNotice | undefined;

/** The window's review controller, once {@link registerReview} has run. */
export function reviewController(): ReviewController | undefined {
    return controller;
}

/**
 * After a run has started: if the script has open findings and the person has not been told about this version of it, say so once,
 * quietly. This never asks anything and never delays the run, which has already begun when it is called (spec 10, R42).
 */
export function offerReviewAfterRun(file: string, text: string): void {
    const state = controller?.state(file);
    const open = state?.review?.findings.length ?? 0;
    if (!notice || !notice.shouldOffer(file, text, open)) {
        return;
    }
    void vscode.window
        .showInformationMessage(`Loom: ${open} open ${open === 1 ? 'finding' : 'findings'} in ${path.basename(file)}. The run was not held up.`, 'Review', "Don't show again for this workspace")
        .then((choice) => {
            if (choice === 'Review') {
                void vscode.commands.executeCommand('loom.review', file);
            } else if (choice) {
                notice?.mute();
            }
        });
}

/**
 * Wires the review into the editor: squiggles and Problems entries (source loom-review), the status-bar count, the CodeLens above each
 * workflow, the Review panel, and the commands. The review itself comes from the controller, which asks the engine and falls back to
 * running weave. Nothing here gates anything: Run, Graph and every other command work whatever the review says.
 */
export function registerReview(context: vscode.ExtensionContext): ReviewController {
    const scheduler = new TimeoutScheduler();
    const weave = new WeaveGraphRunner({
        javaPath: configuredJavaPath(),
        jarPath: context.asAbsolutePath(path.join('bin', 'weave.jar')),
        timeoutMs: 60_000,
    });
    const source = new EngineReviewSource(() => engineHost()?.engine(), new ProcessReviewSource(weave));
    const ctl = new ReviewController({
        source,
        scheduler,
        settings: readSettings,
        unsavedText: (file) => vscode.workspace.textDocuments.find((d) => d.isDirty && samePath(d.uri.fsPath, file))?.getText(),
    });
    controller = ctl;
    notice = new RunNotice({ get: (k) => context.workspaceState.get(k), update: (k, v) => context.workspaceState.update(k, v) });

    const diagnostics = vscode.languages.createDiagnosticCollection('loom-review');
    const status = vscode.window.createStatusBarItem(vscode.StatusBarAlignment.Right, 100);
    status.command = 'loom.review';
    const lensChanged = new vscode.EventEmitter<void>();

    const showDiagnostics = (state: ReviewState): void => {
        const doc = vscode.workspace.textDocuments.find((d) => samePath(d.uri.fsPath, state.file));
        if (!doc) {
            return;
        }
        const specs = diagnosticsFor(state.review, state.file, minSeverity(), doc.lineCount);
        diagnostics.set(
            doc.uri,
            specs.map((s) => {
                const range = new vscode.Range(s.line, s.column, s.endLine, Math.min(s.endColumn, doc.lineAt(s.endLine).text.length));
                const level = s.level === 'warning' ? vscode.DiagnosticSeverity.Warning : s.level === 'information' ? vscode.DiagnosticSeverity.Information : vscode.DiagnosticSeverity.Hint;
                const d = new vscode.Diagnostic(range, s.message, level);
                d.source = s.source;
                d.code = s.code;
                return d;
            }),
        );
    };

    const showStatus = (): void => {
        const editor = vscode.window.activeTextEditor;
        const enabled = vscode.workspace.getConfiguration('loom.review').get<boolean>('statusBar', true);
        if (!enabled || !editor || !isLoom(editor.document)) {
            status.hide();
            return;
        }
        const state = ctl.state(editor.document.uri.fsPath);
        const failOn = vscode.workspace.getConfiguration('loom.review').get<string>('failOn', 'high');
        const spec = statusFor(state.review, { failOn: failOn === 'none' || failOn === 'medium' || failOn === 'low' || failOn === 'info' ? (failOn as Severity | 'none') : 'high', dismissed: 0, stale: state.status === 'stale' });
        status.text = spec.text;
        status.tooltip = spec.tooltip;
        status.backgroundColor = spec.tone === 'warning' ? new vscode.ThemeColor('statusBarItem.warningBackground') : undefined;
        status.show();
    };

    const lens: vscode.CodeLensProvider = {
        onDidChangeCodeLenses: lensChanged.event,
        provideCodeLenses(document) {
            if (!vscode.workspace.getConfiguration('loom.review').get<boolean>('codeLens', true) || !isLoom(document)) {
                return [];
            }
            const state = ctl.state(document.uri.fsPath);
            const workflows = ((state.graph as { workflows?: GraphWorkflow[] } | undefined)?.workflows ?? []).filter((w) => w && typeof w.line === 'number');
            return codeLensFor(state.review, workflows, document.uri.fsPath).map((s) => {
                const range = new vscode.Range(s.line, 0, s.line, 0);
                return new vscode.CodeLens(range, { title: s.title, command: s.command, arguments: s.args, tooltip: s.tooltip });
            });
        },
    };

    context.subscriptions.push(
        diagnostics,
        status,
        lensChanged,
        { dispose: () => ctl.dispose() },
        ctl.onDidChange((state) => {
            showDiagnostics(state);
            showStatus();
            lensChanged.fire();
        }),
        vscode.languages.registerCodeLensProvider({ language: 'loom' }, lens),
        vscode.window.onDidChangeActiveTextEditor(() => showStatus()),
        vscode.workspace.onDidChangeTextDocument((e) => isLoom(e.document) && e.contentChanges.length > 0 && ctl.changed(e.document.uri.fsPath)),
        vscode.workspace.onDidSaveTextDocument((d) => isLoom(d) && ctl.saved(d.uri.fsPath)),
        vscode.workspace.onDidOpenTextDocument((d) => {
            if (isLoom(d) && readSettings().on !== 'manual') {
                void ctl.request(d.uri.fsPath);
            }
        }),
        vscode.workspace.onDidCloseTextDocument((d) => {
            if (isLoom(d)) {
                ctl.closed(d.uri.fsPath);
                diagnostics.delete(d.uri);
            }
        }),
        vscode.workspace.onDidChangeConfiguration((e) => {
            if (e.affectsConfiguration('loom.review')) {
                vscode.workspace.textDocuments.filter(isLoom).forEach((d) => showDiagnostics(ctl.state(d.uri.fsPath)));
                showStatus();
                lensChanged.fire();
            }
        }),
        vscode.commands.registerCommand('loom.review', (target?: vscode.Uri | string) => {
            const file = typeof target === 'string' ? target : target instanceof vscode.Uri ? target.fsPath : vscode.window.activeTextEditor?.document.uri.fsPath;
            if (!file || !file.endsWith('.loom')) {
                void vscode.window.showErrorMessage('Loom: open a .loom file to review it.');
                return;
            }
            ReviewPanel.show(context, ctl, file, vscode.window.activeTextEditor?.viewColumn ?? vscode.ViewColumn.One);
        }),
        vscode.window.registerWebviewPanelSerializer('loomReview', {
            async deserializeWebviewPanel(panel: vscode.WebviewPanel, state: unknown) {
                const entry = (state as { entry?: unknown } | null)?.entry;
                if (typeof entry === 'string' && entry.endsWith('.loom')) {
                    ReviewPanel.revive(context, ctl, panel, entry);
                } else {
                    panel.dispose();
                }
            },
        }),
    );
    vscode.workspace.textDocuments.filter(isLoom).forEach((d) => readSettings().on !== 'manual' && void ctl.request(d.uri.fsPath));
    showStatus();
    void atLeast;
    return ctl;
}
