import { Suggestions, suggest } from './completion';
import { hoverAt, HoverInfo } from './hover';
import { definitionOf, elementAt, Location, outline, OutlineItem, referencesOf, signatureAt, Signature } from './queries';
import { SymbolsSource } from './source';
import { Position } from './symbols';

/**
 * What each feature answers, as plain data, from a source that may have nothing. `undefined` always means "I have nothing": the
 * editor's glue then lets the older answer stand (spec 10, R22: each feature degrades by itself).
 */

export async function definitionAnswer(source: SymbolsSource | undefined, file: string, at: Position, signal?: AbortSignal): Promise<Location[] | undefined> {
    const index = await source?.index(file, signal);
    if (!index) {
        return undefined;
    }
    const element = elementAt(index, file, at);
    const found = element ? definitionOf(index, element) : [];
    return found.length > 0 ? found : undefined;
}

export async function referencesAnswer(source: SymbolsSource | undefined, file: string, at: Position, includeDeclaration: boolean, signal?: AbortSignal): Promise<Location[] | undefined> {
    const index = await source?.index(file, signal);
    const element = index && elementAt(index, file, at);
    if (!index || !element || (!element.resolved && element.kind !== 'task')) {
        return undefined;
    }
    return referencesOf(index, element.key, includeDeclaration);
}

export async function hoverAnswer(source: SymbolsSource | undefined, file: string, text: string, at: Position, signal?: AbortSignal): Promise<HoverInfo | undefined> {
    const index = await source?.index(file, signal);
    return index ? hoverAt(index, file, text, at) : undefined;
}

export async function completionAnswer(source: SymbolsSource | undefined, file: string, text: string, at: Position, signal?: AbortSignal): Promise<Suggestions | undefined> {
    const index = await source?.index(file, signal);
    return index ? suggest(index, file, text, at) : undefined;
}

export async function outlineAnswer(source: SymbolsSource | undefined, file: string, signal?: AbortSignal): Promise<OutlineItem[] | undefined> {
    const index = await source?.index(file, signal);
    return index ? outline(index, file) : undefined;
}

export async function signatureAnswer(source: SymbolsSource | undefined, file: string, textBeforeCursor: string, signal?: AbortSignal): Promise<Signature | undefined> {
    const index = await source?.index(file, signal);
    return index ? signatureAt(index, textBeforeCursor) : undefined;
}
