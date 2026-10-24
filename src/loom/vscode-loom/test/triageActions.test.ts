import { strict as assert } from 'assert';
import { test } from 'node:test';
import { FakeEngine } from '../src/engine/fake';
import { EngineRpcError } from '../src/engine/errors';
import { ApplyFixAnswer } from '../src/engine/protocol';
import { actionsFor, DismissStore, editOf, safeCount, TriageActions, withoutDismissed } from '../src/review/triage';
import { FakeEditHost, finding, memory, REVIEW_FIX, SAFE_FIX } from './triageSupport';
import { reviewFixture } from './reviewSupport';

const FILE = '/p/main.loom';
const fixed = (original: string, text: string, applied = 1, withheld: string[] = []): ApplyFixAnswer => ({ file: FILE, original, text, changed: original !== text, applied: Array.from({ length: applied }, (_, i) => ({ rule: 'LX', line: i + 1, label: 'x', pass: 1 })), withheld });

function setup() {
    const engine = new FakeEngine();
    const host = new FakeEditHost();
    const dismissals = new DismissStore(memory());
    const triage = new TriageActions(() => engine, host, dismissals);
    return { engine, host, dismissals, triage };
}

// ---- what is on offer for a finding (R35)

test('R35: a finding with a safe fix offers Fix; with a fix to review, Review fix…; with none, an Accept and a reason why not', () => {
    assert.equal(actionsFor(finding('LA05', { fix: SAFE_FIX })).fix, 'safe');
    assert.equal(actionsFor(finding('LR01', { fix: REVIEW_FIX })).fix, 'review');
    const none = actionsFor(finding('LD02'));
    assert.equal(none.fix, null);
    assert.match(none.note ?? '', /No automatic fix/);
    assert.equal(none.accept.allowed, true);
    assert.equal(none.explain, true);
});

test('R35: a finding that only has options is a fix to review, not a missing fix', () => {
    const f = finding('LR02', { options: [{ id: 'note', label: 'n', explain: '', safety: 'review', ask: [] }] });
    assert.equal(actionsFor(f).fix, 'review');
    assert.equal(actionsFor(f).options, true);
});

test('R38: the lethal trifecta cannot be accepted with a reason in a file: the button says why', () => {
    const a = actionsFor(finding('LA01'));
    assert.equal(a.accept.allowed, false);
    assert.match(a.accept.reason ?? '', /in the script itself/);
});

test('R40: errors in the script cannot be dismissed or accepted', () => {
    const e = finding('LV00', { lens: 'validity', severity: 'high' });
    assert.equal(actionsFor(e).dismiss.allowed, false);
    assert.equal(actionsFor(e).accept.allowed, false);
    assert.equal(actionsFor(finding('LV00', { lens: 'validity', severity: 'low' })).dismiss.allowed, true);
});

test('R36: "Fix all safe (n of m)" counts only the safe ones', () => {
    assert.deepEqual(safeCount([finding('A', { fix: SAFE_FIX }), finding('B', { fix: REVIEW_FIX }), finding('C')]), { safe: 1, total: 3 });
});

// ---- fixing

test('R6: a safe fix is applied at once, with no question, as one change', async () => {
    const t = setup();
    t.engine.on('loom/applyFix', () => fixed('a\n', 'a\nb\n'));
    const out = await t.triage.fixOne(FILE, finding('LA05', { fix: SAFE_FIX }));
    assert.equal(out.ok, true);
    assert.equal(t.host.confirmed.length, 0, 'no preview for a safe fix');
    assert.equal(t.host.applied.length, 1);
    assert.deepEqual(t.host.applied[0].map((c) => [c.file, c.before, c.after]), [[FILE, 'a\n', 'a\nb\n']]);
    assert.match(out.message, /^Fixed 1/);
});

test('R6: a fix that needs judgement is shown first, and cancelling applies nothing', async () => {
    const t = setup();
    t.engine.on('loom/applyFix', () => fixed('a\n', 'a\nb\n'));
    t.host.answerConfirm = false;
    const out = await t.triage.fixOne(FILE, finding('LR01', { fix: REVIEW_FIX }));
    assert.equal(out.ok, false);
    assert.equal(t.host.confirmed.length, 1);
    assert.equal(t.host.confirmed[0].change.after, 'a\nb\n');
    assert.equal(t.host.applied.length, 0);
});

