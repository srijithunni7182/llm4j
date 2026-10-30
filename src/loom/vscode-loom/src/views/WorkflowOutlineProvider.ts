import * as fs from 'fs';
import * as path from 'path';
import * as vscode from 'vscode';
import { EngineClient } from '../engine/client';
import { Debouncer, LoomFileFacts, OUTLINE_FILE_LIMIT, OutlineRow, outlineRows, symbolsOf, WorkspaceFacts } from './outlineModel';
import { ProgressSource, isProgressFile, progressRows, progressStatus } from './progressModel';

/** A file bigger than this is listed but not read for its definitions. */
const MAX_READ_BYTES = 1_000_000;
const REFRESH_DELAY_MS = 150;
/** How many files to look for: the limit, plus enough to say how many were left out. */
const SEARCH_LIMIT = 1000;

/**
 * The Workflow Outline: the active file's agents, workflows, schedules and routing, and the other
 * workflows in the folder. It is never blank: with no folder, no Loom file, or an error, it says
 * what is the case (see outlineModel).
 */
export class WorkflowOutlineProvider implements vscode.TreeDataProvider<OutlineRow> {
    private readonly changed = new vscode.EventEmitter<OutlineRow | undefined | null | void>();
    readonly onDidChangeTreeData = this.changed.event;
    private readonly refreshSoon = new Debouncer(
        () => this.changed.fire(),
        (fn) => setTimeout(fn, REFRESH_DELAY_MS) as unknown as number,
        (id) => clearTimeout(id as unknown as NodeJS.Timeout),
    );

    private progressFor: { script: string; source: ProgressSource } | undefined;

    /** @param engine the running engine, or undefined when there is none (no Java, still starting): progress then says it needs one. */
    constructor(private readonly engine: () => EngineClient | undefined = () => undefined) {
        const poke = (): void => this.refreshSoon.poke();
        vscode.workspace.onDidSaveTextDocument((document) => {
            if (document.languageId === 'loom') {
                poke();
            }
        });
        vscode.window.onDidChangeActiveTextEditor(poke);
        vscode.workspace.onDidChangeWorkspaceFolders?.(poke);
        const watcher = vscode.workspace.createFileSystemWatcher?.('**/*.loom');
        watcher?.onDidCreate(poke);
        watcher?.onDidDelete(poke);
        watcher?.onDidChange(poke);
        // the evaluation's files and tests change stages without touching a .loom file
        const products = vscode.workspace.createFileSystemWatcher?.('**/{eval/**,EVAL_DESIGN.md,*.test.yaml,*.test.yml}');
        const pokeIfStage = (uri: vscode.Uri): void => {
            if (isProgressFile(uri.fsPath)) {
                poke();
            }
        };
        products?.onDidCreate(pokeIfStage);
        products?.onDidDelete(pokeIfStage);
        products?.onDidChange(pokeIfStage);
        this.status = vscode.window.createStatusBarItem?.(vscode.StatusBarAlignment.Left, 50);
    }

    private readonly status: vscode.StatusBarItem | undefined;

    /** Shows the next step in the status bar, or hides the item when there is no journey. */
    private showStatus(journey: Parameters<typeof progressStatus>[0]): void {
        const bar = progressStatus(journey);
        if (!this.status) {
            return;
        }
        if (!bar) {
            this.status.hide();
            return;
        }
        this.status.text = bar.text;
        this.status.tooltip = bar.tooltip;
        this.status.command = bar.command ? { command: bar.command, title: bar.text, arguments: [bar.argument] } : undefined;
        this.status.show();
    }

