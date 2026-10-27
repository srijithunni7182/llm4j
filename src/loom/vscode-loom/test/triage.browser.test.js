'use strict';
/*
 * R34 to R41 in a real page: the buttons a finding has (and none it does not), the group actions with their numbers, the Accept and
 * Review-fix dialogs, dismissing, the Handled section, toasts with Undo, the bulk bar and the keyboard. The page only sends messages;
 * what the extension does with them is tested in triageActions.test.ts.
 */
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('fs');
const os = require('os');
const path = require('path');
const { execSync } = require('child_process');
const { renderReviewHtml } = require('../out-test/src/review/html.js');
const { actionsFor } = require('../out-test/src/review/triage.js');

const MEDIA = path.join(__dirname, '..', 'media');
const FIX = path.join(__dirname, 'fixtures', 'review');
function fixture(name, edit) {
  const review = JSON.parse(fs.readFileSync(path.join(FIX, name + '.review.json'), 'utf8'));
  const graph = JSON.parse(fs.readFileSync(path.join(FIX, name + '.graph.json'), 'utf8'));
  if (edit) { edit(review); }
  review.findings.forEach((f) => { f.actions = actionsFor(f); });
  return { review, graph };
}
const byRule = (f, rule) => f.review.findings.find((x) => x.rule === rule);
function loadPlaywright() {
  for (const resolve of [() => require('playwright'), () => require(path.join(execSync('npm root -g').toString().trim(), 'playwright'))]) { try { return resolve(); } catch (e) { /* next */ } }
  return null;
}
const pw = loadPlaywright();
let browser, htmlFile;
test.before(async () => {
  if (!pw) { return; }
  try { browser = await pw.chromium.launch(); } catch (e) { browser = null; }
  htmlFile = path.join(os.tmpdir(), 'loom-review-triage-test.html');
  fs.writeFileSync(htmlFile, renderReviewHtml({ cspSource: 'file:', nonce: 'n', uri: (name) => 'file://' + path.join(MEDIA, name) }));
});
test.after(async () => { if (browser) { await browser.close(); } });

async function open(options = {}) {
  const context = await browser.newContext({ viewport: options.viewport || { width: 1440, height: 900 } });
  context.setDefaultTimeout(4000);
  const page = await context.newPage();
  const errors = [];
  page.on('pageerror', (e) => errors.push(e.message));
  page.on('console', (m) => { if (m.type() === 'error') { errors.push(m.text()); } });
  await page.addInitScript((initial) => { window.__posted = []; window.__state = initial; window.acquireVsCodeApi = () => ({ postMessage: (m) => window.__posted.push(JSON.parse(JSON.stringify(m))), getState: () => window.__state, setState: (s) => { window.__state = JSON.parse(JSON.stringify(s)); } }); }, options.state || null);
  await page.goto('file://' + htmlFile);
  await page.evaluate(() => { document.body.className = 'vscode-light'; document.documentElement.style.cssText = '--vscode-editor-background:#fff;--vscode-foreground:#3b3b3b;--vscode-font-family:sans-serif'; });
  return {
    page, errors, context,
    show: async (f, extra = {}) => { await page.evaluate((m) => window.postMessage(m, '*'), { type: 'review', review: f.review, graph: f.graph, status: 'idle', entry: '/p/x.loom', ...extra }); await page.waitForTimeout(80); },
    send: async (m) => { await page.evaluate((x) => window.postMessage(x, '*'), m); await page.waitForTimeout(60); },
    posted: () => page.evaluate(() => window.__posted),
    last: async () => { const p = await page.evaluate(() => window.__posted); return p[p.length - 1]; },
    clear: () => page.evaluate(() => { window.__posted.length = 0; }),
    texts: (selector) => page.$$eval(selector, (nodes) => nodes.map((n) => n.innerText.replace(/\s+/g, ' ').trim())),
    close: () => context.close()
  };
}
const skip = (t) => { if (!pw || !browser) { t.skip('playwright or a browser is not installed'); return true; } return false; };
const withSafeFix = (rule) => (review) => { const f = review.findings.find((x) => x.rule === rule); f.fix = { safety: 'safe', label: 'Add an approval', edits: [{ file: 'x.loom', startLine: 5, startColumn: 1, endLine: 5, endColumn: 1, newText: '  approve: Mailer\n' }] }; };

