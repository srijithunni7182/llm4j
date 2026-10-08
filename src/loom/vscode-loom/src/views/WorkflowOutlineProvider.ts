import * as vscode from 'vscode';

/**
 * Represents a single node in the Workflow Outline tree.
 * Full definition is in Task 5.
 */
export interface OutlineNode {
    kind: 'agent' | 'workflow' | 'schedule' | 'routing';
    name: string;
    range: vscode.Range;
}

/**
 * Full implementation of the WorkflowOutlineProvider.
 * (Requirements 6.1, 6.2, 6.3, 6.4, 6.5)
 *
 * Implements vscode.TreeDataProvider<OutlineNode> to provide a navigable
 * tree of agents, workflows, schedules, and routing policies defined in
 * the active .loom file.
 */
export class WorkflowOutlineProvider implements vscode.TreeDataProvider<OutlineNode> {
    private _onDidChangeTreeData = new vscode.EventEmitter<OutlineNode | undefined | null | void>();
    readonly onDidChangeTreeData = this._onDidChangeTreeData.event;
    private hasLoomFile = false;

    constructor() {
        // Task 5.2: Wire to document save and active editor change
        vscode.workspace.onDidSaveTextDocument((document) => {
            if (document.languageId === 'loom') {
                this.refresh();
            }
        });

        vscode.window.onDidChangeActiveTextEditor((editor) => {
            if (!editor || editor.document.languageId !== 'loom') {
                // Clear the tree when switching away from .loom files
                this.hasLoomFile = false;
                this._onDidChangeTreeData.fire();
            } else {
                this.hasLoomFile = true;
                this.refresh();
            }
        });
    }

    /**
     * Task 5.1: Return a TreeItem with a command to navigate to element.range
     */
    getTreeItem(element: OutlineNode): vscode.TreeItem {
        const item = new vscode.TreeItem(element.name);
        item.description = element.kind;
        
        // Task 5.3: Click-to-navigate command
        const editor = vscode.window.activeTextEditor;
        if (editor) {
            item.command = {
                command: 'vscode.open',
                title: 'Go to Definition',
                arguments: [
                    editor.document.uri,
                    { selection: element.range }
                ]
            };
        }
        
        return item;
    }

    /**
     * Task 5.1: Parse the active .loom document and return all OutlineNode objects
     * in hierarchical order (agents first, then workflows, then schedules, then routing policies)
     */
    getChildren(element?: OutlineNode): Thenable<OutlineNode[]> {
        // Flat tree: no children for individual nodes
        if (element) {
            return Promise.resolve([]);
        }

        // If no .loom file is active, return empty
        if (!this.hasLoomFile) {
            return Promise.resolve([]);
        }

        const editor = vscode.window.activeTextEditor;
        if (!editor || editor.document.languageId !== 'loom') {
            return Promise.resolve([]);
        }

        const nodes = this.parseDocument(editor.document);
        return Promise.resolve(nodes);
    }

    /**
     * Trigger a tree refresh — called on document save.
     */
    refresh(): void {
        this._onDidChangeTreeData.fire();
    }

    /**
     * Parse the document and extract all agent, workflow, schedule, and routing definitions.
     * Returns nodes in the order: agents, workflows, schedules, routing policies.
     */
    private parseDocument(document: vscode.TextDocument): OutlineNode[] {
        const text = document.getText();
        const nodes: OutlineNode[] = [];

        // Regex patterns for each definition type
        const patterns = [
            { kind: 'agent' as const, regex: /^agent\s+(\w+)/gm },
            { kind: 'workflow' as const, regex: /^workflow\s+(\w+)/gm },
            { kind: 'schedule' as const, regex: /^schedule\s+(\w+)/gm },
            { kind: 'routing' as const, regex: /^routing\s+(\w+)/gm }
        ];

        // Extract nodes for each kind in order
        for (const { kind, regex } of patterns) {
            let match: RegExpExecArray | null;
            while ((match = regex.exec(text)) !== null) {
                const name = match[1];
                const startPos = document.positionAt(match.index);
                const endPos = document.positionAt(match.index + match[0].length);
                const range = new vscode.Range(startPos, endPos);
                
                nodes.push({ kind, name, range });
            }
        }

        return nodes;
    }
}