    getTreeItem(row: OutlineRow): vscode.TreeItem {
        const collapsible =
            row.children && row.children.length > 0
                ? row.collapsed || row.kind === 'file'
                    ? vscode.TreeItemCollapsibleState.Collapsed
                    : vscode.TreeItemCollapsibleState.Expanded
                : vscode.TreeItemCollapsibleState.None;
        const item = new vscode.TreeItem(row.label, collapsible);
        item.description = row.description;
        if (row.icon) {
            item.iconPath = new vscode.ThemeIcon(row.icon);
        }
        item.accessibilityInformation = { label: row.accessibleLabel ?? (row.description ? `${row.label}, ${row.description}` : row.label) };
        if (row.typeCommand) {
            item.command = { command: 'loom.progress.type', title: row.label, arguments: [row.typeCommand] };
        } else if (row.command) {
            item.command = { command: row.command, title: row.label };
        } else if (row.file) {
            const position = new vscode.Position(Math.max(0, (row.line ?? 1) - 1), 0);
            item.command = {
                command: 'vscode.open',
                title: 'Go to definition',
                arguments: [vscode.Uri.file(row.file), { selection: new vscode.Range(position, position) }],
            };
        }
        return item;
    }

    async getChildren(row?: OutlineRow): Promise<OutlineRow[]> {
        if (row) {
            return row.children ?? [];
        }
        try {
            return outlineRows(await this.facts());
        } catch (e) {
            return outlineRows({
                hasFolder: true,
                files: [],
                error: { message: `The outline could not be read: ${e instanceof Error ? e.message : String(e)}`, fix: 'Reload the window, or open the Output panel (Loom) for details' },
            });
        }
    }

    refresh(): void {
        this.changed.fire();
    }

    private async facts(): Promise<WorkspaceFacts> {
        const folders = vscode.workspace.workspaceFolders;
        if (!folders || folders.length === 0) {
            return { hasFolder: false, files: [] };
        }
        const found = await vscode.workspace.findFiles('**/*.loom', '**/node_modules/**', SEARCH_LIMIT);
        const shown = found.slice(0, OUTLINE_FILE_LIMIT);
        const files = await Promise.all(shown.map((uri) => this.read(uri.fsPath)));
        files.sort((a, b) => a.relative.localeCompare(b.relative));
        const editor = vscode.window.activeTextEditor;
        const active =
            editor && editor.document.languageId === 'loom'
                ? { path: editor.document.uri.fsPath, relative: this.relative(editor.document.uri.fsPath), symbols: symbolsOf(editor.document.getText()) }
                : undefined;
        const script = active?.path ?? files.find((f) => path.basename(f.path) === 'main.loom')?.path ?? files[0]?.path;
        const progress = script ? await this.progress(script) : undefined;
        return { hasFolder: true, files, moreFiles: Math.max(0, found.length - shown.length), active, progress };
    }

    /** The project's progress, from the engine; a message when there is no engine to ask. */
    private async progress(script: string): Promise<OutlineRow> {
        const engine = this.engine();
        if (!engine || engine.state === 'unavailable' || engine.state === 'mismatch' || engine.state === 'stopped') {
            this.showStatus(undefined);
            return { kind: 'message', label: 'Project progress needs the Loom engine (Java 17 or newer)', command: 'loom.javaHelp' };
        }
        if (this.progressFor?.script !== script) {
            this.progressFor = {
                script,
                source: new ProgressSource(
                    () => engine.request('loom/next', { file: script }),
                    () => Date.now(),
                ),
            };
        }
        const known = await this.progressFor.source.get();
        this.showStatus(known?.journey);
        if (!known) {
            return { kind: 'message', label: 'Project progress is not ready yet', description: 'it appears when the engine has answered' };
        }
        return progressRows(known.journey, known.journey.root ?? path.dirname(script), known.ageSeconds);
    }

    private async read(file: string): Promise<LoomFileFacts> {
        let symbols: LoomFileFacts['symbols'] = [];
        try {
            if ((await fs.promises.stat(file)).size <= MAX_READ_BYTES) {
                symbols = symbolsOf(await fs.promises.readFile(file, 'utf8'));
            }
        } catch {
            // a file that vanished between the search and the read is listed with nothing in it
        }
        return { path: file, relative: this.relative(file), symbols };
    }

    private relative(file: string): string {
        const folder = vscode.workspace.workspaceFolders?.find((f) => file.startsWith(f.uri.fsPath));
        return (folder ? path.relative(folder.uri.fsPath, file) : path.basename(file)).split(path.sep).join('/');
    }
}