test('R6, R36: Fix all safe applies only the safe fixes, shows the change once, and applies it as ONE edit', async () => {
    const t = setup();
    let asked: string[] = [];
    t.engine.on('loom/applyFix', (p) => { asked = p.fingerprints ?? []; return fixed('x', 'x y z', 3); });
    const findings = [finding('A', { fix: SAFE_FIX }), finding('B', { fix: SAFE_FIX }), finding('C', { fix: SAFE_FIX }), finding('D', { fix: REVIEW_FIX }), finding('E')];
    const out = await t.triage.fixAllSafe(FILE, findings);
    assert.deepEqual(asked, ['fp-A', 'fp-B', 'fp-C'], 'the review fix and the finding with no fix were not asked for');
    assert.equal(t.host.confirmed.length, 1, 'one preview');
    assert.equal(t.host.applied.length, 1, 'one edit, so one undo');
    assert.match(out.message, /^Fixed 3 \(2 need review\)/);
    assert.deepEqual(out.handled, ['fp-A', 'fp-B', 'fp-C']);
});

test('R36: Fix all safe with nothing safe says how many need judgement and does not call the engine', async () => {
    const t = setup();
    const out = await t.triage.fixAllSafe(FILE, [finding('D', { fix: REVIEW_FIX }), finding('E')]);
    assert.equal(out.ok, false);
    assert.match(out.message, /1 need your judgement/);
    assert.equal(t.engine.calls.length, 0);
});

test('R37: a fix that would withhold some is reported with how many were not applied', async () => {
    const t = setup();
    t.engine.on('loom/applyFix', () => fixed('a', 'ab', 1, ['LX at line 4: fix withheld, it adds a high finding']));
    const out = await t.triage.fixAllSafe(FILE, [finding('A', { fix: SAFE_FIX }), finding('B', { fix: SAFE_FIX })]);
    assert.match(out.message, /1 withheld/);
});

test('a fix that no longer applies says so, asks for a new review, and changes nothing', async () => {
    const t = setup();
    t.engine.on('loom/applyFix', () => fixed('a', 'a', 0, ['LX at line 2: fix withheld']));
    const out = await t.triage.fixOne(FILE, finding('LA05', { fix: SAFE_FIX }));
    assert.equal(out.ok, false);
    assert.equal(out.rerun, true);
    assert.match(out.message, /no longer applies/);
    assert.equal(t.host.applied.length, 0);
});

test('fault: when the script changed while the fix was prepared, the editor refuses and the review runs again', async () => {
    const t = setup();
    t.engine.on('loom/applyFix', () => fixed('a', 'ab'));
    t.host.refuseApply = true;
    const out = await t.triage.fixOne(FILE, finding('LA05', { fix: SAFE_FIX }));
    assert.equal(out.ok, false);
    assert.equal(out.rerun, true);
    assert.match(out.message, /changed while this was prepared/);
});

test('R37: a fix can be undone, and undo asks the editor to undo that file', async () => {
    const t = setup();
    t.engine.on('loom/applyFix', () => fixed('a', 'ab'));
    const out = await t.triage.fixOne(FILE, finding('LA05', { fix: SAFE_FIX }));
    await out.undo!();
    assert.deepEqual(t.host.undone, [[FILE]]);
});

test('a finding with no fix is refused without asking the engine', async () => {
    const t = setup();
    const out = await t.triage.fixOne(FILE, finding('LD02'));
    assert.equal(out.ok, false);
    assert.equal(t.engine.calls.length, 0);
});

test('an option is sent with its answers, is always shown first, and applied as one edit', async () => {
    const t = setup();
    let sent: unknown;
    t.engine.on('loom/applyFix', (p) => { sent = p; return fixed('a', 'a timeout 45s'); });
    const out = await t.triage.fixOption(FILE, finding('LR01', { fix: REVIEW_FIX }), 'timeout-only', { seconds: '45' });
    assert.equal(out.ok, true);
    assert.deepEqual((sent as { option: string; answers: unknown; fingerprints: string[] }).answers, { seconds: '45' });
    assert.equal((sent as { option: string }).option, 'timeout-only');
    assert.equal(t.host.confirmed.length, 1);
    assert.equal(t.host.applied.length, 1);
});

test('an option the engine refuses reports its reason and changes nothing', async () => {
    const t = setup();
    t.engine.failNext(new EngineRpcError(-32602, 'fix-refused', 'the answer for seconds must be a number'));
    const out = await t.triage.fixOption(FILE, finding('LR01', { fix: REVIEW_FIX }), 'timeout-only', { seconds: 'x' });
    assert.equal(out.ok, false);
    assert.match(out.message, /must be a number/);
    assert.equal(t.host.applied.length, 0);
});

