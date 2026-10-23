/**
 * Turning "the script before" and "the script after" into one edit the editor can apply: the smallest single replacement, found
 * by trimming what the two texts share at both ends. One edit means one undo step, and the cursor and the folds of the lines that did
 * not change stay where they were. Positions are what VS Code uses: 0-based lines, UTF-16 columns, and a line ends at \n, \r\n or \r.
 */

export interface TextEdit {
    startLine: number;
    startColumn: number;
    endLine: number;
    endColumn: number;
    newText: string;
}

export interface Position {
    line: number;
    column: number;
}

/** The line and column of an offset into `text`. */
export function positionAt(text: string, offset: number): Position {
    let line = 0;
    let lineStart = 0;
    for (let i = 0; i < offset && i < text.length; i++) {
        const c = text.charCodeAt(i);
        if (c === 13 /* \r */) {
            if (text.charCodeAt(i + 1) === 10 && i + 1 < offset) {
                i++;
            } else if (text.charCodeAt(i + 1) === 10) {
                // offset falls between \r and \n: the line has not ended yet in the editor's eyes
                return { line, column: i - lineStart };
            }
            line++;
            lineStart = i + 1;
        } else if (c === 10 /* \n */) {
            line++;
            lineStart = i + 1;
        }
    }
    return { line, column: Math.min(offset, text.length) - lineStart };
}

/** The offset of a line and column in `text`; a column past the end of the line is the end of the line. */
export function offsetAt(text: string, line: number, column: number): number {
    let l = 0;
    let i = 0;
    while (l < line && i < text.length) {
        const c = text.charCodeAt(i);
        if (c === 13 && text.charCodeAt(i + 1) === 10) {
            i += 2;
            l++;
        } else if (c === 13 || c === 10) {
            i++;
            l++;
        } else {
            i++;
        }
    }
    let end = i;
    while (end < text.length && text.charCodeAt(end) !== 10 && text.charCodeAt(end) !== 13) {
        end++;
    }
    return Math.min(i + column, end);
}

/** The one edit that turns `before` into `after`, or undefined when they are the same. */
export function singleEdit(before: string, after: string): TextEdit | undefined {
    if (before === after) {
        return undefined;
    }
    let start = 0;
    const max = Math.min(before.length, after.length);
    while (start < max && before.charCodeAt(start) === after.charCodeAt(start)) {
        start++;
    }
    // never split a surrogate pair: back up to the start of the character
    if (start > 0 && start < before.length && isLowSurrogate(before.charCodeAt(start)) && isHighSurrogate(before.charCodeAt(start - 1))) {
        start--;
    }
    // never cut between \r and \n
    if (start > 0 && before.charCodeAt(start - 1) === 13 && (before.charCodeAt(start) === 10 || after.charCodeAt(start) === 10)) {
        start--;
    }
    let endBefore = before.length;
    let endAfter = after.length;
    while (endBefore > start && endAfter > start && before.charCodeAt(endBefore - 1) === after.charCodeAt(endAfter - 1)) {
        endBefore--;
        endAfter--;
    }
    if (endBefore < before.length && isLowSurrogate(before.charCodeAt(endBefore)) && endBefore > 0 && isHighSurrogate(before.charCodeAt(endBefore - 1))) {
        endBefore++;
        endAfter++;
    }
    if (endBefore < before.length && before.charCodeAt(endBefore) === 10 && endBefore > 0 && before.charCodeAt(endBefore - 1) === 13) {
        endBefore++;
        endAfter++;
    }
    const from = positionAt(before, start);
    const to = positionAt(before, endBefore);
    return { startLine: from.line, startColumn: from.column, endLine: to.line, endColumn: to.column, newText: after.slice(start, endAfter) };
}

/** Applies an edit to a text (what the editor does), for checking that an edit does what it says. */
export function applyEdit(text: string, edit: TextEdit): string {
    const from = offsetAt(text, edit.startLine, edit.startColumn);
    const to = offsetAt(text, edit.endLine, edit.endColumn);
    return text.slice(0, from) + edit.newText + text.slice(to);
}

function isHighSurrogate(c: number): boolean {
    return c >= 0xd800 && c <= 0xdbff;
}

function isLowSurrogate(c: number): boolean {
    return c >= 0xdc00 && c <= 0xdfff;
}
