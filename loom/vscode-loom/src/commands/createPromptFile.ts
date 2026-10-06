import * as fs from 'fs';
import * as path from 'path';
import * as vscode from 'vscode';
import { createPromptFile, CreatePromptHost } from '../prompts/createPromptFile';

/** "Loom: Create Prompt File": makes the markdown file for a `prompt: "…"` the script names and does not have yet. */
export async function createPromptFileCommand(): Promise<void> {
    const host: CreatePromptHost = {
        activeScript() {
            const editor = vscode.window.activeTextEditor;
            return editor
                ? { file: editor.document.uri.fsPath, text: editor.document.getText(), cursorLine: editor.selection.active.line + 1 }
                : undefined;
        },
        exists: (file) => fs.existsSync(file),
        write(file, content) {
            fs.mkdirSync(path.dirname(file), { recursive: true });
            fs.writeFileSync(file, content, { flag: 'wx' });
        },
        async open(file) {
            await vscode.window.showTextDocument(vscode.Uri.file(file), { viewColumn: vscode.ViewColumn.Beside });
        },
        pick: async (choices) => vscode.window.showQuickPick(choices, { placeHolder: 'Which prompt file should be created?' }),
        report(message, level) {
            const show = level === 'error' ? vscode.window.showErrorMessage : level === 'warning' ? vscode.window.showWarningMessage : vscode.window.showInformationMessage;
            void show(message);
        },
    };
    await createPromptFile(host);
}