// ---- accepting

test('R38: accepting writes review.properties with the lines the engine made, as one edit, and offers undo', async () => {
    const t = setup();
    let sent: any;
    t.engine.on('loom/accept', (p) => { sent = p; return { file: '/p/review.properties', existed: false, original: '', text: 'LR01.accept = finding fp "r"\n', written: 1, lines: ['LR01.accept = finding fp "r"'] }; });
    const out = await t.triage.accept(FILE, { ids: ['fp-LR01'], scope: 'finding', reason: 'intended' });
    assert.equal(out.ok, true);
    assert.deepEqual(sent.fingerprints, ['fp-LR01']);
    assert.equal(sent.reason, 'intended');
    assert.equal(t.host.applied[0][0].file, '/p/review.properties');
    assert.equal(t.host.applied[0][0].existed, false, 'the file is created');
    assert.equal(out.message, 'Accepted 1');
    assert.equal(out.rerun, true);
    await out.undo!();
    assert.deepEqual(t.host.undone, [['/p/review.properties']]);
});

test('R36: the message says the scope and the number', async () => {
    const t = setup();
    t.engine.on('loom/accept', () => ({ file: '/p/review.properties', existed: true, original: 'x\n', text: 'x\ny\n', written: 3, lines: [] }));
    assert.equal((await t.triage.accept(FILE, { ids: ['a', 'b', 'c'], scope: 'script', reason: 'r' })).message, 'Accepted 3 in this script');
    assert.equal((await t.triage.accept(FILE, { ids: ['a'], scope: 'project', reason: 'r' })).message, 'Accepted 3 in the project');
    assert.equal((await t.triage.accept(FILE, { ids: ['a'], scope: 'thing', reason: 'r', on: 'Replier' })).message, 'Accepted 3 on Replier');
});

test('R44 of phase 9: several serious findings need their number typed back, and the confirmation travels to the engine', async () => {
    const t = setup();
    t.engine.failNext(new EngineRpcError(-32602, 'confirm-count', 'this would accept 4 serious findings: confirm the number'));
    const refused = await t.triage.accept(FILE, { ids: ['a', 'b', 'c', 'd'], scope: 'script', reason: 'r' });
    assert.equal(refused.ok, false);
    assert.match(refused.message, /confirm the number/);
    let sent: any;
    t.engine.on('loom/accept', (p) => { sent = p; return { file: '/p/review.properties', existed: true, original: '', text: 'a\n', written: 4, lines: [] }; });
    await t.triage.accept(FILE, { ids: ['a', 'b', 'c', 'd'], scope: 'script', reason: 'r', confirm: 4 });
    assert.equal(sent.confirm, 4);
});

test('R38: the engine\'s refusal of the trifecta reaches the person in its own words', async () => {
    const t = setup();
    t.engine.failNext(new EngineRpcError(-32602, 'trifecta-in-script', 'LA01 (the lethal trifecta) is accepted in the script, not here'));
    const out = await t.triage.accept(FILE, { ids: ['fp-LA01'], scope: 'finding', reason: 'we know' });
    assert.equal(out.ok, false);
    assert.match(out.message, /accepted in the script/);
    assert.equal(t.host.applied.length, 0);
});

test('accepting what is already accepted says so', async () => {
    const t = setup();
    t.engine.on('loom/accept', () => ({ file: '/p/review.properties', existed: true, original: 'x\n', text: 'x\n', written: 0, lines: [] }));
    const out = await t.triage.accept(FILE, { ids: ['a'], scope: 'finding', reason: 'r' });
    assert.equal(out.ok, false);
    assert.match(out.message, /already accepted/);
});

test('a properties file that changed while the accept was prepared is not overwritten', async () => {
    const t = setup();
    t.engine.on('loom/accept', () => ({ file: '/p/review.properties', existed: true, original: 'x\n', text: 'x\ny\n', written: 1, lines: [] }));
    t.host.refuseApply = true;
    const out = await t.triage.accept(FILE, { ids: ['a'], scope: 'finding', reason: 'r' });
    assert.equal(out.ok, false);
    assert.match(out.message, /changed while this was prepared/);
});

// ---- the baseline

