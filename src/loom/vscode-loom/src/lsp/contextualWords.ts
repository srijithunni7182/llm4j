/**
 * Hover for the words of phase 34 that are keywords only in the one shape each has (D17): {@code report},
 * {@code progress}, {@code notify}, {@code parent}, {@code sticky}, {@code signal}, {@code any} and
 * {@code changes}. Everywhere else they are ordinary names, so a hover over {@code -> report} says nothing
 * about a keyword. The patterns are the grammar's, applied to one line.
 */

interface Entry {
    /** Matches the word with the context that makes it a keyword; capture group 1 is the word. */
    readonly shape: RegExp;
    readonly doc: string;
}

const ENTRIES: Record<string, Entry[]> = {
    report: [{ shape: /(?<![.\w"])(report)(?=\s*\{)/g, doc: '**report** `{ field: value, ... }`\n\nSays where this workflow is, for whoever started it. The fields are the ones the workflow declared with `progress { }`; a value of the wrong type fails the step, and a report beyond 2000 characters (or the 1000th report) is dropped and recorded. The parent reads the latest as `handle.progress.field`, as unchecked text.' }],
    progress: [
        { shape: /(?<=\)\s{0,3})(progress)(?=\s*\{)/g, doc: '**progress** `{ field: type, ... }`\n\nAfter a workflow\'s parameters: what it may `report`, one flat level of `string`, `number`, `boolean` or `enum["a", "b"]`.' },
        { shape: /(?<=\bassert\s)(progress)(?=\.\w+\s+changes\b)/g, doc: '**assert progress.field changes within 5m** `{ actions }`\n\nIn a `supervise` block: fires when the child\'s reported field has not changed within the time (its running time; sleep does not count). It looks at whether the field moves, never at what it says.' },
    ],
    changes: [{ shape: /(?<=\bassert\s+progress\.\w+\s)(changes)(?=\s+within\b)/g, doc: '**changes** `within 5m`\n\nIn `assert progress.field changes within 5m { ... }`: the field must change at least that often while the child runs.' }],
    notify: [{ shape: /(?<![.\w"])(notify)(?=\s+[A-Za-z_][\w.-]*(?:\[[^\]]*\])?\s*")/g, doc: '**notify** `handle "name" ["text"] [sticky]` or `notify parent "name" ["text"] [sticky]`\n\nSends a named signal: a parent to a child it started, or a child to the workflow that started it. It is an entry in the receiver\'s journal, so one sent early is not lost and none is delivered twice. A message is read once, in order; `sticky` makes it a latch that stays set. The receiver reads the text as unchecked, and a signal never causes anything by itself. At most 16 unread per name, 64 in all, a 40-character name and 2000 characters of text.' }],
    parent: [{ shape: /(?<=\bnotify\s)(parent)(?=\s*")/g, doc: '**parent**\n\nIn `notify parent "name"`: the workflow that started this one. A child can signal only its parent. In a run nobody started the signal is dropped and recorded.' }],
    sticky: [{ shape: /(?<="\s+)(sticky)\b(?=\s*$)/g, doc: '**sticky**\n\nAfter a `notify`: the signal is a latch. It stays set, any wait for it returns at once without consuming it, and a later `notify` of the same name replaces it.' }],
    signal: [
        { shape: /(?<=\bwait\s)(signal)(?=\s*")/g, doc: '**wait signal** `"name" timeout 10m -> text`\n\nIn a child: waits for a signal from its parent. A timeout is required; the child holds no thread while it waits, and the parent\'s `notify` runs it again. If the time passes first, `text` raises an error when read (`text is ok` is false).' },
        { shape: /(?<=\bwait\s+[A-Za-z_][\w.-]*(?:\[[^\]]*\])?\s+)(signal)(?=\s*")/g, doc: '**wait handle signal** `"name" timeout 5m -> text`\n\nIn a parent: waits for a signal from one child. A timeout is required; it ends at once if the child has settled and sent nothing.' },
        { shape: /(?<=\bwhen\s)(signal)(?=\s*")/g, doc: '**when signal** `"name" { actions }`\n\nIn a `supervise` block: fires once for each arrival of that signal, never on what it says, and takes the message. The actions\' text stays fixed.' },
    ],
    any: [{ shape: /(?<=\bwait\s)(any)(?=\s+(?!timeout\b)[A-Za-z_])/g, doc: '**wait any** `handles timeout 10m -> event`\n\nReturns the first of a signal from any handle, a handle finishing, or the timeout. `event.kind` is `signal`, `finished` or `timeout`; `event.name` is the handle\'s key; `event.remaining` counts the children not yet reported. Signals come before finished children, each is reported once, and the first return is journaled.' }],
};

/** The documentation for {@code word} at column {@code character} of {@code line}, or null when it is an ordinary name there. */
export function contextualDoc(word: string, line: string, character: number): string | null {
    for (const entry of ENTRIES[word] ?? []) {
        entry.shape.lastIndex = 0;
        for (let m = entry.shape.exec(line); m; m = entry.shape.exec(line)) {
            const start = m.index + m[0].indexOf(m[1]);
            if (character >= start && character <= start + word.length) {
                return entry.doc;
            }
            if (m[0].length === 0) {
                entry.shape.lastIndex++;
            }
        }
    }
    return null;
}
