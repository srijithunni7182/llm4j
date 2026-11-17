import * as fs from 'fs';
import * as path from 'path';
import * as vscode from 'vscode';
import { vscodeHandoffHost } from '../commands/onboardingCommands';
import { Journey } from '../views/progressModel';
import { shellKind, terminalCommand } from '../welcome/welcome';
import { AGENT_NAME, MCP_SERVER, serverCommand, writeAgentFiles } from './agentFiles';
import { Args, EditorActions, handleRequest, pending } from './mailbox';
import { GuideEvent, NoticeGate, appendEvent, guideFolder, noticeFor, stageChanges, statusText, undiscussed } from './model';

/**
 * The Guide in the editor: marks the editor present for `weave guide-mcp`, answers its requests
 * (show the graph, the review, the report, a file, the key terminal, a drafted instruction),
 * records what happens, shows a notice when something is worth a conversation, counts the notes
 * not yet discussed, registers the MCP server with the editor, and sets up and opens the agent.
 */
export class GuideCompanion implements vscode.Disposable {
    private readonly disposables: vscode.Disposable[] = [];
    private readonly gate = new NoticeGate(() => Date.now());
    private readonly status: vscode.StatusBarItem;
    private lastJourney: Journey | undefined;
    private lastNoticed = 0;

    constructor(
        private readonly context: vscode.ExtensionContext,
        private readonly root: string,
    ) {
        this.status = vscode.window.createStatusBarItem(vscode.StatusBarAlignment.Left, 49);
        this.status.command = 'loom.guide.open';
        this.disposables.push(this.status);
        this.markPresent();
        const requests = vscode.workspace.createFileSystemWatcher(new vscode.RelativePattern(root, '.loom/guide/requests/*.json'));
        requests.onDidCreate((uri) => void this.answer(uri.fsPath), undefined, this.disposables);
        const events = vscode.workspace.createFileSystemWatcher(new vscode.RelativePattern(root, '.loom/guide/{events.jsonl,discussed.json}'));
        events.onDidChange(() => this.refresh(), undefined, this.disposables);
        events.onDidCreate(() => this.refresh(), undefined, this.disposables);
        this.disposables.push(requests, events);
        vscode.workspace.onDidSaveTextDocument((d) => this.saved(d), undefined, this.disposables);
        for (const f of pending(root)) void this.answer(f);
        this.refresh();
    }

    private java(): string {
        return vscode.workspace.getConfiguration('loom.graph').get<string>('javaPath', 'java');
    }

    private jar(): string {
        return this.context.asAbsolutePath(path.join('bin', 'weave.jar'));
    }

    /** `weave guide-mcp` sends requests only while this file exists. */
    private markPresent(): void {
        const folder = guideFolder(this.root);
        fs.mkdirSync(folder, { recursive: true });
        fs.writeFileSync(path.join(folder, 'editor.json'), JSON.stringify({ pid: process.pid, at: new Date().toISOString() }) + '\n', { mode: 0o600 });
    }

    /** Called by the Outline each time it reads the journey: stage changes become events. */
    journeyChanged(journey: Journey | undefined): void {
        if (!journey) return;
        for (const e of stageChanges(this.lastJourney, journey)) appendEvent(this.root, e, new Date());
        this.lastJourney = journey;
        this.refresh();
    }

    private saved(doc: vscode.TextDocument): void {
        const rel = path.relative(this.root, doc.uri.fsPath);
        if (rel.startsWith('..') || path.isAbsolute(rel)) return;
        const isScript = rel.endsWith('.loom');
        const isPrompt = rel.split(path.sep)[0] === 'prompts';
        if (isScript || isPrompt) appendEvent(this.root, { kind: 'saved', subject: rel.split(path.sep).join('/') }, new Date());
    }

    private refresh(): void {
        const notes = undiscussed(this.root);
        const text = statusText(notes.length);
        if (text) {
            this.status.text = text;
            this.status.tooltip = 'Things the Loom Guide has not talked about yet. Click to open the Guide.';
            this.status.show();
        } else {
            this.status.hide();
        }
        const fresh = notes.filter((e) => (e.seq ?? 0) > this.lastNoticed);
        if (fresh.length === 0) return;
        this.lastNoticed = Math.max(...fresh.map((e) => e.seq ?? 0));
        const worth = fresh.map(noticeFor).filter((t): t is string => t !== undefined);
        const enabled = vscode.workspace.getConfiguration('loom.guide').get<boolean>('notices', true);
        if (worth.length > 0 && this.gate.allow(enabled, false)) {
            void vscode.window.showInformationMessage(worth[worth.length - 1], 'Ask the Guide', 'Not now').then((c) => {
                if (c === 'Ask the Guide') void vscode.commands.executeCommand('loom.guide.open', fresh[fresh.length - 1]);
            });
        }
    }

