import * as fs from 'fs';
import * as path from 'path';
import * as vscode from 'vscode';
import { EngineClient } from '../engine/client';
import { samePath } from '../graph/paths';
import { ReviewController } from './controller';
import { singleEdit } from './edits';
import { FindingData } from './model';
import { ReviewHostMessage, ReviewWebviewMessage } from './protocol';
import { DismissStore, EditHost, FileChange, Outcome, prepareForPanel, TriageActions } from './triage';

const PREVIEW_SCHEME = 'loom-review-preview';

/** The text the diff editor shows on its right-hand side: nothing is ever written to a file for a preview. */
class PreviewProvider implements vscode.TextDocumentContentProvider {
    private readonly texts = new Map<string, string>();
    private next = 1;

    add(text: string): vscode.Uri {
        const id = String(this.next++);
        this.texts.set(id, text);
        if (this.texts.size > 20) {
            this.texts.delete(this.texts.keys().next().value as string);
        }
        return vscode.Uri.from({ scheme: PREVIEW_SCHEME, path: '/' + id });
    }

    provideTextDocumentContent(uri: vscode.Uri): string {
        return this.texts.get(uri.path.slice(1)) ?? '';
    }
}

/** The editor's side of the actions: VS Code's own diff editor, one WorkspaceEdit for all the changes (one undo), and the editor's undo. */
export class VsCodeEditHost implements EditHost {
    private readonly previews = new PreviewProvider();

    constructor() {}

    register(): vscode.Disposable {
        return vscode.workspace.registerTextDocumentContentProvider(PREVIEW_SCHEME, this.previews);
    }

    async text(file: string): Promise<string | undefined> {
        const open = vscode.workspace.textDocuments.find((d) => samePath(d.uri.fsPath, file));
        if (open) {
            return open.getText();
        }
        try {
            return fs.readFileSync(file, 'utf8');
        } catch {
            return undefined;
        }
    }

    async apply(changes: FileChange[]): Promise<boolean> {
        const edit = new vscode.WorkspaceEdit();
        const touched: vscode.Uri[] = [];
        for (const c of changes) {
            const uri = vscode.Uri.file(c.file);
            if (!c.existed) {
                edit.createFile(uri, { ignoreIfExists: true });
                edit.insert(uri, new vscode.Position(0, 0), c.after);
            } else {
                const doc = await vscode.workspace.openTextDocument(uri);
                // text that has moved on since the change was prepared is never overwritten
                if (doc.getText() !== c.before) {
                    return false;
                }
                const e = singleEdit(c.before, c.after);
                if (!e) {
                    continue;
                }
                edit.replace(uri, new vscode.Range(e.startLine, e.startColumn, e.endLine, e.endColumn), e.newText);
            }
            touched.push(uri);
        }
        const applied = await vscode.workspace.applyEdit(edit);
        if (!applied) {
            return false;
        }
        // a project file (review.properties, the baseline) is saved so the next review reads it; the script is left for the person to save
        for (const uri of touched) {
            if (!uri.fsPath.endsWith('.loom')) {
                const doc = vscode.workspace.textDocuments.find((d) => samePath(d.uri.fsPath, uri.fsPath));
                await doc?.save();
            }
        }
        return true;
    }

    async confirm(title: string, change: FileChange): Promise<boolean> {
        const right = this.previews.add(change.after);
        const left = change.existed ? vscode.Uri.file(change.file) : this.previews.add('');
        await vscode.commands.executeCommand('vscode.diff', left, right, `${title}: ${path.basename(change.file)} (proposed)`, { preview: true, viewColumn: vscode.ViewColumn.Beside });
        const choice = await vscode.window.showInformationMessage(title, 'Apply', 'Cancel');
        if (choice !== 'Apply') {
            await vscode.commands.executeCommand('workbench.action.closeActiveEditor');
        }
        return choice === 'Apply';
    }

    async undo(files: string[]): Promise<void> {
        for (const f of files) {
            const doc = await vscode.workspace.openTextDocument(vscode.Uri.file(f));
            await vscode.window.showTextDocument(doc, { preserveFocus: false });
            await vscode.commands.executeCommand('undo');
        }
    }
}

/** What the panel's buttons do. One instance per window; the panel passes it what the person pressed. */
export class PanelTriage {
    readonly dismissals: DismissStore;
    readonly actions: TriageActions;
    private readonly undoers = new Map<number, () => Promise<void>>();
    private nextToken = 1;

