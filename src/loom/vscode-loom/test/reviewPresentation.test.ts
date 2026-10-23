import { strict as assert } from 'assert';
import { test } from 'node:test';
import { codeLensFor, diagnosticsFor, DIAGNOSTIC_SOURCE, statusFor, typicalCost, worstCost } from '../src/review/presentation';
import { MUTE_KEY, RunNotice } from '../src/review/runNotice';
import { parseReviewMessage } from '../src/review/protocol';
import { atLeast, countsText, countsOf, parseReview } from '../src/review/model';
import { reviewFixture } from './reviewSupport';

const FILE = '/p/risky.loom';

test('A5, R10: findings become squiggles with source loom-review and the rule id as the code', () => {
    const { review } = reviewFixture('risky');
    const specs = diagnosticsFor(review, FILE, 'info', 40);
    assert.equal(specs.length, 10);
    assert.ok(specs.every((s) => s.source === DIAGNOSTIC_SOURCE && /^L[A-Z]\d\d$/.test(s.code)));
    const lr01 = specs.find((s) => s.code === 'LR01')!;
    assert.equal(lr01.line, 9, 'line 10 is index 9');
    assert.match(lr01.message, /retry or timeout/);
    assert.match(lr01.message, /\n/, 'the title, then why');
});

test('findings are advice: the worst are warnings, never errors', () => {
    const specs = diagnosticsFor(reviewFixture('risky').review, FILE, 'info', 40);
    assert.deepEqual([...new Set(specs.map((s) => s.level))].sort(), ['hint', 'information', 'warning']);
    assert.equal(specs.find((s) => s.severity === 'high')!.level, 'warning');
    assert.equal(specs.find((s) => s.severity === 'low')!.level, 'hint');
});

test('loom.review.minSeverity hides what is below it', () => {
    const { review } = reviewFixture('risky');
    assert.equal(diagnosticsFor(review, FILE, 'high', 40).length, 3);
    assert.equal(diagnosticsFor(review, FILE, 'medium', 40).length, 8);
});

test('a whole-script finding sits on line 1, and a line past the end of the buffer is clamped', () => {
    const { review } = reviewFixture('risky');
    const la03 = diagnosticsFor(review, FILE, 'info', 40).find((s) => s.code === 'LA03')!;
    assert.equal(la03.line, 0);
    const clamped = diagnosticsFor(review, FILE, 'info', 5);
    assert.ok(clamped.every((s) => s.line <= 4 && s.endLine <= 4));
});

test('findings of another file are not shown on this one', () => {
    const { review } = reviewFixture('risky');
    assert.equal(diagnosticsFor(review, '/p/other.loom', 'info', 40).length, 0);
});

test('R43, A3: the status bar says the open counts and the typical cost, and a tick when nothing is open', () => {
    const risky = statusFor(reviewFixture('risky').review, { failOn: 'high', dismissed: 0, stale: false });
    assert.match(risky.text, /^Loom ⚠ 3 high · 5 medium · .+\/run$/);
    assert.equal(risky.tone, 'warning');
    assert.match(risky.tooltip, /Open findings never block Run/);
    const clean = statusFor(reviewFixture('clean').review, { failOn: 'high', dismissed: 0, stale: false });
    assert.equal(clean.text, 'Loom ✓');
    assert.equal(clean.tone, 'ok');
});

test('R40, R43: a count of dismissed findings is always on the status bar; stale is in the tooltip; failOn none never turns it warning', () => {
    const s = statusFor(reviewFixture('triage').review, { failOn: 'none', dismissed: 2, stale: true });
    assert.match(s.text, /2 dismissed$/);
    assert.match(s.tooltip, /changed after this review/);
    assert.equal(s.tone, 'neutral');
});

test('R43: before any review the status bar is just the name, and a click opens the panel', () => {
    const s = statusFor(undefined, { failOn: 'high', dismissed: 0, stale: false });
    assert.equal(s.text, 'Loom');
});

