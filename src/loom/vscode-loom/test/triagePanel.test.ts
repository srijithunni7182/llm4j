import { strict as assert } from 'assert';
import { test } from 'node:test';
import { prepareForPanel, quickFixesFor } from '../src/review/triage';
import { finding, REVIEW_FIX, SAFE_FIX } from './triageSupport';
import { reviewFixture } from './reviewSupport';

test('the panel is sent the open findings with their actions, and the dismissed ones apart', () => {
    const review = reviewFixture('risky').review;
    const away = new Set([review.findings[3].fingerprint, review.findings[4].fingerprint]);
    const sent = prepareForPanel(review, away);
    assert.equal(sent.review.findings.length, review.findings.length - 2);
    assert.equal(sent.dismissed.length, 2);
    assert.ok(sent.review.findings.every((f) => (f as { actions?: unknown }).actions));
    assert.deepEqual(sent.review.findings.map((f) => f.rule), review.findings.filter((f) => !away.has(f.fingerprint)).map((f) => f.rule), 'the engine order is kept');
    assert.equal(sent.review.summary.total, review.summary.total, 'the engine\'s own counts are not rewritten');
});

test('the review passed to the panel is not changed in place', () => {
    const review = reviewFixture('risky').review;
    const before = JSON.stringify(review);
    prepareForPanel(review, new Set([review.findings[0].fingerprint]));
    assert.equal(JSON.stringify(review), before);
});

test('R6, A5: the lightbulb offers a safe fix first and preferred, then Explain and Suppress', () => {
    const specs = quickFixesFor('/p/a.loom', finding('LA05', { fix: SAFE_FIX }));
    assert.deepEqual(specs.map((s) => s.title), ['Fix: Add approve', 'Explain LA05', 'Suppress LA05 with a reason…']);
    assert.equal(specs[0].preferred, true);
    assert.deepEqual(specs[0].args, ['/p/a.loom', 'fp-LA05']);
    assert.equal(specs[0].command, 'loom.review.fixFinding');
});

test('R6: a fix to review is labelled "Review:" and is not the preferred action', () => {
    const specs = quickFixesFor('/p/a.loom', finding('LR01', { fix: REVIEW_FIX }));
    assert.equal(specs[0].title, 'Review: Add retry and timeout…');
    assert.equal(specs[0].preferred, false);
});

test('no fix means no Fix in the lightbulb; the trifecta has no Suppress', () => {
    assert.deepEqual(quickFixesFor('/p/a.loom', finding('LD02')).map((s) => s.title), ['Explain LD02', 'Suppress LD02 with a reason…']);
    assert.deepEqual(quickFixesFor('/p/a.loom', finding('LA01')).map((s) => s.title), ['Explain LA01']);
});
