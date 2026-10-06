import * as path from 'path';
import * as vscode from 'vscode';
import { CheckDiagnostic } from '../check/model';
import { CheckDiagnosticsController, DiagnosticsHost } from '../check/controller';
import { WeaveCheckRunner } from '../check/runner';
import { samePath } from '../graph/paths';
import { WeaveGraphRunner } from '../graph/runner';
import { readGraphSettings, TimeoutScheduler } from '../views/GraphPanel';

/** The editor side of the controller: problems go to the Problems panel and the squiggles, under the source "weave check". */
class VsCodeDiagnosticsHost implements DiagnosticsHost {
    constructor(private readonly collection: vscode.DiagnosticCollection) {}

    set(file: string, diagnostics: CheckDiagnostic[]): void {
        const document = vscode.workspace.textDocuments.find((d) => samePath(d.uri.fsPath, file));
        const shown = diagnostics.map((d) => {
            const lastLine = document ? Math.max(0, document.lineCount - 1) : Number.MAX_SAFE_INTEGER;
            const line = Math.min(Math.max(d.line - 1, 0), lastLine);
            const range = document ? document.lineAt(line).range : new vscode.Range(line, 0, line, Number.MAX_SAFE_INTEGER);
            const item = new vscode.Diagnostic(range, d.message, d.severity === 'error' ? vscode.DiagnosticSeverity.Error : vscode.DiagnosticSeverity.Warning);
            item.source = 'weave check';
            return item;
        });
        this.collection.set(vscode.Uri.file(file), shown);
    }

    clear(file: string): void {
        this.collection.delete(vscode.Uri.file(file));
    }

    notify(message: string): void {
        void vscode.window.showWarningMessage(message);
    }
}

function isLoom(document: vscode.TextDocument): boolean {
    return document.uri.scheme === 'file' && document.uri.fsPath.endsWith('.loom');
}

/**
 * Checks .loom files with `weave check` (the language's own parser) when they are opened and saved, and shows what it finds as problems.
 * It reads the saved file, so unsaved edits are checked at the next save.
 */
export function registerCheckDiagnostics(context: vscode.ExtensionContext): void {
    const collection = vscode.languages.createDiagnosticCollection('loom');
    const settings = readGraphSettings();
    const runner = new WeaveCheckRunner(
        new WeaveGraphRunner({
            javaPath: settings.javaPath,
            jarPath: context.asAbsolutePath(path.join('bin', 'weave.jar')),
            timeoutMs: settings.timeoutMs,
        }),
    );
    const controller = new CheckDiagnosticsController({ runner, host: new VsCodeDiagnosticsHost(collection), scheduler: new TimeoutScheduler() });
    context.subscriptions.push(
        collection,
        { dispose: () => controller.dispose() },
        vscode.workspace.onDidOpenTextDocument((d) => isLoom(d) && controller.request(d.uri.fsPath)),
        vscode.workspace.onDidSaveTextDocument((d) => isLoom(d) && controller.request(d.uri.fsPath)),
        vscode.workspace.onDidCloseTextDocument((d) => isLoom(d) && controller.closed(d.uri.fsPath)),
    );
    vscode.workspace.textDocuments.filter(isLoom).forEach((d) => controller.request(d.uri.fsPath));
}