test('R35: the buttons of a finding follow what can be done: a fix to review says Review fix…, none says why, and no Fix button is ever dead', async (t) => {
  if (skip(t)) { return; }
  const p = await open();
  const f = fixture('risky');
  await p.show(f);
  const buttonsOf = async (fp) => p.page.$$eval(`[data-finding="${fp}"] .fd-acts button`, (n) => n.map((b) => [b.textContent, b.disabled]));
  const lr01 = await buttonsOf(byRule(f, 'LR01').fingerprint);
  assert.deepEqual(lr01.map((b) => b[0]), ['Review fix…', 'Accept…', 'Dismiss', 'Explain']);
  const la01 = await buttonsOf(byRule(f, 'LA01').fingerprint);
  assert.deepEqual(la01.map((b) => b[0]), ['Accept…', 'Dismiss', 'Explain'], 'no Fix button at all without a fix');
  assert.equal(la01[0][1], true, 'the trifecta cannot be accepted this way');
  assert.match(await p.page.getAttribute(`[data-finding="${byRule(f, 'LA01').fingerprint}"] [data-act="accept"]`, 'title'), /in the script itself/);
  assert.match((await p.texts(`[data-finding="${byRule(f, 'LD02')?.fingerprint ?? byRule(f, 'LA02').fingerprint}"] .fd-acts`))[0], /No automatic fix: see the hint\./);
  await p.close();
});

test('R35: a safe fix has a Fix button that sends the fix at once', async (t) => {
  if (skip(t)) { return; }
  const p = await open();
  const f = fixture('risky', withSafeFix('LA02'));
  await p.show(f);
  const fp = byRule(f, 'LA02').fingerprint;
  assert.deepEqual(await p.page.$$eval(`[data-finding="${fp}"] .fd-acts button`, (n) => n.map((b) => b.textContent)), ['Fix', 'Accept…', 'Dismiss', 'Explain']);
  await p.clear();
  await p.page.click(`[data-finding="${fp}"] [data-act="fix"]`);
  assert.deepEqual(await p.last(), { type: 'fix', ids: [fp] });
  await p.close();
});

test('R36: group actions state the number first: "Fix all safe (n of m)" counts only the safe ones and is off, with a reason, when there are none', async (t) => {
  if (skip(t)) { return; }
  const p = await open();
  const f = fixture('risky', withSafeFix('LA02'));
  await p.show(f);
  const medium = (await p.texts('.gh [data-act="group-fix"]'));
  assert.deepEqual(medium, ['Fix all safe (0 of 3)', 'Fix all safe (1 of 5)', 'Fix all safe (0 of 2)']);
  const high = await p.page.$('.gh [data-act="group-fix"]');
  assert.equal(await high.isDisabled(), true);
  assert.match(await high.getAttribute('title'), /No finding in this group has a safe fix/);
  await p.clear();
  await p.page.click('.gh.s-med [data-act="group-fix"]');
  assert.deepEqual(await p.last(), { type: 'fix', ids: [byRule(f, 'LA02').fingerprint] });
  await p.close();
});

test('R40: Dismiss takes the finding out of the list at once and tells the extension; errors cannot be dismissed', async (t) => {
  if (skip(t)) { return; }
  const p = await open();
  const f = fixture('risky');
  await p.show(f);
  const lr01 = byRule(f, 'LR01');
  await p.clear();
  await p.page.click(`[data-finding="${lr01.fingerprint}"] [data-act="dismiss"]`);
  assert.deepEqual(await p.last(), { type: 'dismiss', ids: [lr01.fingerprint] });
  assert.equal(await p.page.$$eval(`[data-finding="${lr01.fingerprint}"]`, (n) => n.length), 0, 'gone from the list');
  assert.equal((await p.texts('#fcount'))[0], '9 open');
  const lv = f.review.findings.find((x) => x.lens === 'validity' && x.severity === 'high');
  assert.equal(await p.page.isDisabled(`[data-finding="${lv.fingerprint}"] [data-act="dismiss"]`), true);
  await p.close();
});