    private async answer(file: string): Promise<void> {
        await handleRequest(this.root, file, this.actions(), Date.now());
    }

    private async mainScript(): Promise<vscode.TextDocument | undefined> {
        const main = path.join(this.root, 'main.loom');
        const found = fs.existsSync(main)
            ? [vscode.Uri.file(main)]
            : await vscode.workspace.findFiles(new vscode.RelativePattern(this.root, '**/*.loom'), '**/{node_modules,target,out}/**', 1);
        if (found.length === 0) return undefined;
        const doc = await vscode.workspace.openTextDocument(found[0]);
        await vscode.window.showTextDocument(doc, { preview: false });
        return doc;
    }

    private typeWeave(name: string, args: string[]): void {
        const terminal = vscode.window.createTerminal({ name, cwd: this.root });
        terminal.show();
        terminal.sendText(terminalCommand(this.java(), this.jar(), args, shellKind(vscode.env.shell, process.platform)));
    }

    private actions(): EditorActions {
        return {
            show_graph: async (a: Args) => {
                if (!(await this.mainScript())) return 'There is no .loom script to draw yet.';
                await vscode.commands.executeCommand('loom.showGraph');
                return a.node ? `Opened the graph; ${String(a.node)} is one of its nodes.` : 'Opened the graph beside the script.';
            },
            show_review: async (a: Args) => {
                if (!(await this.mainScript())) return 'There is no .loom script to review yet.';
                await vscode.commands.executeCommand('loom.review');
                return a.rule ? `Opened the Review panel; the ${String(a.rule)} findings are in it.` : 'Opened the Review panel.';
            },
            show_eval_report: async () => {
                for (const rel of ['target/eval4j/report/index.html', 'eval-runs/report/index.html']) {
                    const f = path.join(this.root, rel);
                    if (fs.existsSync(f)) {
                        await vscode.env.openExternal(vscode.Uri.file(f));
                        return `Opened the eval4j report (${rel}).`;
                    }
                }
                return 'There is no eval4j report yet: it is written after weave eval runs.';
            },
            show_file: async (a: Args) => {
                const doc = await vscode.workspace.openTextDocument(vscode.Uri.file(path.join(this.root, String(a.path))));
                const line = Math.max(0, Number(a.line ?? 1) - 1);
                await vscode.window.showTextDocument(doc, { selection: new vscode.Range(line, 0, line, 0), preview: false });
                return `Opened ${String(a.path)}${a.line ? ` at line ${String(a.line)}` : ''}.`;
            },
            show_run: async () => {
                const pick = await vscode.window.showInformationMessage(
                    'The Guide suggests watching a run: the live page shows each agent, tool call, context fill and cost as it happens.',
                    'Run With Live Page...',
                );
                if (pick) await vscode.commands.executeCommand('loom.observe');
                return pick ? 'The developer chose to start a watched run; it asks them to confirm its cap.' : 'The developer did not open the run page.';
            },
            start_observed_run: async () => {
                await vscode.commands.executeCommand('loom.observe');
                return 'Asked the developer to start one capped run with the live page; it runs only if they confirm the cap.';
            },
            open_secret_form: async (a: Args) => {
                this.typeWeave('Loom: set a key', ['secrets', 'set', String(a.name), '--allow-host', String(a.host)]);
                return `Opened a terminal where the developer types ${String(a.name)} with no echo, bound to ${String(a.host)}. The value never passes through the Guide.`;
            },
            open_walkthrough: async () => {
                await vscode.commands.executeCommand('loom.openWalkthrough');
                return 'Opened the Loom walkthrough.';
            },
            draft_builder_instruction: async (a: Args) => {
                const text = String(a.text);
                const choice = await vscode.window.showInformationMessage(
                    `The Guide drafted an instruction for your coding agent:\n\n${text}`,
                    { modal: true },
                    'Put It in the Agent Chat',
                    'Copy',
                );
                const handoff = vscodeHandoffHost();
                if (choice === 'Put It in the Agent Chat') {
                    await handoff.copyToClipboard(text);
                    const opened = await handoff.openChat(text);
                    return opened
                        ? 'The instruction is in the agent chat box; the developer reads it and sends it.'
                        : 'The chat could not be opened; the instruction is on the clipboard.';
                }
                if (choice === 'Copy') {
                    await handoff.copyToClipboard(text);
                    return 'The developer copied the instruction.';
                }
                return 'The developer did not take the instruction.';
            },
        };
    }

