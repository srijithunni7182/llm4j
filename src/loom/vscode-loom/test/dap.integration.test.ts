import test from 'node:test';
import assert from 'node:assert/strict';
import { spawn } from 'child_process';
import * as fs from 'fs';
import * as os from 'os';
import * as path from 'path';
import { encodeFrame, FrameDecoder } from '../src/engine/framing';
import { available, classpath } from './realEngine';

// The real debug adapter (`weave dap`) from the Java module's classes, driven the way the editor drives it.
const SCRIPT = `agent A { model: "m" system: "x" }
workflow Main(topic) {
    delegate "Write about {topic}" to A -> draft
    alt (draft == "[mock answer]") {
        return "mock"
    } else {
        return "other"
    }
}
`;

class Editor {
    private readonly decoder = new FrameDecoder();
    private seq = 1;
    private readonly waiting: Array<(m: any) => void> = [];
    private readonly inbox: any[] = [];
    readonly child = spawn('java', ['-cp', classpath, 'io.github.llm4j.loom.cli.WeaveCLI', 'dap'], { stdio: ['pipe', 'pipe', 'pipe'] });

    constructor() {
        this.child.stdout.on('data', (chunk: Buffer) => {
            for (const text of this.decoder.push(chunk)) {
                const message = JSON.parse(text);
                const next = this.waiting.shift();
                if (next) {
                    next(message);
                } else {
                    this.inbox.push(message);
                }
            }
        });
    }

    private take(): Promise<any> {
        const queued = this.inbox.shift();
        if (queued) {
            return Promise.resolve(queued);
        }
        return new Promise((resolve) => this.waiting.push(resolve));
    }

    async request(command: string, args?: unknown): Promise<any> {
        const seq = this.seq++;
        this.child.stdin.write(encodeFrame({ seq, type: 'request', command, arguments: args }));
        for (;;) {
            const m = await this.take();
            if (m.type === 'response' && m.request_seq === seq) {
                return m;
            }
            this.events.push(m);
        }
    }

    readonly events: any[] = [];

    async event(name: string): Promise<any> {
        const have = this.events.findIndex((e) => e.event === name);
        if (have >= 0) {
            return this.events.splice(have, 1)[0];
        }
        for (;;) {
            const m = await this.take();
            if (m.event === name) {
                return m;
            }
            this.events.push(m);
        }
    }

    close(): void {
        this.child.kill();
    }
}

test('R38-R39: an editor debugs a workflow through the real adapter: entry, breakpoint, step, step back, variables, the end', { skip: !available }, async () => {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'loom-dap-'));
    const file = path.join(dir, 'w.loom');
    fs.writeFileSync(file, SCRIPT);
    const editor = new Editor();
    try {
        const init = await editor.request('initialize', { adapterID: 'loom' });
        assert.equal(init.body.supportsStepBack, true);
        assert.equal(init.body.supportsConditionalBreakpoints, true);
        const launched = await editor.request('launch', { script: file, input: { topic: 'cats' }, models: 'mock', effects: 'simulate' });
        assert.equal(launched.success, true, launched.message);
        await editor.event('initialized');
        const bps = await editor.request('setBreakpoints', { source: { path: file }, breakpoints: [{ line: 5 }] });
        assert.equal(bps.body.breakpoints[0].verified, true);
        await editor.request('configurationDone');
        assert.equal((await editor.event('stopped')).body.reason, 'entry');

        const stack = await editor.request('stackTrace', { threadId: 1 });
        assert.equal(stack.body.stackFrames[0].name, 'delegate A');
        assert.equal(stack.body.stackFrames[0].line, 3);

        await editor.request('continue', { threadId: 1 });
        assert.equal((await editor.event('stopped')).body.reason, 'breakpoint');
        const vars = await editor.request('variables', { variablesReference: 1 });
        const draft = vars.body.variables.find((v: { name: string }) => v.name === 'draft');
        assert.match(draft.value, /\[mock answer\]/);
        assert.match(draft.value, /delegate A/);

        await editor.request('stepBack', { threadId: 1 });
        await editor.event('stopped');
        const back = await editor.request('stackTrace', { threadId: 1 });
        assert.equal(back.body.stackFrames[0].line, 4);

        await editor.request('stepIn', { threadId: 1 });
        await editor.event('stopped');
        await editor.request('stepIn', { threadId: 1 });
        const done = await editor.event('terminated');
        assert.ok(done);
        const log = editor.events.filter((e) => e.event === 'output').map((e) => e.body.output).join('');
        assert.match(log, /The run completed/);
        assert.match(log, /Result: mock/);
        await editor.request('disconnect');
    } finally {
        editor.close();
        fs.rmSync(dir, { recursive: true, force: true });
    }
});

test('R40: the real adapter refuses live models without a cap and a confirmation', { skip: !available }, async () => {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'loom-dap-'));
    const file = path.join(dir, 'w.loom');
    fs.writeFileSync(file, SCRIPT);
    const editor = new Editor();
    try {
        await editor.request('initialize', {});
        const refused = await editor.request('launch', { script: file, models: 'live' });
        assert.equal(refused.success, false);
        assert.match(refused.message, /spend money/);
        const unconfirmed = await editor.request('launch', { script: file, models: 'live', maxTokens: 100 });
        assert.match(unconfirmed.message, /confirmed/);
    } finally {
        editor.close();
        fs.rmSync(dir, { recursive: true, force: true });
    }
});
