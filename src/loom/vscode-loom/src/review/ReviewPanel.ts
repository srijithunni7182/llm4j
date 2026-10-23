import { randomBytes } from 'crypto';
import * as path from 'path';
import * as vscode from 'vscode';
import { newNonce } from '../graph/html';
import { samePath } from '../graph/paths';
import { TimeoutScheduler } from '../graph/timeoutScheduler';
import { ReviewController, ReviewState } from './controller';
import { renderReviewHtml } from './html';
import { parseReviewMessage, ReviewHostMessage } from './protocol';

/** One Loom Review panel per script. Everything the page shows comes from the controller; the panel only carries it across. */
export class ReviewPanel {
    private static readonly open = new Map<string, ReviewPanel>();
    private readonly subscriptions: vscode.Disposable[] = [];
    private ready = false;

    private constructor(
        private readonly panel: vscode.WebviewPanel,
        private readonly controller: ReviewController,
        readonly entry: string,
    ) {
        this.subscriptions.push(
            panel.webview.onDidReceiveMessage((raw) => this.handle(raw)),
            controller.onDidChange((state) => this.render(state)),
            vscode.window.onDidChangeTextEditorSelection((event) => this.cursor(event)),
            panel.onDidDispose(() => this.dispose()),
        );
    }

    /** Shows the panel for `entry`, creating it when needed. */
    static show(context: vscode.ExtensionContext, controller: ReviewController, entry: string, column: vscode.ViewColumn): ReviewPanel {
        for (const [key, existing] of ReviewPanel.open) {
            if (samePath(key, entry)) {
                existing.panel.reveal(vscode.ViewColumn.Beside, true);
                void controller.request(entry);
                return existing;
            }
        }
        const media = vscode.Uri.joinPath(context.extensionUri, 'media');
        const panel = vscode.window.createWebviewPanel(
            'loomReview',
            `Loom Review: ${path.basename(entry)}`,
            { viewColumn: vscode.ViewColumn.Beside, preserveFocus: true },
            { enableScripts: true, localResourceRoots: [media], retainContextWhenHidden: false },
        );
        return ReviewPanel.adopt(context, controller, panel, entry, column);
    }

    /** Takes a panel VS Code restored after a reload and gives it its page back. */
    static revive(context: vscode.ExtensionContext, controller: ReviewController, panel: vscode.WebviewPanel, entry: string): ReviewPanel {
        panel.webview.options = { enableScripts: true, localResourceRoots: [vscode.Uri.joinPath(context.extensionUri, 'media')] };
        return ReviewPanel.adopt(context, controller, panel, entry, vscode.ViewColumn.One);
    }

    private static adopt(context: vscode.ExtensionContext, controller: ReviewController, panel: vscode.WebviewPanel, entry: string, _column: vscode.ViewColumn): ReviewPanel {
        const media = vscode.Uri.joinPath(context.extensionUri, 'media');
        panel.iconPath = vscode.Uri.joinPath(media, 'loom-mark-128.png');
        panel.webview.html = renderReviewHtml({
            cspSource: panel.webview.cspSource,
            nonce: newNonce(randomBytes),
            uri: (name) => panel.webview.asWebviewUri(vscode.Uri.joinPath(media, name)).toString(),
        });
        const created = new ReviewPanel(panel, controller, entry);
        ReviewPanel.open.set(entry, created);
        return created;
    }

    /** The panel for a script, if one is open. */
    static forFile(file: string): ReviewPanel | undefined {
        for (const [key, p] of ReviewPanel.open) {
            if (samePath(key, file)) {
                return p;
            }
        }
        return undefined;
    }

    private handle(raw: unknown): void {
        const m = parseReviewMessage(raw);
        if (!m) {
            return;
        }
        switch (m.type) {
            case 'ready':
                this.ready = true;
                this.render(this.controller.state(this.entry));
                if (!this.controller.state(this.entry).review) {
                    void this.controller.request(this.entry);
                }
                break;
            case 'rerun':
                void this.controller.request(this.entry);
                break;
            case 'reveal':
                void this.reveal(m.file, m.line, m.beside);
                break;
            case 'selectWorkflow':
                break; // the page keeps the choice; nothing on this side depends on it
        }
    }

    private render(state: ReviewState): void {
        if (!this.ready || !samePath(state.file, this.entry)) {
            return;
        }
        if (state.review) {
            this.post({ type: 'review', review: state.review, graph: state.graph, status: state.status, message: state.message, entry: this.entry } as ReviewHostMessage);
        } else {
            this.post({ type: 'status', status: state.status, message: state.message });
        }
    }

    private cursor(event: vscode.TextEditorSelectionChangeEvent): void {
        const doc = event.textEditor.document;
        if (!this.ready || !samePath(doc.uri.fsPath, this.entry)) {
            return;
        }
        const active = event.selections[0]?.active;
        if (active) {
            this.post({ type: 'cursor', file: doc.uri.fsPath, line: active.line + 1 });
        }
    }

    /** Opens the file at a line, beside the panel: the file is the script or one it imports, never anything else. */
    private async reveal(file: string, line: number, beside: boolean): Promise<void> {
        const target = path.isAbsolute(file) ? file : path.join(path.dirname(this.entry), file);
        if (!target.endsWith('.loom') && !target.endsWith('.loot')) {
            return;
        }
        const doc = await vscode.workspace.openTextDocument(vscode.Uri.file(target));
        const position = new vscode.Position(Math.max(0, line - 1), 0);
        await vscode.window.showTextDocument(doc, { selection: new vscode.Range(position, position), viewColumn: beside ? vscode.ViewColumn.One : undefined, preserveFocus: false });
    }

    private post(message: ReviewHostMessage | (ReviewHostMessage & { entry: string })): void {
        void this.panel.webview.postMessage(message);
    }

    private dispose(): void {
        ReviewPanel.open.delete(this.entry);
        this.subscriptions.forEach((s) => s.dispose());
    }
}

export { TimeoutScheduler };
