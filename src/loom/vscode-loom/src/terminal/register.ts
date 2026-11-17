import * as fs from 'fs';
import * as os from 'os';
import * as path from 'path';
import * as vscode from 'vscode';
import { configuredJavaPath } from '../engine/vscodeHost';
import { ensureLauncher, launcherFolder, pathCollectionFor, rcFileFor, withPathBlock, windowsPathCommand } from './weaveLauncher';

const SETTING = 'loom.terminal.addWeaveToPath';
const COMMAND = 'loom.addWeaveToShellPath';
/** Set once the person has asked for weave in every terminal: only then does this editor keep ~/.llm4j/bin up to date. */
const EVERYWHERE = 'loom.weaveInEveryTerminal';

/**
 * Puts `weave` on the PATH of the integrated terminals, so the command the guides and the docs show works in them with nothing
 * else installed. That launcher lives in the extension's own storage and is rewritten at start-up to point at the jar of the
 * version that is running. A command offers to put weave on the PATH of every terminal too, after asking: it writes a launcher
 * to `~/.llm4j/bin`, which this editor then keeps pointing at its own jar at each start-up, and only this editor.
 */
export function registerWeaveOnPath(context: vscode.ExtensionContext): void {
    const jar = context.asAbsolutePath(path.join('bin', 'weave.jar'));
    const folder = path.join(context.globalStorageUri.fsPath, 'bin');
    const everywhere = launcherFolder(os.homedir());
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
    if (context.globalState.get<boolean>(EVERYWHERE, false)) {
        try {
            ensureLauncher(everywhere, process.platform, configuredJavaPath(), jar);
        } catch (e) {
            warnOnce(`Loom could not update ${everywhere}: ${e instanceof Error ? e.message : String(e)}`);
        }
    }
    context.subscriptions.push(
        vscode.workspace.onDidChangeConfiguration((e) => {
            if (e.affectsConfiguration(SETTING) || e.affectsConfiguration('loom.javaPath') || e.affectsConfiguration('loom.graph.javaPath')) apply();
        }),
        vscode.commands.registerCommand(COMMAND, () => addToEveryTerminal(everywhere, jar, context)),
    );
}

/** Asks, then adds the launcher folder to the PATH every new terminal reads: the user PATH on Windows, the shell profile elsewhere. */
async function addToEveryTerminal(folder: string, jar: string, context: vscode.ExtensionContext): Promise<void> {
    try {
        ensureLauncher(folder, process.platform, configuredJavaPath(), jar);
        await context.globalState.update(EVERYWHERE, true);
        if (process.platform === 'win32') await addOnWindows(folder);
        else await addInProfile(rcFileFor(process.env.SHELL, os.homedir(), process.platform));
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