test('A3, R1: a CodeLens above each workflow has its findings and cost, then Graph and Run', () => {
    const { review, graph } = reviewFixture('priced');
    const workflows = (graph as { workflows: Array<{ name: string; file: string; line: number }> }).workflows;
    const lens = codeLensFor(review, workflows, '/anywhere/priced.loom');
    assert.equal(lens.length, 3);
    assert.match(lens[0].title, /^Review: .+  ·  ~\$0\.0047 typical \/ \$.+ worst$/);
    assert.equal(lens[0].command, 'loom.review');
    assert.deepEqual(lens.slice(1).map((l) => l.title), ['Graph', 'Run']);
    assert.ok(lens.every((l) => l.line === workflows[0].line - 1));
});

test('a CodeLens before the first review is just "Review"; a clean script says no findings', () => {
    const { graph } = reviewFixture('clean');
    const workflows = (graph as { workflows: Array<{ name: string; file: string; line: number }> }).workflows;
    assert.equal(codeLensFor(undefined, workflows, '/p/clean.loom')[0].title, 'Review');
    assert.match(codeLensFor(reviewFixture('clean').review, workflows, '/p/clean.loom')[0].title, /^Review: no findings/);
});

test('costs read as money when priced and tokens when not', () => {
    assert.equal(typicalCost(reviewFixture('priced').review), '$0.0047');
    assert.match(typicalCost(reviewFixture('triage').review)!, /tok$/);
    assert.match(worstCost(reviewFixture('priced').review)!, /^\$/);
});

test('the review JSON is checked at the boundary: anything else is not a review', () => {
    assert.ok(parseReview(reviewFixture('risky').review));
    assert.equal(parseReview(null), undefined);
    assert.equal(parseReview({ findings: 'x', summary: {} }), undefined);
    assert.equal(parseReview({ findings: [{ rule: 'X', severity: 'catastrophic', title: 't', location: {} }], summary: {} }), undefined);
    assert.equal(atLeast('high', 'medium'), true);
    assert.equal(atLeast('low', 'medium'), false);
    assert.equal(countsText(countsOf(reviewFixture('risky').review)), '3 high · 5 medium · 2 low');
});

test('R42, A17: the run notice is offered once for an unchanged script, never when muted, never when nothing is open', async () => {
    const store = new Map<string, unknown>();
    const memory = { get: <T>(k: string) => store.get(k) as T | undefined, update: async (k: string, v: unknown) => { store.set(k, v); } };
    const notice = new RunNotice(memory);
    assert.equal(notice.shouldOffer(FILE, 'text', 0), false, 'nothing open');
    assert.equal(notice.shouldOffer(FILE, 'text', 4), true);
    assert.equal(notice.shouldOffer(FILE, 'text', 4), false, 'the same script, the same findings');
    assert.equal(notice.shouldOffer(FILE, 'text changed', 4), true, 'a changed script');
    assert.equal(notice.shouldOffer('/p/other.loom', 'text', 4), true, 'another script');
    notice.mute();
    assert.equal(store.get(MUTE_KEY), true);
    assert.equal(notice.shouldOffer(FILE, 'text again', 9), false, 'muted for this workspace');
});

test('the panel may send only the four messages the extension understands', () => {
    assert.deepEqual(parseReviewMessage({ type: 'ready' }), { type: 'ready' });
    assert.deepEqual(parseReviewMessage({ type: 'rerun' }), { type: 'rerun' });
    assert.deepEqual(parseReviewMessage({ type: 'reveal', file: 'a.loom', line: 3, beside: true }), { type: 'reveal', file: 'a.loom', line: 3, beside: true });
    assert.deepEqual(parseReviewMessage({ type: 'selectWorkflow', name: 'Main' }), { type: 'selectWorkflow', name: 'Main' });
    for (const bad of [null, 'x', {}, { type: 'exec', cmd: 'rm' }, { type: 'reveal', file: 'a', line: 0 }, { type: 'reveal', file: '', line: 1 }, { type: 'reveal', file: 'a', line: 1.5 }, { type: 'selectWorkflow' }]) {
        assert.equal(parseReviewMessage(bad), undefined, JSON.stringify(bad));
    }
});