test('R37: when the extension says an action failed, what was taken out comes back', async (t) => {
  if (skip(t)) { return; }
  const p = await open();
  const f = fixture('risky');
  await p.show(f);
  await p.page.click(`[data-finding="${byRule(f, 'LR01').fingerprint}"] [data-act="dismiss"]`);
  assert.equal((await p.texts('#fcount'))[0], '9 open');
  await p.send({ type: 'toast', text: 'could not dismiss', ok: false });
  assert.equal((await p.texts('#fcount'))[0], '10 open');
  assert.match((await p.texts('#toasts'))[0], /could not dismiss/);
  await p.close();
});

test('R37: a toast says what happened, offers Undo while it can be undone, and Undo sends the token', async (t) => {
  if (skip(t)) { return; }
  const p = await open();
  await p.show(fixture('risky'));
  await p.send({ type: 'toast', text: 'Fixed 5', ok: true, token: 7 });
  assert.match((await p.texts('#toasts'))[0], /^Fixed 5 Undo ✕$/);
  assert.equal(await p.page.getAttribute('#toasts .toast', 'role'), 'status');
  await p.clear();
  await p.page.click('#toasts [data-act="undo"]');
  assert.deepEqual(await p.last(), { type: 'undo', token: 7 });
  assert.equal(await p.page.$$eval('#toasts .toast', (n) => n.length), 0, 'the toast goes once undone');
  await p.send({ type: 'toast', text: 'It failed', ok: false });
  assert.equal(await p.page.getAttribute('#toasts .toast', 'role'), 'alert');
  await p.close();
});

test('R36: Accept… opens a dialog with the scope, its number and a reason; nothing is sent without a reason', async (t) => {
  if (skip(t)) { return; }
  const p = await open();
  const f = fixture('risky');
  await p.show(f);
  const lr01 = byRule(f, 'LR01');
  await p.page.click(`[data-finding="${lr01.fingerprint}"] [data-act="accept"]`);
  assert.equal(await p.page.getAttribute('#dialog', 'role'), 'dialog');
  assert.equal(await p.page.getAttribute('#dialog', 'aria-modal'), 'true');
  const scopes = await p.page.$$eval('#dialog [data-scope]', (n) => n.map((b) => [b.getAttribute('data-scope'), b.innerText.replace(/\s+/g, ' ').trim()]));
  assert.deepEqual(scopes.map((s) => s[0]), ['finding', 'thing', 'script', 'project']);
  assert.match(scopes[0][1], /Just this finding.*1 finding/);
  assert.match(scopes[2][1], /LR01 anywhere in this script.*1 finding/);
  await p.clear();
  await p.page.click('#acceptGo');
  assert.match((await p.texts('#dialog [role="alert"]'))[0], /Say why this is fine/);
  assert.equal((await p.posted()).length, 0, 'nothing was sent');
  await p.close();
});

test('R36: reasons can be picked from presets or typed; a quote is refused; "will fix later" asks until when; the message carries scope, reason and date', async (t) => {
  if (skip(t)) { return; }
  const p = await open();
  const f = fixture('risky');
  await p.show(f);
  const lr01 = byRule(f, 'LR01');
  await p.page.click(`[data-finding="${lr01.fingerprint}"] [data-act="accept"]`);
  await p.page.fill('#acceptReason', 'he said "no"');
  await p.page.click('#acceptGo');
  assert.match((await p.texts('#dialog [role="alert"]'))[0], /double quote/);
  await p.page.click('[data-preset="later"]');
  assert.ok(await p.page.isVisible('#acceptUntil'));
  await p.page.fill('#acceptUntil', '2099-12-31');
  await p.page.click('[data-scope="script"]');
  await p.clear();
  await p.page.click('#acceptGo');
  assert.deepEqual(await p.last(), { type: 'accept', ids: [lr01.fingerprint], scope: 'script', reason: 'Will fix later.', until: '2099-12-31' });
  assert.equal(await p.page.$$eval('#dialog', (n) => n.length), 0, 'the dialog closed');
  assert.equal(await p.page.$$eval(`[data-finding="${lr01.fingerprint}"]`, (n) => n.length), 0, 'and the finding left the list');
  await p.close();
});

