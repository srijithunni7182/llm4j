'use strict';
/* R36, R37, R40 (A10): "Create test" and "Try a change" on the run page and on an incident, in a real browser. The server's answers are scripted here (the Java tests cover the routes); what is tested is what the page offers and shows. */
const test = require('node:test');
const assert = require('node:assert/strict');
const { missing, launch, startFixtures, openRun } = require('./support');

const skip = missing();
let fixtures; let browser;

test.before(async () => {
  if (skip) return;
  fixtures = await startFixtures(['flaky-tool']);
  browser = await launch();
});
test.after(async () => {
  if (browser) await browser.close();
  if (fixtures) fixtures.stop();
});

const CASE = '# Taken from a real run\ncaptured:\n  status: observed\n';

/** Makes the page believe it is signed in to a server with the capture permission on, and records what it posts. */
async function scripted(page, opts) {
  const o = Object.assign({ enabled: true, signedIn: true, capture: { ok: true, message: 'Captured.', name: 'a.test.yaml', text: CASE }, tests: 2 }, opts || {});
  const posted = [];
  await page.route('**/api/session', (r) => r.fulfill({ json: { authRequired: false, passwordSet: true, signedIn: o.signedIn, csrf: 'tok', controlsEnabled: false } }));
  await page.route(/\/api\/runs\/[0-9a-f]{32}$/, async (r) => {
    const res = await r.fetch();
    const body = await res.json();
    body.loop = o.enabled ? { enabled: true } : { enabled: false, reason: 'start Fabric with --allow-capture' };
    await r.fulfill({ response: res, json: body });
  });
  await page.route('**/api/loop?*', (r) => r.fulfill({ json: { enabled: true, tests: o.tests } }));
  await page.route(/\/api\/runs\/[0-9a-f]{32}\/(capture|whatif)$/, (r) => {
    posted.push({ url: r.request().url().split('/').pop(), body: JSON.parse(r.request().postData() || '{}'), csrf: r.request().headers()['x-csrf-token'] });
    const isCapture = /capture$/.test(r.request().url());
    r.fulfill({ json: isCapture ? o.capture : { ok: true, message: 'Replayed.', text: 'candidate result: Sorry, it is late.' } });
  });
  return posted;
}

async function open(opts) {
  const ctx = await browser.newContext({ viewport: { width: 1360, height: 900 }, acceptDownloads: true });
  const page = await ctx.newPage();
  const problems = [];
  page.on('pageerror', (e) => problems.push('pageerror: ' + e.message));
  const posted = await scripted(page, opts);
  await page.goto(fixtures.base);
  await page.waitForSelector('#main:not([hidden])', { timeout: 15000 });
  return { page, ctx, posted, problems };
}

test('R36: a signed-in administrator with the permission sees both actions on the run page', { skip: skip || false }, async () => {
  const { page, ctx, problems } = await open();
  await page.locator('#loopbar button', { hasText: 'Create test' }).waitFor();
  assert.ok(await page.locator('#loopbar button', { hasText: 'Try a change' }).count());
  assert.deepEqual(problems, []);
  await ctx.close();
});

test('R39: without the permission, or without a session, the actions are not offered', { skip: skip || false }, async () => {
  for (const opts of [{ enabled: false }, { signedIn: false }]) {
    const { page, ctx } = await open(opts);
    await page.waitForTimeout(500);
    assert.equal(await page.locator('#loopbar button').count(), 0);
    assert.equal(await page.locator('.incident button', { hasText: 'Create test' }).count(), 0);
    await ctx.close();
  }
});

test('R36: Create test posts with the page token, shows the masked case, and downloads it as a file', { skip: skip || false }, async () => {
  const { page, ctx, posted } = await open();
  await page.locator('#loopbar button', { hasText: 'Create test' }).click();
  await page.locator('#lp-out:not([hidden])').waitFor();
  assert.equal(posted.length, 1);
  assert.equal(posted[0].url, 'capture');
  assert.equal(posted[0].csrf, 'tok');
  assert.equal((await page.locator('#lp-out').innerText()).trim(), CASE.trim());
  assert.match(await page.locator('#lp-text').innerText(), /never writes into your repository/);
  assert.match(await page.locator('#lp-text').innerText(), /name or a word no pattern can find is not/);
  const [dl] = await Promise.all([page.waitForEvent('download'), page.locator('#lp-dl').click()]);
  assert.equal(dl.suggestedFilename(), 'a.test.yaml');
  await ctx.close();
});

test('R37: when the journal is elsewhere the page shows the command to run, not an error', { skip: skip || false }, async () => {
  const cmd = "weave capture r-1 --store <where this run's store is>";
  const { page, ctx } = await open({ capture: { ok: false, message: 'This server does not hold the run\'s journal. Run this where the store lives:', fallback: true, command: cmd } });
  await page.locator('#loopbar button', { hasText: 'Create test' }).click();
  await page.locator('#lp-cmd:not([hidden])').waitFor();
  assert.equal(await page.locator('#lp-cmd').innerText(), cmd);
  assert.match(await page.locator('#lp-text').innerText(), /does not hold the run/);
  assert.equal(await page.locator('#lp-err:not([hidden])').count(), 0);
  assert.equal(await page.locator('#lp-dl:not([hidden])').count(), 0);
  await ctx.close();
});

test('R40: an incident row offers Create test with its kind and shows how many tests came from that kind', { skip: skip || false }, async () => {
  const { page, ctx, posted } = await open({ tests: 2 });
  const row = page.locator('#inclist .incident').first();
  await row.waitFor();
  assert.match(await row.innerText(), /2 tests created from this kind of incident/);
  await row.locator('button', { hasText: 'Create test' }).click();
  await page.locator('#lp-out:not([hidden])').waitFor();
  assert.match(posted[0].body.kind, /^[a-z][a-z0-9-]*$/);
  await ctx.close();
});

test('R36: Try a change sends where and what, and shows the replay', { skip: skip || false }, async () => {
  const { page, ctx, posted } = await open();
  await page.locator('#loopbar button', { hasText: 'Try a change' }).click();
  await page.fill('#lp-at', 'Main/s1/a0');
  await page.fill('#lp-answer', 'Replier=Sorry, it is late.');
  await page.click('#lp-run');
  await page.locator('#lp-out:not([hidden])').waitFor();
  assert.equal(posted[0].url, 'whatif');
  assert.deepEqual(posted[0].body, { at: 'Main/s1/a0', sets: {}, answers: { Replier: 'Sorry, it is late.' } });
  assert.match(await page.locator('#lp-out').innerText(), /candidate result/);
  await ctx.close();
});

test('hostile text in a case or a report is shown as text, never run', { skip: skip || false }, async () => {
  const evil = '<img src=x onerror="window.__pwn=1"><script>window.__pwn=1</script>';
  const { page, ctx } = await open({ capture: { ok: true, message: 'Captured.', name: 'x.test.yaml', text: evil } });
  await page.locator('#loopbar button', { hasText: 'Create test' }).click();
  await page.locator('#lp-out:not([hidden])').waitFor();
  assert.equal(await page.locator('#lp-out').innerText(), evil);
  assert.equal(await page.evaluate(() => window.__pwn), undefined);
  await ctx.close();
});
