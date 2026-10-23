/** `Content-Length` framing, as the Language Server Protocol does it. */

export const MAX_FRAME_BYTES = 16 * 1024 * 1024;

export class FrameError extends Error {
    constructor(message: string) {
        super(message);
        this.name = 'FrameError';
    }
}

export function encodeFrame(message: unknown): Buffer {
    const body = Buffer.from(JSON.stringify(message), 'utf8');
    return Buffer.concat([Buffer.from(`Content-Length: ${body.length}\r\n\r\n`, 'ascii'), body]);
}

/**
 * Turns the bytes the process prints, in whatever pieces they arrive, into messages. A message split across chunks is held until it
 * is whole; several in one chunk come out together. A header it cannot read, or a message over the limit, is a {@link FrameError}:
 * the stream cannot be trusted to line up again.
 */
export class FrameDecoder {
    private buffer: Buffer = Buffer.alloc(0);

    push(chunk: Buffer): string[] {
        this.buffer = this.buffer.length === 0 ? chunk : Buffer.concat([this.buffer, chunk]);
        const out: string[] = [];
        for (;;) {
            const headerEnd = this.buffer.indexOf('\r\n\r\n');
            if (headerEnd < 0) {
                if (this.buffer.length > 8192) {
                    throw new FrameError('a header longer than 8192 bytes');
                }
                return out;
            }
            const header = this.buffer.subarray(0, headerEnd).toString('ascii');
            const m = /content-length:\s*(\d+)/i.exec(header);
            if (!m) {
                throw new FrameError(`a message without Content-Length: ${header.slice(0, 80)}`);
            }
            const length = Number(m[1]);
            if (length > MAX_FRAME_BYTES) {
                throw new FrameError(`a message of ${length} bytes; the limit is ${MAX_FRAME_BYTES}`);
            }
            const start = headerEnd + 4;
            if (this.buffer.length < start + length) {
                return out;
            }
            out.push(this.buffer.subarray(start, start + length).toString('utf8'));
            this.buffer = this.buffer.subarray(start + length);
        }
    }
}
