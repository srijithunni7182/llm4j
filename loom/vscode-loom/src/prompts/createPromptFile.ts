import { promptFilePath, promptReferences, promptsFolder, PromptReference, starterText } from './promptFiles';

/** What creating a prompt file needs from the editor and the disk; the command gives the real ones, tests give fakes. */
export interface CreatePromptHost {
    /** The script in the active editor, or undefined when there is none. */
    activeScript(): { file: string; text: string; cursorLine: number } | undefined;
    exists(file: string): boolean;
    write(file: string, content: string): void;
    open(file: string): Promise<void>;
    /** Asks which of several missing prompts to create; undefined when the person cancels. */
    pick(choices: string[]): Promise<string | undefined>;
    report(message: string, level: 'info' | 'warning' | 'error'): void;
}

/**
 * Creates the file for a prompt a script names and does not have yet: the one on the cursor's line, else the only missing one, else the one
 * the person picks. Never overwrites a file.
 */
export async function createPromptFile(host: CreatePromptHost): Promise<string | undefined> {
    const script = host.activeScript();
    if (!script || !script.file.endsWith('.loom')) {
        host.report('Loom: open a .loom file that names a prompt to create its file.', 'error');
        return undefined;
    }
    const folder = promptsFolder(script.file, script.text);
    const all = promptReferences(script.text);
    if (all.length === 0) {
        host.report('Loom: this script has no prompt: "…" line, so there is no prompt file to create.', 'info');
        return undefined;
    }
    const missing = all.filter((ref) => !host.exists(promptFilePath(folder, ref)) && !host.exists(promptFilePath(folder, { ...ref, version: undefined })));
    const unique = missing.filter((ref, i) => missing.findIndex((o) => o.id === ref.id && o.version === ref.version) === i);
    if (unique.length === 0) {
        host.report('Loom: every prompt this script names already has a file.', 'info');
        return undefined;
    }
    let chosen: PromptReference | undefined = unique.find((ref) => ref.line === script.cursorLine);
    if (!chosen && unique.length === 1) {
        chosen = unique[0];
    }
    if (!chosen) {
        const label = (ref: PromptReference): string => (ref.version ? `${ref.id}@${ref.version}` : ref.id);
        const picked = await host.pick(unique.map(label));
        chosen = unique.find((ref) => label(ref) === picked);
        if (!chosen) {
            return undefined;
        }
    }
    const file = promptFilePath(folder, chosen);
    if (host.exists(file)) {
        host.report(`Loom: ${file} already exists, so it was left as it is.`, 'warning');
        return file;
    }
    host.write(file, starterText(chosen));
    await host.open(file);
    return file;
}
