import * as vscode from 'vscode';
import { offerAgentSetup, OfferHost } from './agentOffer';

const MARKER = 'loom:begin';
/** The files "Loom: Set Up My Coding Agent" writes; a marker block inside the shared ones means it ran. */
const WHOLE_FILES = ['.claude/skills/llm4j-workflow-guide/SKILL.md', '.cursor/rules/loom.mdc'];
const SHARED_FILES = ['AGENTS.md', '.github/copilot-instructions.md'];

async function readText(uri: vscode.Uri): Promise<string | undefined> {
    try {
        return Buffer.from(await vscode.workspace.fs.readFile(uri)).toString('utf8');
    } catch {
        return undefined;
    }
}

/** Offers the coding-agent set-up once when a folder with a .loom file has none; the choice is remembered. Never writes anything itself. */
export function registerAgentOffer(context: vscode.ExtensionContext): void {
    const folder = vscode.workspace.workspaceFolders?.[0];
    if (!folder) {
        return;
    }
    const key = (scope: 'workspace' | 'global'): string => `loom.agentOffer.${scope}`;
    const host: OfferHost = {
        async hasLoomFile() {
            return (await vscode.workspace.findFiles('**/*.loom', '**/node_modules/**', 1)).length > 0;
        },
        async agentInstructionsPresent() {
            for (const rel of WHOLE_FILES) {
                if ((await readText(vscode.Uri.joinPath(folder.uri, rel))) !== undefined) {
                    return true;
                }
            }
            for (const rel of SHARED_FILES) {
                if ((await readText(vscode.Uri.joinPath(folder.uri, rel)))?.includes(MARKER)) {
                    return true;
                }
            }
            return false;
        },
        getFlag(scope) {
            return (scope === 'global' ? context.globalState : context.workspaceState).get<boolean>(key(scope), false);
        },
        async setFlag(scope) {
            await (scope === 'global' ? context.globalState : context.workspaceState).update(key(scope), true);
        },
        show(message, buttons) {
            return Promise.resolve(vscode.window.showInformationMessage(message, ...buttons));
        },
        async runSetUp() {
            await vscode.commands.executeCommand('loom.installSkill');
        },
    };
    void offerAgentSetup(host);
}