test('R44 of phase 9: accepting several serious findings needs their number typed back', async (t) => {
  if (skip(t)) { return; }
  const p = await open();
  const f = fixture('risky');
  await p.show(f);
  const group = await p.page.$$('.gh [data-act="group-accept"]');
  await group[1].click(); // the medium group: 5 findings, some in the security lens
  assert.match((await p.texts('#dialog'))[0], /Accept 5 findings/i);
  assert.match((await p.texts('#dialog'))[0], /accepts 5 serious findings/);
  await p.page.fill('#acceptReason', 'reviewed');
  await p.page.click('#acceptGo');
  assert.match((await p.texts('#dialog [role="alert"]'))[0], /Type 5 to confirm/);
  await p.clear();
  await p.page.fill('#acceptConfirm', '5');
  await p.page.click('#acceptGo');
  const sent = await p.last();
  assert.equal(sent.type, 'accept');
  assert.equal(sent.ids.length, 5);
  assert.equal(sent.confirm, 5);
  await p.close();
});

test('the dialog traps the keyboard, closes on Escape and gives the focus back', async (t) => {
  if (skip(t)) { return; }
  const p = await open();
  const f = fixture('risky');
  await p.show(f);
  const trigger = `[data-finding="${byRule(f, 'LR01').fingerprint}"] [data-act="accept"]`;
  await p.page.focus(trigger);
  await p.page.keyboard.press('Enter');
  assert.ok(await p.page.isVisible('#dialog'));
  for (let i = 0; i < 12; i++) { await p.page.keyboard.press('Tab'); }
  assert.ok(await p.page.evaluate(() => document.activeElement.closest('#dialog') !== null), 'the focus stayed inside');
  await p.page.keyboard.press('Escape');
  assert.equal(await p.page.$$eval('#dialog', (n) => n.length), 0);
  assert.ok(await p.page.evaluate((sel) => document.activeElement === document.querySelector(sel), trigger), 'and returned to the button');
  await p.close();
});

test('R35: Review fix… shows the ways to fix it, with their questions, and sends the chosen option with the answers', async (t) => {
  if (skip(t)) { return; }
  const p = await open();
  const f = fixture('risky');
  await p.show(f);
  const lr01 = byRule(f, 'LR01');
  await p.page.click(`[data-finding="${lr01.fingerprint}"] [data-act="review-fix"]`);
  const options = await p.page.$$eval('#dialog [data-option]', (n) => n.map((b) => b.getAttribute('data-option')));
  assert.deepEqual(options, ['default', 'retry-timeout', 'timeout-only']);
  await p.page.click('[data-option="timeout-only"]');
  assert.ok(await p.page.isVisible('[data-ask="seconds"]'));
  await p.page.fill('[data-ask="seconds"]', '45');
  await p.clear();
  await p.page.click('#fixGo');
  assert.deepEqual(await p.last(), { type: 'fixOption', id: lr01.fingerprint, option: 'timeout-only', answers: { seconds: '45' } });
  await p.close();
});

test('R35: Review fix… with the default chosen sends an ordinary fix', async (t) => {
  if (skip(t)) { return; }
  const p = await open();
  const f = fixture('risky');
  await p.show(f);
  const lr01 = byRule(f, 'LR01');
  await p.page.click(`[data-finding="${lr01.fingerprint}"] [data-act="review-fix"]`);
  await p.clear();
  await p.page.click('#fixGo');
  assert.deepEqual(await p.last(), { type: 'fix', ids: [lr01.fingerprint] });
  await p.close();
});

