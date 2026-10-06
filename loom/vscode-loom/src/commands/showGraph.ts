import * as vscode from 'vscode';
import { GraphPanel } from '../views/GraphPanel';

/**
 * Command handler for "loom.showGraph": opens the workflow graph of the active .loom file beside the editor.
 * The graph is built by `weave graph` (the same parser the harness runs), so it always matches the real grammar.
 */
export async function showGraphCommand(context: vscode.ExtensionContext): Promise<void> {
    const editor = vscode.window.activeTextEditor;
    if (!editor || !editor.document.uri.fsPath.endsWith('.loom')) {
        vscode.window.showErrorMessage('Loom: open a .loom file to show its workflow graph.');
        return;
    }
    await GraphPanel.show(
        context,
        editor.document.uri.fsPath,
        { file: editor.document.uri.fsPath, line: editor.selection.active.line + 1 },
        editor.viewColumn ?? vscode.ViewColumn.One,
    );
}
