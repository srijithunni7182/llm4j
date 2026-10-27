import * as fs from 'fs';
import * as path from 'path';
import { parseIndex, Position, SymbolIndex } from '../src/semantic/symbols';
import { positionOf } from '../src/semantic/text';

const FIX = path.join(__dirname, '..', '..', 'test', 'fixtures', 'semantic');
export const SCRIPTS = path.join(FIX, 'scripts');

/** Real `weave symbols` output for a fixture script (scripts/regen-editor-fixtures.sh), with its folder put back. */
export function fixtureIndex(name: string): SymbolIndex {
    const raw = fs.readFileSync(path.join(FIX, `${name}.symbols.json`), 'utf8').split('@SCRIPTS@').join(SCRIPTS.split('\\').join('/'));
    const index = parseIndex(JSON.parse(raw));
    if (!index) {
        throw new Error(`fixture ${name} is not a symbols index`);
    }
    return index;
}

export function fixtureText(relative: string): string {
    return fs.readFileSync(path.join(SCRIPTS, relative), 'utf8');
}

/** The place `inside` characters into the `nth` occurrence of `needle` in `text`. */
export function at(text: string, needle: string, inside = 0, nth = 0): Position {
    let from = -1;
    for (let i = 0; i <= nth; i++) {
        from = text.indexOf(needle, from + 1);
        if (from < 0) {
            throw new Error(`"${needle}" occurrence ${nth} not found`);
        }
    }
    return positionOf(text, from + inside);
}

/** The text with `insert` typed at `place`, and the cursor after it: what the editor holds mid-typing. */
export function typed(text: string, place: Position, insert: string): { text: string; cursor: Position } {
    const lines = text.split('\n');
    let offset = 0;
    for (let l = 0; l < place.line; l++) {
        offset += lines[l].length + 1;
    }
    offset += place.character;
    const next = text.slice(0, offset) + insert + text.slice(offset);
    return { text: next, cursor: positionOf(next, offset + insert.length) };
}