test('R39: "Accept all existing…" is in the toolbar, prominent when there are many findings, and asks what it will include', async (t) => {
  if (skip(t)) { return; }
  const p = await open();
  await p.show(fixture('many'));
  assert.match(await p.page.getAttribute('#acceptAll', 'class'), /primary/);
  await p.page.click('#acceptAll');
  assert.match((await p.texts('#dialog'))[0], /records the 240 open findings as known in review\.baseline\.json/);
  assert.ok(await p.page.isVisible('#baselineSerious'));
  await p.clear();
  await p.page.click('#baselineGo');
  assert.deepEqual(await p.last(), { type: 'baseline', includeSerious: false });
  await p.page.click('#acceptAll');
  await p.page.check('#baselineSerious');
  await p.page.click('#baselineGo');
  assert.deepEqual(await p.last(), { type: 'baseline', includeSerious: true });
  await p.show(fixture('risky'));
  assert.doesNotMatch(await p.page.getAttribute('#acceptAll', 'class'), /primary/, 'not prominent for a small script');
  await p.close();
});

test('R36: the toolbar\'s Fix all safe states the number and is off, with a reason, when nothing shown is safe', async (t) => {
  if (skip(t)) { return; }
  const p = await open();
  await p.show(fixture('risky'));
  assert.equal((await p.texts('#fixAll'))[0], 'Fix all safe (0 of 10)');
  assert.equal(await p.page.isDisabled('#fixAll'), true);
  assert.match(await p.page.getAttribute('#fixAll', 'title'), /No finding shown has a safe fix/);
  await p.show(fixture('risky', withSafeFix('LA02')));
  assert.equal((await p.texts('#fixAll'))[0], 'Fix all safe (1 of 10)');
  await p.clear();
  await p.page.click('#fixAll');
  assert.equal((await p.last()).type, 'fix');
  await p.close();
});

test('R37: Handled counts what was accepted, dismissed and baselined, lists them on request, and restores what was dismissed', async (t) => {
  if (skip(t)) { return; }
  const p = await open();
  const f = fixture('risky');
  const dismissed = [f.review.findings[3]];
  f.review.findings = f.review.findings.filter((x) => x !== dismissed[0]);
  f.review.accepted = [{ ...f.review.findings[4], reason: 'intended', by: 'dev', until: '2099-01-01' }];
  f.review.summary.accepted = 1;
  await p.show(f, { dismissed });
  assert.match((await p.texts('#handled'))[0], /Handled 1 accepted · 1 dismissed · 0 baselined/i);
  await p.page.click('#handled .twist');
  await p.page.waitForTimeout(60);
  const text = (await p.texts('#handled'))[0];
  assert.match(text, /“intended” · dev · until 2099-01-01/);
  assert.match(text, /Dismissed on this machine/i);
  await p.clear();
  await p.page.click('#handled [data-act="restore"]');
  assert.deepEqual(await p.last(), { type: 'restore', ids: [dismissed[0].fingerprint] });
  await p.page.click('#handled [data-act="restore-all"]');
  assert.deepEqual(await p.last(), { type: 'restoreAll' });
  await p.close();
});

test('R41: F, A, D and E act on the focused finding; Space selects it; the keys do nothing where the action does not exist', async (t) => {
  if (skip(t)) { return; }
  const p = await open();
  const f = fixture('risky', withSafeFix('LA02'));
  await p.show(f);
  const focus = async (rule) => { await p.page.focus(`[data-finding="${byRule(f, rule).fingerprint}"]`); };
  await focus('LA02');
  await p.clear();
  await p.page.keyboard.press('f');
  assert.deepEqual(await p.last(), { type: 'fix', ids: [byRule(f, 'LA02').fingerprint] });
  await focus('LR01');
  await p.page.keyboard.press('e');
  assert.deepEqual(await p.last(), { type: 'explain', rule: 'LR01' });
  await p.page.keyboard.press('f');
  assert.ok(await p.page.isVisible('#dialog'), 'F on a fix to review opens the dialog');
  await p.page.keyboard.press('Escape');
  await focus('LA01');
  await p.clear();
  await p.page.keyboard.press('a');
  assert.equal(await p.page.$$eval('#dialog', (n) => n.length), 0, 'the trifecta cannot be accepted: A does nothing');
  await p.page.keyboard.press('f');
  assert.equal((await p.posted()).length, 0, 'and has no fix: F does nothing');
  await focus('LR01');
  await p.page.keyboard.press('d');
  assert.equal((await p.last()).type, 'dismiss');
  await p.close();
});

