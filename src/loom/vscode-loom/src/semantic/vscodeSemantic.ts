import * as fs from 'fs';
import * as vscode from 'vscode';
import type { Middleware } from 'vscode-languageclient/node';
import { engineHost, engineLog } from '../engine/vscodeHost';
import { samePath } from '../graph/paths';
import { completionAnswer, definitionAnswer, hoverAnswer, outlineAnswer, referencesAnswer, signatureAnswer } from './answers';
import { Suggestion } from './completion';
import { OutlineItem, planRename, prepareRename } from './queries';
import { EngineSymbolsSource, SymbolsSource, Unsaved } from './source';
import { Range as IndexRange } from './symbols';
import { offsetOf } from './text';

const SELECTOR: vscode.DocumentSelector = { scheme: 'file', language: 'loom' };

function isLoom(document: vscode.TextDocument): boolean {
    return document.uri.scheme === 'file' && document.uri.fsPath.endsWith('.loom');
}

function enabled(): boolean {
    return vscode.workspace.getConfiguration('loom.semantic').get<boolean>('enable', true);
}

function toRange(r: IndexRange): vscode.Range {
    return new vscode.Range(r.start.line, r.start.character, r.end.line, r.end.character);
}

/** The unsaved text of the file asked about, and of every other changed script (so imports are read as they are being edited). */
function unsavedFor(file: string): Unsaved {
    const own = vscode.workspace.textDocuments.find((d) => d.isDirty && isLoom(d) && samePath(d.uri.fsPath, file));
    const others: Record<string, string> = {};
    for (const d of vscode.workspace.textDocuments) {
        if (d.isDirty && isLoom(d) && !samePath(d.uri.fsPath, file)) {
            others[d.uri.fsPath] = d.getText();
        }
    }
    return { text: own?.getText(), buffers: Object.keys(others).length > 0 ? others : undefined };
}

function abortOn(token: vscode.CancellationToken): AbortSignal {
    const controller = new AbortController();
    token.onCancellationRequested(() => controller.abort());
    return controller.signal;
}

let source: SymbolsSource | undefined;

function symbols(): SymbolsSource | undefined {
    return enabled() ? source : undefined;
}

const COMPLETION_KINDS: Record<Suggestion['kind'], vscode.CompletionItemKind> = {
    agent: vscode.CompletionItemKind.Class,
    workflow: vscode.CompletionItemKind.Function,
    tool: vscode.CompletionItemKind.Method,
    classifier: vscode.CompletionItemKind.Interface,
    decision: vscode.CompletionItemKind.Interface,
    checkpoint: vscode.CompletionItemKind.Reference,
    variable: vscode.CompletionItemKind.Variable,
    field: vscode.CompletionItemKind.Field,
    value: vscode.CompletionItemKind.EnumMember,
    task: vscode.CompletionItemKind.Event,
    mcp: vscode.CompletionItemKind.Module,
    knowledge: vscode.CompletionItemKind.Module,
    routing: vscode.CompletionItemKind.Module,
};

const SYMBOL_KINDS: Record<string, vscode.SymbolKind> = {
    agent: vscode.SymbolKind.Class,
    workflow: vscode.SymbolKind.Function,
    tool: vscode.SymbolKind.Method,
    classifier: vscode.SymbolKind.Interface,
    persona: vscode.SymbolKind.Object,
    provider: vscode.SymbolKind.Module,
    decision: vscode.SymbolKind.Interface,
    mcp: vscode.SymbolKind.Module,
    knowledge: vscode.SymbolKind.Module,
    routing: vscode.SymbolKind.Module,
    checkpoint: vscode.SymbolKind.Event,
    variable: vscode.SymbolKind.Variable,
};

function toSymbol(item: OutlineItem): vscode.DocumentSymbol {
    const s = new vscode.DocumentSymbol(item.name, item.detail ?? item.kind, SYMBOL_KINDS[item.kind] ?? vscode.SymbolKind.Object, toRange(item.range), toRange(item.selection));
    s.children = item.children.map(toSymbol);
    return s;
}

/**
 * Go to definition, hover and completion go through the language client, which already answers them: the engine's answer comes first,
 * and when the engine has nothing (no engine, not a place it knows) the older answer is used. Switching `loom.semantic.enable` off
 * leaves only the older answers.
 */
