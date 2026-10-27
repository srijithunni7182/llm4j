import { strict as assert } from 'assert';
import { test } from 'node:test';
import { mergeInstant, ReviewController, ReviewSettings, ReviewState } from '../src/review/controller';
import { FakeScheduler, settle } from './support';
import { ManualSource, reviewFixture } from './reviewSupport';

function setup(settings: ReviewSettings = { on: 'save' }, unsaved: Record<string, string> = {}) {
    const source = new ManualSource();
    const scheduler = new FakeScheduler();
    const states: ReviewState[] = [];
    const controller = new ReviewController({ source, scheduler, settings: () => settings, unsavedText: (f) => unsaved[f] });
    controller.onDidChange((s) => states.push(s));
    return { source, scheduler, states, controller, settings };
}
const F = '/p/risky.loom';

test('R9: the first review shows loading, then the result; a script with no result yet is loading, not empty', async () => {
    const t = setup();
    assert.equal(t.controller.state(F).status, 'loading');
    const done = t.controller.request(F);
    assert.equal(t.controller.state(F).status, 'loading');
    t.source.answer(0, 'risky');
    await done;
    const s = t.controller.state(F);
    assert.equal(s.status, 'idle');
    assert.equal(s.review?.findings.length, 10);
    assert.ok(s.graph);
});

test('R9: while a new review runs the previous result stays, marked running; it is never cleared to empty', async () => {
    const t = setup();
    const first = t.controller.request(F);
    t.source.answer(0, 'risky');
    await first;
    const second = t.controller.request(F);
    const during = t.controller.state(F);
    assert.equal(during.status, 'running');
    assert.equal(during.review?.findings.length, 10);
    t.source.answer(1, 'clean');
    await second;
    assert.equal(t.controller.state(F).review?.findings.length, 0);
    assert.equal(t.controller.state(F).status, 'idle');
});

test('R9: the result is marked stale the moment the text changes, and a fresh result clears it', async () => {
    const t = setup();
    const first = t.controller.request(F);
    t.source.answer(0, 'risky');
    await first;
    t.controller.changed(F);
    assert.equal(t.controller.state(F).status, 'stale');
    assert.equal(t.controller.state(F).review?.findings.length, 10, 'what was reviewed is still shown, as stale');
    const second = t.controller.request(F);
    t.source.answer(1, 'risky');
    await second;
    assert.equal(t.controller.state(F).status, 'idle');
});

test('R9: a change before any review has nothing to mark stale', () => {
    const t = setup();
    t.controller.changed(F);
    assert.equal(t.controller.state(F).status, 'loading');
});

test('R10: on save, the review runs after a save and not after an edit', async () => {
    const t = setup({ on: 'save' });
    t.controller.changed(F);
    t.scheduler.advance(1000);
    assert.equal(t.source.requests.length, 0);
    t.controller.saved(F);
    assert.equal(t.source.requests.length, 1);
    assert.equal(t.source.requests[0].tier, 'full');
});

test('R10: on type, an edit runs the quick review after 200 ms of quiet, and many edits run it once', async () => {
    const t = setup({ on: 'type' });
    t.controller.changed(F);
    t.scheduler.advance(150);
    t.controller.changed(F);
    t.scheduler.advance(150);
    assert.equal(t.source.requests.length, 0, 'the second edit restarted the wait');
    t.scheduler.advance(60);
    assert.equal(t.source.requests.length, 1);
    assert.equal(t.source.requests[0].tier, 'instant');
});

test('R10: manual means nothing runs by itself, not even on save', async () => {
    const t = setup({ on: 'manual' });
    t.controller.changed(F);
    t.controller.saved(F);
    t.scheduler.advance(5000);
    assert.equal(t.source.requests.length, 0);
    void t.controller.request(F);
    assert.equal(t.source.requests.length, 1);
});

test('R10: the quick review replaces the validity findings and keeps the other lenses of the last full review, as stale', async () => {
    const t = setup({ on: 'type' });
    const full = t.controller.request(F);
    t.source.answer(0, 'risky');
    await full;
    t.controller.changed(F);
    t.scheduler.advance(250);
    t.source.answer(1, 'clean');
    await settle();
    const s = t.controller.state(F);
    const risky = reviewFixture('risky').review;
    assert.equal(s.review?.findings.filter((f) => f.lens === 'validity').length, 0, 'validity is from the quick review');
    assert.equal(s.review?.findings.length, risky.findings.filter((f) => f.lens !== 'validity').length);
    assert.equal(s.status, 'stale', 'the other lenses are from the earlier text');
    assert.equal(s.tier, 'full');
});

test('mergeInstant: counts in the summary follow the findings', () => {
    const risky = reviewFixture('risky').review;
    const merged = mergeInstant(risky, reviewFixture('clean').review);
    assert.equal(merged.summary.total, merged.findings.length);
    assert.equal(merged.summary.high, merged.findings.filter((f) => f.severity === 'high').length);
});

test('R11: a newer request withdraws the older one, and the older one\'s late answer is dropped', async () => {
    const t = setup();
    const a = t.controller.request(F);
    const b = t.controller.request(F);
    assert.equal(t.source.requests[0].signal.aborted, true);
    t.source.answer(1, 'risky');
    await b;
    await a;
    assert.equal(t.controller.state(F).review?.findings.length, 10);
    t.source.answer(0, 'clean'); // a late answer for the older one
    await settle();
    assert.equal(t.controller.state(F).review?.findings.length, 10, 'the late answer changed nothing');
});

test('R9: a review that fails keeps the last good result and says why', async () => {
    const t = setup();
    const a = t.controller.request(F);
    t.source.answer(0, 'risky');
    await a;
    const b = t.controller.request(F);
    t.source.requests[1].reject(new Error('the engine did not answer'));
    await b;
    const s = t.controller.state(F);
    assert.equal(s.status, 'error');
    assert.match(s.message ?? '', /did not answer/);
    assert.equal(s.review?.findings.length, 10);
});

test('the text of an unsaved buffer and the settings go with the request', async () => {
    const t = setup({ on: 'save', lenses: 'security,cost', minSeverity: 'low' }, { [F]: 'unsaved' });
    void t.controller.request(F);
    assert.equal(t.source.requests[0].text, 'unsaved');
    assert.equal(t.source.requests[0].lenses, 'security,cost');
    assert.equal(t.source.requests[0].minSeverity, 'low');
});

test('each script has its own state', async () => {
    const t = setup();
    void t.controller.request('/p/a.loom');
    void t.controller.request('/p/b.loom');
    t.source.answer(0, 'risky');
    await settle();
    assert.equal(t.controller.state('/p/a.loom').status, 'idle');
    assert.equal(t.controller.state('/p/b.loom').status, 'loading');
});

test('closing a script drops its state and withdraws its request', async () => {
    const t = setup();
    void t.controller.request(F);
    t.controller.closed(F);
    assert.equal(t.source.requests[0].signal.aborted, true);
    assert.equal(t.controller.state(F).status, 'loading');
});

test('listeners hear every change, with a generation that counts up', async () => {
    const t = setup();
    const a = t.controller.request(F);
    t.source.answer(0, 'risky');
    await a;
    const gens = t.states.map((s) => s.generation);
    assert.deepEqual(gens, [...gens].sort((x, y) => x - y));
    assert.ok(new Set(gens).size === gens.length);
    t.controller.dispose();
});