test('R41: the keys are listed where a person looks, and the actions are named for a screen reader', async (t) => {
  if (skip(t)) { return; }
  const p = await open();
  await p.show(fixture('risky'));
  assert.match((await p.texts('#footer'))[0], /Advisory/);
  const names = await p.page.$$eval('#flist [data-act]', (n) => n.map((b) => b.textContent));
  assert.ok(names.every((x) => x.trim().length > 0), 'every button has a name');
  await p.close();
});

test('List: a checkbox per row, a select-all, and a bulk bar that states what the selection can do', async (t) => {
  if (skip(t)) { return; }
  const p = await open();
  const f = fixture('risky', withSafeFix('LA02'));
  await p.show(f);
  await p.page.click('[data-layout="list"]');
  const boxes = await p.page.$$eval('#flist .trow .cb', (n) => n.length);
  assert.equal(boxes, 10);
  assert.equal(await p.page.getAttribute('#table .thead .cb', 'aria-checked'), 'false');
  await p.page.click(`[data-finding="${byRule(f, 'LA02').fingerprint}"] .cb`);
  await p.page.click(`[data-finding="${byRule(f, 'LR01').fingerprint}"] .cb`);
  await p.page.waitForTimeout(60);
  assert.match((await p.texts('#bulk'))[0], /^2 selected 1 of 2 have a safe fix Fix 1 Accept 2… Dismiss 2 Clear$/);
  assert.equal(await p.page.getAttribute('#table .thead .cb', 'aria-checked'), 'mixed');
  await p.clear();
  await p.page.click('#bulk [data-act="bulk-dismiss"]');
  assert.equal((await p.last()).ids.length, 2);
  await p.close();
});

test('List: select all, clear, and Space on a row', async (t) => {
  if (skip(t)) { return; }
  const p = await open();
  await p.show(fixture('risky'));
  await p.page.click('[data-layout="list"]');
  await p.page.click('#table .thead .cb');
  assert.equal(await p.page.getAttribute('#table .thead .cb', 'aria-checked'), 'true');
  assert.match((await p.texts('#bulk'))[0], /^10 selected/);
  await p.page.click('#bulk [data-act="bulk-clear"]');
  assert.equal(await p.page.$$eval('#bulk', (n) => n.length), 0);
  const first = await p.page.$('#flist .trow');
  await first.focus();
  await p.page.keyboard.press(' ');
  await p.page.waitForTimeout(60);
  assert.match((await p.texts('#bulk'))[0], /^1 selected/);
  await p.close();
});

test('the selection survives a switch of layout and is dropped for a finding that is gone', async (t) => {
  if (skip(t)) { return; }
  const p = await open();
  const f = fixture('risky');
  await p.show(f);
  await p.page.click('[data-layout="list"]');
  await p.page.click(`[data-finding="${byRule(f, 'LR01').fingerprint}"] .cb`);
  await p.page.click('[data-layout="split"]');
  await p.page.click('[data-layout="list"]');
  assert.match((await p.texts('#bulk'))[0], /^1 selected/);
  f.review.findings = f.review.findings.filter((x) => x.rule !== 'LR01');
  await p.show(f);
  assert.equal(await p.page.$$eval('#bulk', (n) => n.length), 0);
  await p.close();
});

test('hostile text in a finding is text in every button, row and dialog', async (t) => {
  if (skip(t)) { return; }
  const p = await open();
  const f = fixture('risky');
  const hostile = '<img src=x onerror="window.__pwned=1">';
  f.review.findings.forEach((x) => { x.title = hostile; x.why = hostile; x.fixHint = hostile; });
  await p.show(f);
  await p.page.click('#flist [data-act="accept"]:not([disabled])');
  assert.equal(await p.page.$$eval('#dialog img, #flist img', (n) => n.length), 0);
  assert.equal(await p.page.evaluate(() => window.__pwned), undefined);
  assert.deepEqual(p.errors, []);
  await p.close();
});
