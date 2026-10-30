import * as fs from 'fs';
import * as path from 'path';
import * as vscode from 'vscode';
import { Debouncer, LoomFileFacts, OUTLINE_FILE_LIMIT, OutlineRow, outlineRows, symbolsOf, WorkspaceFacts } from './outlineModel';

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

    constructor() {
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
        item.accessibilityInformation = { label: row.description ? `${row.label}, ${row.description}` : row.label };
        if (row.command) {
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
        return { hasFolder: true, files, moreFiles: Math.max(0, found.length - shown.length), active };
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
