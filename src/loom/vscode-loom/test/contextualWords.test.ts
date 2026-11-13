import { strict as assert } from 'assert';
import { test } from 'node:test';
import { contextualDoc } from '../src/lsp/contextualWords';

/** Phase 34, D17: a hover explains the new words where they are keywords, and says nothing where they are ordinary names. */
const at = (line: string, word: string): string | null => contextualDoc(word, line, line.indexOf(word) + 1);

test('D17: the new words have documentation where they are keywords', () => {
    const cases: Array<[string, string, RegExp]> = [
        ['    report { stage: "x" }', 'report', /Says where this workflow is/],
        ['workflow R(t) progress { stage: string } {', 'progress', /what it may `report`/],
        ['    supervise w { assert progress.done changes within 5m { cancel } }', 'progress', /never at what it says/],
        ['    supervise w { assert progress.done changes within 5m { cancel } }', 'changes', /at least that often/],
        ['    notify w "go" "text"', 'notify', /named signal/],
        ['    notify parent "stuck" "x"', 'parent', /started this one/],
        ['    notify w "ok" "yes" sticky', 'sticky', /latch/],
        ['    wait signal "go" timeout 5m -> t', 'signal', /In a child/],
        ['    wait w signal "found" timeout 5m -> t', 'signal', /In a parent/],
        ['    supervise w { when signal "stuck" { wrap_up } }', 'signal', /once for each arrival/],
        ['    wait any w timeout 5m -> e', 'any', /first of a signal/],
    ];
    for (const [line, word, expect] of cases) {
        const doc = at(line, word);
        assert.ok(doc && expect.test(doc), `${word} in: ${line}`);
    }
});

test('D17: as names the same words get no keyword documentation', () => {
    const cases: Array<[string, string]> = [
        ['    delegate "go" to A -> report', 'report'],
        ['    set progress = "half"', 'progress'],
        ['workflow Main(progress, report) {', 'progress'],
        ['    set notify = "a"', 'notify'],
        ['    set parent = "c"', 'parent'],
        ['    wait signal timeout 5m -> r', 'signal'],
        ['    wait any timeout 5m -> r', 'any'],
        ['    note "report { a: 1 } and wait any w timeout 1m"', 'report'],
        ['    set changes = "x"', 'changes'],
    ];
    for (const [line, word] of cases) assert.equal(at(line, word), null, `${word} in: ${line}`);
});