    constructor(
        private readonly controller: ReviewController,
        private readonly engine: () => EngineClient | undefined,
        memory: vscode.Memento,
        readonly host: VsCodeEditHost,
    ) {
        this.dismissals = new DismissStore({ get: (k) => memory.get(k), update: (k, v) => memory.update(k, v) });
        this.actions = new TriageActions(engine, host, this.dismissals);
    }

    /** What to send the panel for this script's state. */
    prepare(file: string): { review: unknown; dismissed: FindingData[] } | undefined {
        const state = this.controller.state(file);
        if (!state.review) {
            return undefined;
        }
        this.dismissals.prune(file, state.review);
        return prepareForPanel(state.review, this.dismissals.dismissed(file));
    }

    findings(file: string, ids: string[]): FindingData[] {
        const all = this.controller.state(file).review?.findings ?? [];
        return all.filter((f) => ids.includes(f.fingerprint));
    }

    /** Handles one action message from the panel for `file`. `post` tells the panel; `refresh` redraws it from the stored state. */
    async handle(m: ReviewWebviewMessage, file: string, post: (message: ReviewHostMessage) => void, refresh: () => void): Promise<void> {
        let outcome: Outcome | undefined;
        switch (m.type) {
            case 'fix': {
                const found = this.findings(file, m.ids);
                outcome = found.length === 1 ? await this.actions.fixOne(file, found[0]) : await this.actions.fixAllSafe(file, found);
                break;
            }
            case 'fixOption': {
                const f = this.findings(file, [m.id])[0];
                outcome = f ? await this.actions.fixOption(file, f, m.option, m.answers) : gone();
                break;
            }
            case 'accept':
                outcome = await this.actions.accept(file, { ids: m.ids, scope: m.scope, reason: m.reason, until: m.until, on: m.on, confirm: m.confirm });
                break;
            case 'dismiss':
                outcome = this.actions.dismiss(file, this.findings(file, m.ids));
                refresh();
                break;
            case 'restore':
                outcome = this.actions.restore(file, m.ids);
                refresh();
                break;
            case 'restoreAll':
                outcome = this.actions.restoreAll(file);
                refresh();
                break;
            case 'baseline':
                outcome = await this.actions.baseline(file, m.includeSerious);
                break;
            case 'explain':
                await this.explain(m.rule);
                return;
            case 'undo': {
                const undo = this.undoers.get(m.token);
                this.undoers.delete(m.token);
                if (undo) {
                    await undo();
                    refresh();
                }
                return;
            }
            default:
                return;
        }
        if (!outcome) {
            return;
        }
        this.report(outcome, post);
        if (outcome.rerun) {
            void this.controller.request(file);
        } else if (!outcome.ok) {
            refresh();
        }
    }

    /** Tells the panel what happened; an outcome that can be undone carries a token the panel sends back. */
    report(outcome: Outcome, post: (message: ReviewHostMessage) => void): void {
        let token: number | undefined;
        if (outcome.ok && outcome.undo) {
            token = this.nextToken++;
            this.undoers.set(token, async () => {
                await outcome.undo!();
            });
            if (this.undoers.size > 20) {
                this.undoers.delete(this.undoers.keys().next().value as number);
            }
        }
        post({ type: 'toast', text: outcome.message, ok: outcome.ok, token });
    }

    /** The rule's page, from the engine, in a side view. */
    async explain(rule: string): Promise<void> {
        const engine = this.engine();
        if (!engine) {
            void vscode.window.showWarningMessage('Loom: the engine is not running, so the rule cannot be explained. Try "Loom: Restart Engine".');
            return;
        }
        try {
            const answer = await engine.request('loom/explainRule', { rule });
            const doc = await vscode.workspace.openTextDocument({ language: 'markdown', content: `# ${rule}\n\n\`\`\`text\n${answer.text}\n\`\`\`\n` });
            await vscode.window.showTextDocument(doc, { viewColumn: vscode.ViewColumn.Beside, preview: true, preserveFocus: true });
        } catch (e) {
            void vscode.window.showWarningMessage(`Loom: ${(e as Error).message}`);
        }
    }
}

function gone(): Outcome {
    return { ok: false, message: 'That finding is no longer in the review. Reviewing again.', rerun: true };
}
