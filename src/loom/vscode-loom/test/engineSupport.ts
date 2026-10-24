import { EngineProcess } from '../src/engine/client';
import { encodeFrame, FrameDecoder } from '../src/engine/framing';

interface Sent {
    id?: number;
    method: string;
    params: Record<string, unknown>;
}

/** A `weave serve` that a test drives by hand: it records what the client sends and answers when told to. */
export class FakeEngineProcess implements EngineProcess {
    private dataListeners: Array<(chunk: Buffer) => void> = [];
    private stderrListeners: Array<(text: string) => void> = [];
    private exitListeners: Array<(code: number | null) => void> = [];
    private readonly decoder = new FrameDecoder();
    readonly sent: Sent[] = [];
    killed = false;

    write(bytes: Buffer): void {
        this.decoder.push(bytes).forEach((frame) => this.sent.push(JSON.parse(frame) as Sent));
    }
    onData(listener: (chunk: Buffer) => void): void {
        this.dataListeners.push(listener);
    }
    onStderr(listener: (text: string) => void): void {
        this.stderrListeners.push(listener);
    }
    onExit(listener: (code: number | null) => void): void {
        this.exitListeners.push(listener);
    }
    kill(): void {
        if (!this.killed) {
            this.killed = true;
            this.exit(null);
        }
    }

    // ---- driving it

    /** Requests (not notifications) sent for this method. */
    requests(method: string): Sent[] {
        return this.sent.filter((s) => s.method === method && s.id !== undefined);
    }
    last(method: string): Sent {
        const all = this.requests(method);
        return all[all.length - 1];
    }
    emit(message: unknown): void {
        this.emitBytes(encodeFrame(message));
    }
    emitBytes(bytes: Buffer): void {
        this.dataListeners.forEach((l) => l(bytes));
    }
    reply(id: number, result: unknown): void {
        this.emit({ jsonrpc: '2.0', id, result });
    }
    fail(id: number, code: number, kind: string, message = kind): void {
        this.emit({ jsonrpc: '2.0', id, error: { code, message, data: { kind } } });
    }
    /** Answers the handshake. */
    handshake(protocol = 1): void {
        this.reply(this.last('initialize').id as number, { protocol, weave: 'test', methods: [], roots: [] });
    }
    stderr(text: string): void {
        this.stderrListeners.forEach((l) => l(text));
    }
    exit(code: number | null): void {
        this.exitListeners.forEach((l) => l(code));
    }
}

/** A client with a fake process per start. */
export function processes(): { made: FakeEngineProcess[]; start: () => FakeEngineProcess } {
    const made: FakeEngineProcess[] = [];
    return {
        made,
        start: () => {
            const p = new FakeEngineProcess();
            made.push(p);
            return p;
        },
    };
}