    /** Writes the Guide's agent files for Copilot and Claude Code, after showing what it writes. */
    async setUp(): Promise<void> {
        const ok = await vscode.window.showInformationMessage(
            'Set up the Loom Guide? It writes .github/agents/llm4j-guide.agent.md (Copilot), .claude/agents/llm4j-guide.md (Claude Code) and adds the llm4j-guide server to .mcp.json. A file you changed is left alone.',
            { modal: true },
            'Set Up',
        );
        if (ok !== 'Set Up') return;
        const persona = fs.readFileSync(this.context.asAbsolutePath(path.join('guide', 'persona.md')), 'utf8');
        const written = writeAgentFiles(this.root, persona, this.java(), this.jar());
        const kept = written.filter((w) => w.outcome === 'kept-yours').map((w) => w.file);
        void vscode.window.showInformationMessage(
            `The Guide is set up. In Copilot Chat pick "${AGENT_NAME}" in the agent list; in Claude Code run "claude --agent ${AGENT_NAME}" (Loom: Open the Guide does it).` +
                (kept.length ? ` Left as you changed them: ${kept.join(', ')}.` : ''),
        );
    }

    /** Opens the Guide on the assistant the developer chooses. */
    async open(event?: GuideEvent): Promise<void> {
        // only a plain stage id from the events file may reach a terminal line
        const stage = /^[a-z][a-z-]{0,39}$/.test(event?.stage ?? '') ? event?.stage : undefined;
        const ask = stage ? `What does the change to the ${stage} stage mean for my workflow?` : 'Where does my workflow stand, and what should I do next?';
        const where = await vscode.window.showQuickPick(
            [
                { label: 'In Copilot Chat', detail: `Choose ${AGENT_NAME} in the agent list; the question is filled in for you`, id: 'copilot' },
                { label: 'In Claude Code', detail: `A terminal running: claude --agent ${AGENT_NAME}`, id: 'claude' },
            ],
            { placeHolder: 'Open the Loom Guide' },
        );
        if (!where) return;
        if (!fs.existsSync(path.join(this.root, '.github', 'agents', `${AGENT_NAME}.agent.md`))) await this.setUp();
        if (where.id === 'claude') {
            const terminal = vscode.window.createTerminal({ name: 'Loom Guide', cwd: this.root });
            terminal.show();
            terminal.sendText(`claude --agent ${AGENT_NAME} '${ask}'`);
            return;
        }
        if (!(await vscodeHandoffHost().openChat(ask))) {
            void vscode.window.showInformationMessage(`Open Copilot Chat and choose ${AGENT_NAME} in the agent list.`);
        }
    }

    /** The Guide's MCP server, offered to Copilot without a `.vscode/mcp.json`. */
    registerServer(): void {
        // editors and test doubles without the MCP API simply go without it
        if (typeof vscode.lm?.registerMcpServerDefinitionProvider !== 'function') return;
        const { command, args } = serverCommand(this.java(), this.jar(), this.root);
        this.disposables.push(
            vscode.lm.registerMcpServerDefinitionProvider(MCP_SERVER, {
                provideMcpServerDefinitions: () => [new vscode.McpStdioServerDefinition(MCP_SERVER, command, args)],
            }),
        );
    }

    dispose(): void {
        fs.rmSync(path.join(guideFolder(this.root), 'editor.json'), { force: true });
        for (const d of this.disposables) d.dispose();
    }
}

/** Starts the Guide for the first workspace folder and registers its commands. */
export function registerGuide(context: vscode.ExtensionContext): GuideCompanion | undefined {
    const root = vscode.workspace.workspaceFolders?.[0]?.uri.fsPath;
    const companion = root ? new GuideCompanion(context, root) : undefined;
    companion?.registerServer();
    const needFolder = () => void vscode.window.showInformationMessage('Open a folder with a .loom script first.');
    context.subscriptions.push(
        vscode.commands.registerCommand('loom.guide.setUp', () => (companion ? companion.setUp() : needFolder())),
        vscode.commands.registerCommand('loom.guide.open', (e?: GuideEvent) => (companion ? companion.open(e) : needFolder())),
    );
    if (companion) context.subscriptions.push(companion);
    return companion;
}
