import { GraphResult } from './model';
import { samePath } from './paths';

/**
 * The workflow to show first: the one the cursor is in (the last workflow in that file that starts at or above the
 * cursor line), else the first workflow of the entry file, else the first one there is. Returns undefined when the
 * script defines no workflows.
 */
export function chooseWorkflow(result: GraphResult, file: string, cursorLine: number): string | undefined {
    const inFile = result.workflows.filter((w) => samePath(w.file, file));
    const above = inFile.filter((w) => (w.line ?? 0) > 0 && (w.line as number) <= cursorLine);
    if (above.length > 0) {
        return above.reduce((best, w) => ((w.line as number) >= (best.line as number) ? w : best)).name;
    }
    const entry = result.workflows.find((w) => samePath(w.file, result.entry));
    return (entry ?? result.workflows[0])?.name;
}
