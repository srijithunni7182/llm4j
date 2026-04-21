import * as vscode from 'vscode';
import {
    LanguageClient,
    LanguageClientOptions,
    ServerOptions,
    TransportKind,
    State,
} from 'vscode-languageclient/node';

import { runWorkflowCommand } from './commands/runWorkflow';
import { WorkflowOutlineProvider } from './views/WorkflowOutlineProvider';

// Module-level client reference so deactivate() can stop it.
let client: LanguageClient | undefined;

// Track whether a restart has already been attempted to avoid infinite loops.
let restartAttempted = false;

/**
 * Called by VS Code when the extension activates (onLanguage:loom / onLanguage:loot).
 *
 * Responsibilities (Requirements 1.1, 1.2, 2.1, 2.2, 6.1):
 *  - Start the Language Server process and maintain the LSP client connection.
 *  - Register the `loom.runWorkflow` command.
 *  - Register the WorkflowOutline tree view.
 *  - Push all disposables to context.subscriptions so VS Code cleans them up.
 */
export function activate(context: vscode.ExtensionContext): void {
    // ------------------------------------------------------------------ //
    // 1. Language Server (LSP client)                                     //
    // ------------------------------------------------------------------ //

    const serverModule = context.asAbsolutePath('./out/lsp/server.js');

    const serverOptions: ServerOptions = {
        run: {
            module: serverModule,
            transport: TransportKind.ipc,
        },
        debug: {
            module: serverModule,
            transport: TransportKind.ipc,
            options: { execArgv: ['--nolazy', '--inspect=6009'] },
        },
    };

    const clientOptions: LanguageClientOptions = {
        documentSelector: [{ scheme: 'file', language: 'loom' }],
        synchronize: {
            fileEvents: vscode.workspace.createFileSystemWatcher('**/*.loom'),
        },
    };

    client = new LanguageClient(
        'loomLanguageServer',
        'Loom Language Server',
        serverOptions,
        clientOptions
    );

    // ------------------------------------------------------------------ //
    // 2. LSP restart logic (Requirement 2.4 / Task 3.3)                  //
    //                                                                     //
    // If the language client transitions to the Stopped state             //
    // unexpectedly (i.e. not because deactivate() was called), attempt    //
    // one restart. If the restart itself fails, show an error message.    //
    // ------------------------------------------------------------------ //
    client.onDidChangeState(async (event) => {
        if (event.newState === State.Stopped && !restartAttempted) {
            restartAttempted = true;
            try {
                await client!.start();
                // Reset the flag on a successful restart so future unexpected
                // stops can trigger another single-attempt restart.
                restartAttempted = false;
            } catch {
                vscode.window.showErrorMessage('Loom Language Server failed to start.');
            }
        }
    });

    client.start();
    context.subscriptions.push(client);

    // ------------------------------------------------------------------ //
    // 3. "Run Workflow" command (Requirement 7.1, 6.1)                   //
    // ------------------------------------------------------------------ //
    const runWorkflowDisposable = vscode.commands.registerCommand(
        'loom.runWorkflow',
        () => runWorkflowCommand(context)
    );
    context.subscriptions.push(runWorkflowDisposable);

    // ------------------------------------------------------------------ //
    // 4. Workflow Outline tree view (Requirement 6.1)                    //
    // ------------------------------------------------------------------ //
    const provider = new WorkflowOutlineProvider();
    const treeViewDisposable = vscode.window.registerTreeDataProvider(
        'loom.workflowOutline',
        provider
    );
    context.subscriptions.push(treeViewDisposable);
}

/**
 * Called by VS Code when the extension deactivates (Requirement 2.3 / Task 3.2).
 *
 * Stops the LSP client and releases all associated resources.
 * VS Code also disposes everything pushed to context.subscriptions, but
 * the LanguageClient requires an explicit stop() call to cleanly shut down
 * the server process.
 */
export function deactivate(): Thenable<void> | undefined {
    return client?.stop();
}
