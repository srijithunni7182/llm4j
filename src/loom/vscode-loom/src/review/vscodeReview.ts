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
import { PanelTriage, VsCodeEditHost } from './vscodeTriage';
import { actionsFor, quickFixesFor } from './triage';
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
let triage: PanelTriage | undefined;

/** The window's triage actions, once {@link registerReview} has run. */
export function panelTriage(): PanelTriage | undefined {
    return triage;
}

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
    const editHost = new VsCodeEditHost();
    const triageActions = new PanelTriage(ctl, () => engineHost()?.engine(), context.workspaceState, editHost);
    triage = triageActions;

    const diagnostics = vscode.languages.createDiagnosticCollection('loom-review');
    const status = vscode.window.createStatusBarItem(vscode.StatusBarAlignment.Right, 100);
    status.command = 'loom.review';
    const lensChanged = new vscode.EventEmitter<void>();

    const fingerprints = new WeakMap<vscode.Diagnostic, string>();
    const showDiagnostics = (state: ReviewState): void => {
        const doc = vscode.workspace.textDocuments.find((d) => samePath(d.uri.fsPath, state.file));
        if (!doc) {
            return;
        }
        const away = triageActions.dismissals.dismissed(state.file);
        const shown = state.review ? { ...state.review, findings: state.review.findings.filter((f) => !away.has(f.fingerprint)) } : undefined;
        const specs = diagnosticsFor(shown, state.file, minSeverity(), doc.lineCount);
        diagnostics.set(
            doc.uri,
            specs.map((s) => {
                const range = new vscode.Range(s.line, s.column, s.endLine, Math.min(s.endColumn, doc.lineAt(s.endLine).text.length));
                const level = s.level === 'warning' ? vscode.DiagnosticSeverity.Warning : s.level === 'information' ? vscode.DiagnosticSeverity.Information : vscode.DiagnosticSeverity.Hint;
                const d = new vscode.Diagnostic(range, s.message, level);
                d.source = s.source;
                d.code = s.code;
                fingerprints.set(d, s.fingerprint);
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
        const spec = statusFor(state.review, { failOn: failOn === 'none' || failOn === 'medium' || failOn === 'low' || failOn === 'info' ? (failOn as Severity | 'none') : 'high', dismissed: triageActions.dismissals.count(editor.document.uri.fsPath), stale: state.status === 'stale' });
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

    const fileOf = (target?: vscode.Uri | string): string | undefined => {
        const f = typeof target === 'string' ? target : target instanceof vscode.Uri ? target.fsPath : vscode.window.activeTextEditor?.document.uri.fsPath;
        return f && f.endsWith('.loom') ? f : undefined;
    };
    const refreshDiagnostics = (file: string): void => {
        showDiagnostics(ctl.state(file));
        showStatus();
    };
    // outside the panel an outcome is a quiet message; a change that can be undone says how
    const reportOutcome = (outcome: import('./triage').Outcome, file: string): void => {
        if (outcome.ok) {
            void vscode.window.showInformationMessage(`Loom: ${outcome.message}${outcome.undo ? '. Ctrl+Z in the editor undoes it.' : ''}`);
        } else {
            void vscode.window.showWarningMessage(`Loom: ${outcome.message}`);
        }
        if (outcome.rerun) {
            void ctl.request(file);
        }
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
        editHost.register(),
        vscode.languages.registerCodeActionsProvider(
            { language: 'loom' },
            {
                provideCodeActions(document, _range, codeActionContext) {
                    const out: vscode.CodeAction[] = [];
                    const review = ctl.state(document.uri.fsPath).review;
                    for (const d of codeActionContext.diagnostics) {
                        const fp = fingerprints.get(d);
                        const f = fp ? review?.findings.find((x) => x.fingerprint === fp) : undefined;
                        if (!f) {
                            continue;
                        }
                        for (const q of quickFixesFor(document.uri.fsPath, f)) {
                            const action = new vscode.CodeAction(q.title, vscode.CodeActionKind.QuickFix);
                            action.command = { title: q.title, command: q.command, arguments: q.args };
                            action.diagnostics = [d];
                            action.isPreferred = q.preferred;
                            out.push(action);
                        }
                    }
                    return out;
                },
            },
            { providedCodeActionKinds: [vscode.CodeActionKind.QuickFix] },
        ),
        vscode.commands.registerCommand('loom.review.fixAll', async (target?: vscode.Uri | string) => {
            const file = fileOf(target);
            const review = file ? ctl.state(file).review : undefined;
            if (!file || !review) {
                void vscode.window.showInformationMessage('Loom: open a .loom script and let it be reviewed first.');
                return;
            }
            const away = triageActions.dismissals.dismissed(file);
            const outcome = await triageActions.actions.fixAllSafe(file, review.findings.filter((f) => !away.has(f.fingerprint)));
            reportOutcome(outcome, file);
        }),
        vscode.commands.registerCommand('loom.review.fixFinding', async (file: string, fingerprint: string) => {
            const f = ctl.state(file).review?.findings.find((x) => x.fingerprint === fingerprint);
            reportOutcome(f ? await triageActions.actions.fixOne(file, f) : { ok: false, message: 'That finding is no longer in the review.', rerun: true }, file);
        }),
        vscode.commands.registerCommand('loom.review.explain', async (rule?: string) => {
            const chosen = rule ?? (await vscode.window.showInputBox({ prompt: 'Rule id to explain', placeHolder: 'LA01' }));
            if (chosen) {
                await triageActions.explain(chosen.trim().toUpperCase());
            }
        }),
        vscode.commands.registerCommand('loom.review.accept', async (file?: string, fingerprint?: string) => {
            const target = file ?? vscode.window.activeTextEditor?.document.uri.fsPath;
            const f = target ? ctl.state(target).review?.findings.find((x) => x.fingerprint === fingerprint) : undefined;
            if (!target || !f) {
                void vscode.window.showInformationMessage('Loom: choose a finding in the Review panel, or use the lightbulb on one.');
                return;
            }
            if (!actionsFor(f).accept.allowed) {
                void vscode.window.showWarningMessage(`Loom: ${actionsFor(f).accept.reason}`);
                return;
            }
            const scope = await vscode.window.showQuickPick(
                [
                    { label: 'Just this finding', value: 'finding' as const },
                    { label: `${f.rule} anywhere in this script`, value: 'script' as const },
                    { label: `${f.rule} anywhere in the project`, value: 'project' as const },
                ],
                { placeHolder: 'What should the accept cover?' },
            );
            if (!scope) {
                return;
            }
            const reason = await vscode.window.showInputBox({ prompt: `Why is ${f.rule} fine here? (kept in review.properties)`, validateInput: (v) => (v.trim() === '' ? 'Say why' : v.includes('"') ? 'No double quotes; use single quotes' : undefined) });
            if (!reason) {
                return;
            }
            reportOutcome(await triageActions.actions.accept(target, { ids: [f.fingerprint], scope: scope.value, reason: reason.trim() }), target);
        }),
        vscode.commands.registerCommand('loom.review.dismiss', (file?: string, fingerprint?: string) => {
            const target = file ?? vscode.window.activeTextEditor?.document.uri.fsPath;
            const f = target ? ctl.state(target).review?.findings.find((x) => x.fingerprint === fingerprint) : undefined;
            if (target && f) {
                reportOutcome(triageActions.actions.dismiss(target, [f]), target);
                refreshDiagnostics(target);
            }
        }),
        vscode.commands.registerCommand('loom.review.restore', (target?: vscode.Uri | string) => {
            const file = fileOf(target);
            if (file) {
                reportOutcome(triageActions.actions.restoreAll(file), file);
                refreshDiagnostics(file);
            }
        }),
        vscode.commands.registerCommand('loom.review.baseline', async (target?: vscode.Uri | string) => {
            const file = fileOf(target);
            if (file) {
                reportOutcome(await triageActions.actions.baseline(file, false), file);
            }
        }),
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
            ReviewPanel.show(context, ctl, triageActions, file, vscode.window.activeTextEditor?.viewColumn ?? vscode.ViewColumn.One);
        }),
        vscode.window.registerWebviewPanelSerializer('loomReview', {
            async deserializeWebviewPanel(panel: vscode.WebviewPanel, state: unknown) {
                const entry = (state as { entry?: unknown } | null)?.entry;
                if (typeof entry === 'string' && entry.endsWith('.loom')) {
                    ReviewPanel.revive(context, ctl, triageActions, panel, entry);
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
