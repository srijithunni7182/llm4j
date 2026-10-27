import { Position } from './symbols';

/** Text positions and string state, for the parts of a request the index does not hold (what is being typed). */

export function offsetOf(text: string, at: Position): number {
    let offset = 0;
    for (let line = 0; line < at.line; line++) {
        const nl = text.indexOf('\n', offset);
        if (nl < 0) {
            return text.length;
        }
        offset = nl + 1;
    }
    return Math.min(offset + at.character, text.length);
}

export function positionOf(text: string, offset: number): Position {
    const before = text.slice(0, offset);
    const line = before.split('\n').length - 1;
    return { line, character: offset - (before.lastIndexOf('\n') + 1) };
}

/** Whether `offset` is inside a string literal (they may span lines) or a `//` comment, and where the string began. */
export function stateAt(text: string, offset: number): { inString: boolean; inComment: boolean; stringStart: number } {
    let inString = false;
    let stringStart = -1;
    for (let i = 0; i < offset; i++) {
        const c = text[i];
        if (inString) {
            if (c === '"') {
                inString = false;
            }
        } else if (c === '"') {
            inString = true;
            stringStart = i;
        } else if (c === '/' && text[i + 1] === '/') {
            const nl = text.indexOf('\n', i);
            if (nl < 0 || nl >= offset) {
                return { inString: false, inComment: true, stringStart: -1 };
            }
            i = nl;
        }
    }
    return { inString, inComment: false, stringStart };
}

/** The line up to the cursor with the contents of closed strings blanked, so a word inside a prompt is not read as syntax. */
export function maskedLinePrefix(text: string, offset: number): string {
    const lineStart = text.lastIndexOf('\n', offset - 1) + 1;
    const prefix = text.slice(lineStart, offset);
    let out = '';
    let inString = stateAt(text, lineStart).inString;
    for (const c of prefix) {
        if (c === '"') {
            inString = !inString;
            out += c;
        } else {
            out += inString ? ' ' : c;
        }
    }
    return out;
}
