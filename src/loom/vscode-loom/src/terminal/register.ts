import * as fs from 'fs';
import * as os from 'os';
import * as path from 'path';
import * as vscode from 'vscode';
import { configuredJavaPath } from '../engine/vscodeHost';
import { ensureLauncher, launcherFolder, pathCollectionFor, rcFileFor, withPathBlock, windowsPathCommand } from './weaveLauncher';

const SETTING = 'loom.terminal.addWeaveToPath';
const COMMAND = 'loom.addWeaveToShellPath';

/**
 * Puts `weave` on the PATH of the integrated terminals, so the command the guides and the docs show works in them with nothing
 * else installed. The launcher is written to `~/.llm4j/bin` (one folder that an update does not move, so a coding agent's
 * instructions can name it) and rewritten at start-up to point at the jar of the version that is running. A command offers
 * to put that folder on the PATH of every terminal too, after asking.
 */
export function registerWeaveOnPath(context: vscode.ExtensionContext): void {
    const jar = context.asAbsolutePath(path.join('bin', 'weave.jar'));
    const folder = launcherFolder(os.homedir());
    let told = false;
    const warnOnce = (message: string): void => {
        if (told) return;
        told = true;
        void vscode.window.showWarningMessage(message);
    };
    const apply = (): void => {
        const enabled = vscode.workspace.getConfiguration().get<boolean>(SETTING, true);
        pathCollectionFor(context.environmentVariableCollection, enabled, path.delimiter, () => path.dirname(ensureLauncher(folder, process.platform, configuredJavaPath(), jar)), warnOnce);
    };
    apply();
    context.subscriptions.push(
        vscode.workspace.onDidChangeConfiguration((e) => {
            if (e.affectsConfiguration(SETTING) || e.affectsConfiguration('loom.javaPath') || e.affectsConfiguration('loom.graph.javaPath')) apply();
        }),
        vscode.commands.registerCommand(COMMAND, () => addToEveryTerminal(folder, jar)),
    );
}

/** Asks, then adds the launcher folder to the PATH every new terminal reads: the user PATH on Windows, the shell profile elsewhere. */
async function addToEveryTerminal(folder: string, jar: string): Promise<void> {
    try {
        ensureLauncher(folder, process.platform, configuredJavaPath(), jar);
        if (process.platform === 'win32') await addOnWindows(folder);
        else await addInProfile(rcFileFor(process.env.SHELL, os.homedir()));
    } catch (e) {
        void vscode.window.showErrorMessage(`Loom could not add weave to your PATH: ${e instanceof Error ? e.message : String(e)}`);
    }
}

async function addInProfile(profile: string): Promise<void> {
    const before = fs.existsSync(profile) ? fs.readFileSync(profile, 'utf8') : '';
    const after = withPathBlock(before);
    if (after === before) {
        void vscode.window.showInformationMessage(`weave is already on the PATH set in ${profile}. Open a new terminal if it is not found.`);
        return;
    }
    const yes = 'Add It';
    const answer = await vscode.window.showInformationMessage(
        `Add three lines to ${profile} so that weave works in every new terminal, and for coding agents that run their own shell? They put ~/.llm4j/bin at the end of your PATH, so a weave you installed yourself still wins.`,
        { modal: true },
        yes,
    );
    if (answer !== yes) return;
    fs.writeFileSync(profile, after);
    void vscode.window.showInformationMessage(`Added to ${profile}. Open a new terminal (and restart a coding agent's session) to use weave.`);
}

/**
 * On Windows the user PATH is changed by PowerShell. The extension starts no process but Java, so the exact line goes to the
 * clipboard for the person to paste into a PowerShell window.
 */
async function addOnWindows(folder: string): Promise<void> {
    const copy = 'Copy the Command';
    const answer = await vscode.window.showInformationMessage(
        `To make weave work in every new terminal, and for coding agents that run their own shell, ${folder} goes on your user PATH. Copy the one PowerShell line that does it, then paste it into a PowerShell window and open a new terminal.`,
        { modal: true },
        copy,
    );
    if (answer !== copy) return;
    await vscode.env.clipboard.writeText(windowsPathCommand(folder));
    void vscode.window.showInformationMessage('Copied. Paste it into PowerShell, press Enter, then open a new terminal (and restart VS Code for its own terminals).');
}