test('R39: the baseline says what it records and what stays open, shows the file, and writes it only on confirmation', async () => {
    const t = setup();
    t.engine.on('loom/baseline', () => ({ file: '/p/review.baseline.json', existed: false, text: '{}\n', recorded: 12, leftOut: 3 }));
    t.host.answerConfirm = false;
    const no = await t.triage.baseline(FILE, false);
    assert.equal(no.ok, false);
    assert.match(t.host.confirmed[0].title, /Record 12 findings as known\. 3 serious findings stay open/);
    assert.equal(t.host.applied.length, 0);
    t.host.answerConfirm = true;
    const yes = await t.triage.baseline(FILE, false);
    assert.equal(yes.ok, true);
    assert.match(yes.message, /Recorded 12 as known\. 3 serious findings stay open/);
    assert.equal(t.host.applied[0][0].file, '/p/review.baseline.json');
    assert.equal(yes.rerun, true);
});

// ---- dismissing (R40)

test('R40: dismissing is local: it never calls the engine or writes a file, and is undone at once', () => {
    const t = setup();
    const out = t.triage.dismiss(FILE, [finding('A'), finding('B')]);
    assert.equal(out.ok, true);
    assert.equal(out.message, 'Dismissed 2');
    assert.equal(t.engine.calls.length, 0);
    assert.equal(t.host.applied.length, 0);
    assert.equal(t.dismissals.count(FILE), 2);
    void out.undo!();
    assert.equal(t.dismissals.count(FILE), 0);
});

test('R40: errors cannot be dismissed, and the message says so; the rest of a mixed set are dismissed', () => {
    const t = setup();
    const err = finding('LV00', { lens: 'validity', severity: 'high' });
    assert.match(t.triage.dismiss(FILE, [err]).message, /cannot be dismissed/);
    const mixed = t.triage.dismiss(FILE, [err, finding('A')]);
    assert.equal(mixed.ok, true);
    assert.match(mixed.message, /Dismissed 1 \(1 error cannot be dismissed\)/);
});

test('R40, R37: dismissed findings stay counted and can be restored one by one or all at once', () => {
    const t = setup();
    const review = reviewFixture('risky').review;
    const dismissable = review.findings.filter((f) => actionsFor(f).dismiss.allowed).slice(0, 3);
    const ids = dismissable.map((f) => f.fingerprint);
    t.dismissals.dismiss(FILE, dismissable);
    const { open, dismissed } = withoutDismissed(review, t.dismissals.dismissed(FILE));
    assert.equal(dismissed, 3);
    assert.equal(open.length, review.findings.length - 3);
    assert.equal(t.triage.restore(FILE, [ids[0]]).message, 'Restored 1');
    assert.equal(t.dismissals.count(FILE), 2);
    assert.equal(t.triage.restoreAll(FILE).message, 'Restored 2');
    assert.equal(t.triage.restoreAll(FILE).message, 'Nothing was dismissed');
});

test('R40: what is dismissed is kept per script, in the editor\'s storage only, and forgotten when the finding is gone', () => {
    const mem = memory();
    const store = new DismissStore(mem);
    store.dismiss('/p/a.loom', [finding('A')]);
    store.dismiss('/p/b.loom', [finding('B')]);
    assert.deepEqual([...store.dismissed('/p/a.loom')], ['fp-A']);
    assert.deepEqual([...store.dismissed('/p/b.loom')], ['fp-B']);
    assert.equal(mem.store.size, 1, 'one key, in the workspace storage');
    store.prune('/p/a.loom', { ...reviewFixture('clean').review });
    assert.equal(store.count('/p/a.loom'), 0, 'its finding is gone');
    assert.equal(store.count('/p/b.loom'), 1);
});

test('without an engine every action that needs one says so and does nothing', async () => {
    const triage = new TriageActions(() => undefined, new FakeEditHost(), new DismissStore(memory()));
    for (const out of [
        await triage.fixOne(FILE, finding('A', { fix: SAFE_FIX })),
        await triage.fixAllSafe(FILE, [finding('A', { fix: SAFE_FIX })]),
        await triage.accept(FILE, { ids: ['a'], scope: 'finding', reason: 'r' }),
        await triage.baseline(FILE, false),
        await triage.fixOption(FILE, finding('A', { fix: REVIEW_FIX }), 'o', {}),
    ]) {
        assert.equal(out.ok, false);
        assert.match(out.message, /engine is not running/);
    }
});

test('editOf: a change is one edit that does what it says', () => {
    const e = editOf({ file: FILE, existed: true, before: 'a\nb\n', after: 'a\nX\nb\n' })!;
    assert.equal(e.startLine, 1);
    assert.equal(editOf({ file: FILE, existed: true, before: 'a', after: 'a' }), undefined);
});
