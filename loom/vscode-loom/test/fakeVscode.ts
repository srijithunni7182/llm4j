/** A small stand-in for the `vscode` module: enough of the API for the graph panel's glue code, recording what it is asked. */
import * as path from 'path';

type Listener<T> = (event: T) => void;

export class FakeDisposable {
    disposed = false;
    constructor(private readonly onDispose: () => void = () => undefined) {}
    dispose(): void {
        if (!this.disposed) {
            this.disposed = true;
            this.onDispose();
        }
    }
}

export class FakeEvent<T> {
    private listeners: Array<Listener<T>> = [];
    readonly subscribe = (listener: Listener<T>): FakeDisposable => {
        this.listeners.push(listener);
        return new FakeDisposable(() => { this.listeners = this.listeners.filter((l) => l !== listener); });
    };
    fire(event: T): void {
        [...this.listeners].forEach((l) => l(event));
    }
    get count(): number {
        return this.listeners.length;
    }
}

export class FakeWebviewPanel {
    html = '';
    iconPath: unknown;
    disposed = false;
    posted: any[] = [];
    revealed = 0;
    readonly received = new FakeEvent<unknown>();
    readonly disposeEvent = new FakeEvent<void>();
    readonly webview: any;
    constructor(readonly viewType: string, readonly title: string, readonly showOptions: any, readonly options: any) {
        const self = this;
        this.webview = {
            get html(): string { return self.html; },
            set html(value: string) { self.html = value; },
            cspSource: 'vscode-webview://test',
            asWebviewUri: (uri: { fsPath: string }) => ({ toString: () => 'vscode-webview://test/' + uri.fsPath }),
            onDidReceiveMessage: (listener: Listener<unknown>) => this.received.subscribe(listener),
            postMessage: (message: unknown) => { self.posted.push(message); return Promise.resolve(true); },
        };
    }
    onDidDispose(listener: () => void): FakeDisposable { return this.disposeEvent.subscribe(listener); }
    reveal(): void { this.revealed++; }
    dispose(): void {
        if (!this.disposed) {
            this.disposed = true;
            this.disposeEvent.fire();
        }
    }
    ofType(type: string): any[] { return this.posted.filter((m) => m.type === type); }
}

export function createFakeVscode(settings: Record<string, unknown>) {
    const state = {
        panels: [] as FakeWebviewPanel[],
        errors: [] as Array<{ message: string; actions: string[] }>,
        warnings: [] as string[],
        infos: [] as string[],
        executed: [] as Array<{ command: string; args: unknown[] }>,
        shown: [] as Array<{ file: string; options: any }>,
        clipboard: [] as string[],
        answers: new Map<string, string>(),
        activeEditor: undefined as any,
        save: new FakeEvent<{ uri: { fsPath: string } }>(),
        selection: new FakeEvent<any>(),
        activeEditorChanged: new FakeEvent<any>(),
        channels: [] as Array<{ name: string; lines: string[] }>,
    };
    class EventEmitter<T> {
        private readonly inner = new FakeEvent<T>();
        readonly event = this.inner.subscribe;
        fire(value?: T): void { this.inner.fire(value as T); }
    }
    class TreeItem {
        description?: string;
        command?: unknown;
        constructor(readonly label: string) {}
    }
    class Position { constructor(readonly line: number, readonly character: number) {} }
    class Range { constructor(readonly start: Position, readonly end: Position) {} }
    const Uri = {
        file: (p: string) => ({ fsPath: p, toString: () => 'file://' + p }),
        joinPath: (base: { fsPath: string }, ...parts: string[]) => Uri.file(path.join(base.fsPath, ...parts)),
    };
    const notify = (list: Array<{ message: string; actions: string[] }>) => (message: string, ...actions: string[]) => {
        list.push({ message, actions });
        return Promise.resolve(state.answers.get(message.split('.')[0]) ?? state.answers.get('*'));
    };
    const vscode = {
        Position, Range, Uri, EventEmitter, TreeItem,
        ViewColumn: { Active: -1, Beside: -2, One: 1, Two: 2 },
        window: {
            get activeTextEditor() { return state.activeEditor; },
            createWebviewPanel: (viewType: string, title: string, showOptions: unknown, options: unknown) => {
                const panel = new FakeWebviewPanel(viewType, title, showOptions, options);
                state.panels.push(panel);
                return panel;
            },
            showErrorMessage: notify(state.errors),
            showWarningMessage: (message: string) => { state.warnings.push(message); return Promise.resolve(undefined); },
            showInformationMessage: (message: string) => { state.infos.push(message); return Promise.resolve(undefined); },
            showTextDocument: (uri: { fsPath: string }, options: unknown) => { state.shown.push({ file: uri.fsPath, options }); return Promise.resolve(undefined); },
            onDidChangeTextEditorSelection: (listener: Listener<any>) => state.selection.subscribe(listener),
            onDidChangeActiveTextEditor: (listener: Listener<any>) => state.activeEditorChanged.subscribe(listener),
            createOutputChannel: (name: string) => {
                const channel = { name, lines: [] as string[] };
                state.channels.push(channel);
                return { show: () => undefined, clear: () => { channel.lines.length = 0; }, appendLine: (l: string) => channel.lines.push(l), append: (t: string) => channel.lines.push(t) };
            },
        },
        workspace: {
            getConfiguration: (section: string) => ({
                get: <T>(key: string, fallback: T): T => (settings[`${section}.${key}`] as T) ?? fallback,
            }),
            onDidSaveTextDocument: (listener: Listener<any>) => state.save.subscribe(listener),
        },
        commands: {
            executeCommand: (command: string, ...args: unknown[]) => { state.executed.push({ command, args }); return Promise.resolve(undefined); },
        },
        env: { clipboard: { writeText: (text: string) => { state.clipboard.push(text); return Promise.resolve(); } } },
    };
    return { vscode, state };
}