export function semanticMiddleware(): Middleware {
    return {
        provideDefinition: async (document, position, token, next) => {
            const found = await definitionAnswer(symbols(), document.uri.fsPath, position, abortOn(token));
            return found ? found.map((l) => new vscode.Location(vscode.Uri.file(l.file), toRange(l.range))) : next(document, position, token);
        },
        provideHover: async (document, position, token, next) => {
            const info = await hoverAnswer(symbols(), document.uri.fsPath, document.getText(), position, abortOn(token));
            return info ? new vscode.Hover(new vscode.MarkdownString(info.markdown), toRange(info.range)) : next(document, position, token);
        },
        provideCompletionItem: async (document, position, context, token, next) => {
            const found = await completionAnswer(symbols(), document.uri.fsPath, document.getText(), position, abortOn(token));
            if (!found) {
                return next(document, position, context, token);
            }
            const items = found.items.map((s) => {
                const item = new vscode.CompletionItem(s.label, COMPLETION_KINDS[s.kind]);
                item.detail = s.detail;
                item.insertText = s.insertText ?? s.label;
                item.range = new vscode.Range(position.translate(0, -found.replaceLength), position);
                item.sortText = s.kind === 'field' || s.kind === 'value' ? s.label : undefined;
                return item;
            });
            return new vscode.CompletionList(items, false);
        },
    };
}

/** Registers references, rename, outline and signature help, and wires the symbol source to the engine. */
export function registerSemantic(context: vscode.ExtensionContext): void {
    source = new EngineSymbolsSource(
        () => engineHost()?.engine(),
        unsavedFor,
        engineLog,
    );
    const facts = {
        isWritable: (file: string): boolean => {
            try {
                fs.accessSync(file, fs.constants.W_OK);
                return true;
            } catch {
                return false;
            }
        },
        exists: (file: string): boolean => fs.existsSync(file),
    };
    context.subscriptions.push(
        vscode.languages.registerReferenceProvider(SELECTOR, {
            async provideReferences(document, position, ctx, token) {
                const found = await referencesAnswer(symbols(), document.uri.fsPath, position, ctx.includeDeclaration, abortOn(token));
                return found?.map((l) => new vscode.Location(vscode.Uri.file(l.file), toRange(l.range)));
            },
        }),
        vscode.languages.registerRenameProvider(SELECTOR, {
            async prepareRename(document, position, token) {
                const index = await symbols()?.index(document.uri.fsPath, abortOn(token));
                if (!index) {
                    throw new Error('The Loom engine is not available, so rename cannot find every use. Try again when it is running.');
                }
                const prepared = prepareRename(index, document.uri.fsPath, position);
                if ('reason' in prepared) {
                    throw new Error(prepared.reason);
                }
                return { range: toRange(prepared.range), placeholder: prepared.placeholder };
            },
            async provideRenameEdits(document, position, newName, token) {
                const index = await symbols()?.index(document.uri.fsPath, abortOn(token));
                if (!index) {
                    throw new Error('The Loom engine is not available, so rename cannot find every use.');
                }
                const plan = planRename(index, document.uri.fsPath, position, newName, facts);
                if (!plan.ok) {
                    throw new Error(plan.reason);
                }
                const edit = new vscode.WorkspaceEdit();
                for (const e of plan.edits) {
                    edit.replace(vscode.Uri.file(e.file), toRange(e.range), e.newText);
                }
                for (const r of plan.renames) {
                    edit.renameFile(vscode.Uri.file(r.from), vscode.Uri.file(r.to), { overwrite: false }, { label: 'Rename the prompt file with its name', needsConfirmation: true });
                }
                return edit;
            },
        }),
        vscode.languages.registerDocumentSymbolProvider(SELECTOR, {
            async provideDocumentSymbols(document, token) {
                return (await outlineAnswer(symbols(), document.uri.fsPath, abortOn(token)))?.map(toSymbol);
            },
        }),
        vscode.languages.registerSignatureHelpProvider(
            SELECTOR,
            {
                async provideSignatureHelp(document, position, token) {
                    const text = document.getText();
                    const end = offsetOf(text, position);
                    const sig = await signatureAnswer(symbols(), document.uri.fsPath, text.slice(Math.max(0, end - 400), end), abortOn(token));
                    if (!sig) {
                        return undefined;
                    }
                    const help = new vscode.SignatureHelp();
                    const info = new vscode.SignatureInformation(sig.label);
                    info.parameters = sig.parameters.map((p) => new vscode.ParameterInformation(p));
                    help.signatures = [info];
                    help.activeSignature = 0;
                    help.activeParameter = sig.active;
                    return help;
                },
            },
            '(',
            ',',
        ),
    );
}
