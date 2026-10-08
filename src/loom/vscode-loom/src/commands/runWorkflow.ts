import * as vscode from 'vscode';
import { spawn } from 'child_process';
import * as path from 'path';

/**
 * Module-level output channel cache (Requirement 7.2).
 * Reused across multiple command invocations.
 */
let outputChannel: vscode.OutputChannel | undefined;

/**
 * Command handler for "loom.runWorkflow" (Requirements 7.2, 7.3, 7.4).
 *
 * Task 6.1 — Command handler:
 * - Obtains the active editor's file path
 * - Validates that the file is a .loom file
 * - Creates or reuses a VS Code output channel named "Loom"
 * - Invokes child_process.spawn("weave", ["run", scriptPath])
 * - Shows the output channel when the command is invoked
 *
 * Task 6.2 — Pipe stdout and stderr:
 * - Pipes stdout from the spawned process to the output channel
 * - Pipes stderr from the spawned process to the output channel
 * - Appends the exit code to the output channel on process exit
 *
 * Security requirement (Requirement 7.4):
 * - The script path is passed as a discrete element in the spawn arguments array
 * - NEVER uses string interpolation or concatenation to build a shell command
 * - Uses child_process.spawn with an argument array, NOT child_process.exec
 */
export function runWorkflowCommand(context: vscode.ExtensionContext): void {
    // Task 6.1: Obtain the active editor's file path
    const activeEditor = vscode.window.activeTextEditor;
    if (!activeEditor) {
        vscode.window.showErrorMessage('Loom: No active editor. Please open a .loom file.');
        return;
    }

    const scriptPath = activeEditor.document.uri.fsPath;

    // Task 6.1: Validate that the file is a .loom file
    if (!scriptPath.endsWith('.loom')) {
        vscode.window.showErrorMessage('Loom: Active file is not a .loom file.');
        return;
    }

    // Task 6.1: Create or reuse the output channel
    if (!outputChannel) {
        outputChannel = vscode.window.createOutputChannel('Loom');
    }

    // Task 6.1: Show the output channel
    outputChannel.show(true);

    // Clear previous output
    outputChannel.clear();
    outputChannel.appendLine(`Running workflow: ${scriptPath}`);
    outputChannel.appendLine('');

    // Task 6.1: Invoke child_process.spawn with java -jar and the bundled weave.jar
    // CRITICAL: Pass scriptPath as a discrete element in the spawn arguments array,
    // never interpolate it into a shell string (Requirement 7.4)
    const weaveJarPath = context.asAbsolutePath(path.join('bin', 'weave.jar'));
    const process = spawn('java', ['-jar', weaveJarPath, 'run', scriptPath]);

    // Task 6.2: Pipe stdout to the output channel
    process.stdout.on('data', (data: Buffer) => {
        outputChannel!.append(data.toString());
    });

    // Task 6.2: Pipe stderr to the output channel
    process.stderr.on('data', (data: Buffer) => {
        outputChannel!.append(data.toString());
    });

    // Task 6.2: Append exit code on process exit
    process.on('exit', (code: number | null) => {
        outputChannel!.appendLine('');
        outputChannel!.appendLine(`Process exited with code: ${code}`);
    });

    // Handle process errors (e.g., 'weave' command not found)
    process.on('error', (error: Error) => {
        outputChannel!.appendLine('');
        outputChannel!.appendLine(`Error: ${error.message}`);
        vscode.window.showErrorMessage(`Loom: Failed to run workflow. ${error.message}`);
    });
}
