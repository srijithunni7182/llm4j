'use strict';
/* R15, R16: the Configuration page, in a real browser, against a real Fabric server (its CLI, an empty data directory). */
const test = require('node:test');
const assert = require('node:assert/strict');
const { missing, launch, startFabric } = require('./support');

const skip = missing();
let fabric; let browser;

test.before(async () => {
  if (skip) return;
  fabric = await startFabric();
  browser = await launch();
});
test.after(async () => {
  if (browser) await browser.close();
  if (fabric) fabric.stop();
});

async function text(page, sel) { return (await page.locator(sel).first().innerText()).replace(/\s+/g, ' ').trim(); }

async function configPage() {
  const context = await browser.newContext({ viewport: { width: 1360, height: 1000 } });
  const page = await context.newPage();
  const problems = [];
  page.on('pageerror', (e) => problems.push('pageerror: ' + e.message));
  await page.goto(fabric.base);
  await page.waitForSelector('#loginform input#pw');   // Fabric asks for the admin password before it shows anything
  await page.fill('#pw', fabric.password);
  await page.locator('#loginform button[type=submit]').click();
  await page.locator('#wtabs button.proc', { hasText: 'Configuration' }).waitFor();
  await page.locator('#wtabs button.proc', { hasText: 'Configuration' }).click();
  await page.waitForSelector('#cfgbody:not([hidden])');
  await page.waitForFunction(() => document.querySelectorAll('#rb-rule option').length > 0);   // the page has loaded the configuration
  return { page, context, problems };
}

async function pick(page, behaviour) {
  await page.selectOption('#rb-agent', '*');
  await page.selectOption('#rb-rule', { label: behaviour });
}

test('R15: the rule editor offers all fourteen behaviours, each with its default beside it', { skip: skip || false }, async () => {
  const { page, context } = await configPage();
  const labels = await page.locator('#rb-rule option').allTextContents();
  for (const l of ['Context nearly full', 'Truncated answers', 'Compaction thrash', 'Tool success rate', 'Latency spike', 'Access refused', 'Injection scan', 'Accepted trifecta']) {
    assert.ok(labels.includes(l), l + ' is offered');
  }
  assert.equal(labels.length, 14);
  const expect = { 'Context nearly full': /85.*95/, 'Truncated answers': /1/, 'Compaction thrash': /4.*10/, 'Tool success rate': /70.*10/, 'Latency spike': /5/, 'Access refused': /3.*60/, 'Injection scan': /on/i, 'Accepted trifecta': /not accepted|reason/i };
  for (const [name, re] of Object.entries(expect)) {
    await pick(page, name);
    assert.match(await text(page, '#rb-hint'), re, name + ' shows its default');
  }
  await context.close();
});

test('R15: a context rule is added, shown against its default, saved, and still there after a reload', { skip: skip || false }, async () => {
  const { page, context, problems } = await configPage();
  await pick(page, 'Context nearly full');
  await page.fill('#rf-warn', '80');
  await page.fill('#rf-high', '92');
  await page.click('#rb-add');
  const row = page.locator('#rules-table tr', { hasText: 'Context nearly full' });
  assert.match(await row.innerText(), /80%.*92%/);
  assert.match(await row.innerText(), /85%.*95%/);
  await pick(page, 'Tool success rate');
  await page.fill('#rf-below', '60');
  await page.fill('#rf-over', '8');
  await page.click('#rb-add');
  await page.click('#rules-save');
  await page.waitForFunction(() => /saved/.test(document.getElementById('rules-msg').textContent));
  await page.reload();
  await page.locator('#wtabs button.proc', { hasText: 'Configuration' }).click();
  await page.waitForSelector('#rules-table tr');
  const t = await text(page, '#rules-table');
  assert.match(t, /Context nearly full.*80%.*92%/);
  assert.match(t, /Tool success rate.*60%.*8/);
  const file = await page.evaluate(() => fetch('/api/config').then((r) => r.json()));
  assert.match(file.sections.rules.text, /context_nearly_full/);
  assert.deepEqual(problems, []);
  await context.close();
});

test('R15: the server\'s refusal is shown as the server wrote it, and nothing is lost from the form', { skip: skip || false }, async () => {
  const { page, context } = await configPage();
  await pick(page, 'Context nearly full');
  await page.fill('#rf-warn', '95');
  await page.fill('#rf-high', '90');
  await page.click('#rb-add');
  const before = await page.locator('#rules-table tr').count();
  await page.click('#rules-save');
  await page.waitForSelector('#rules-problems:not([hidden])');
  assert.match(await text(page, '#rules-problems'), /must be lower than high/);
  assert.equal(await page.locator('#rules-table tr').count(), before, 'the edited rules are still on the page');
  assert.match(await text(page, '#rules-msg'), /not saved/);
  await page.click('#rules-reset');
  await context.close();
});

test('R15: an accepted trifecta needs a reason, and accepting for every agent shows the warning', { skip: skip || false }, async () => {
  const { page, context } = await configPage();
  await pick(page, 'Accepted trifecta');
  await page.fill('#rf-reason', '   ');
  await page.click('#rb-add');
  assert.match(await text(page, '#rules-problems'), /reason/i);
  assert.equal(await page.locator('#rules-table tr', { hasText: 'Accepted trifecta' }).count(), 0);
  await page.fill('#rf-reason', 'support desk: reads pages, looks up the account, replies by mail; reviewed');
  await page.click('#rb-add');
  assert.equal(await page.locator('#rules-table tr', { hasText: 'Accepted trifecta' }).count(), 1);
  await page.click('#rules-save');
  await page.waitForFunction(() => /saved/.test(document.getElementById('rules-msg').textContent));
  assert.match(await text(page, '#rules-problems'), /every agent|\*|all agents/i, 'the check\'s warning is shown');
  await page.click('#rules-reset');
  await context.close();
});

test('R15: access, injection scan, thrash, truncation and latency rules each take their own inputs', { skip: skip || false }, async () => {
  const { page, context } = await configPage();
  await pick(page, 'Access refused'); await page.fill('#rf-count', '5'); await page.fill('#rf-seconds', '120'); await page.click('#rb-add');
  await pick(page, 'Injection scan'); await page.selectOption('#rf-onoff', 'off'); await page.click('#rb-add');
  await pick(page, 'Compaction thrash'); await page.fill('#rf-count', '3'); await page.fill('#rf-of', '8'); await page.click('#rb-add');
  await pick(page, 'Truncated answers'); await page.fill('#rf-n', '2'); await page.click('#rb-add');
  await pick(page, 'Latency spike'); await page.fill('#rf-factor', '8'); await page.click('#rb-add');
  const t = await text(page, '#rules-table');
  assert.match(t, /Access refused.*5.*120/);
  assert.match(t, /Injection scan.*off/);
  assert.match(t, /Compaction thrash.*3.*8/);
  assert.match(t, /Truncated answers.*2/);
  assert.match(t, /Latency spike.*8/);
  await page.click('#rules-save');
  await page.waitForFunction(() => /saved/.test(document.getElementById('rules-msg').textContent));
  const rules = await page.evaluate(() => fetch('/api/config').then((r) => r.json()).then((c) => c.sections.rules.structured['*']));
  assert.equal(rules.access_denied.count, 5);
  assert.equal(rules.injection_scan, false);
  assert.equal(rules.compaction_thrash.of_calls, 8);
  assert.equal(rules.truncated_answers, 2);
  assert.equal(rules.latency_spike, 8);
  await context.close();
});

test('R16: the context-window catalog is edited, saved and removed like the prices, and a bad value is refused', { skip: skip || false }, async () => {
  const { page, context } = await configPage();
  await page.fill('#wb-new', 'tiny-model');
  await page.click('#wb-add');
  await page.fill('#wb-window', '0');
  await page.click('#wb-set');
  await page.click('#windows-save');
  await page.waitForSelector('#windows-problems:not([hidden])');
  assert.match(await text(page, '#windows-problems'), /tiny-model|positive|window/i);
  await page.click('#windows-reset');
  await page.fill('#wb-new', 'tiny-model');
  await page.click('#wb-add');
  await page.fill('#wb-window', '32000');
  await page.click('#wb-set');
  assert.match(await text(page, '#windows-table'), /tiny-model.*32,?000/);
  await page.click('#windows-save');
  await page.waitForFunction(() => /saved/.test(document.getElementById('windows-msg').textContent));
  const saved = await page.evaluate(() => fetch('/api/config').then((r) => r.json()).then((c) => c.sections.windows));
  assert.deepEqual(saved.structured, [{ model: 'tiny-model', window: 32000 }]);
  await page.locator('#windows-table tr', { hasText: 'tiny-model' }).locator('button', { hasText: 'Edit' }).click();
  await page.click('#wb-remove');
  await page.click('#windows-save');
  await page.waitForFunction(() => /0 model|saved/.test(document.getElementById('windows-msg').textContent));
  assert.equal((await page.evaluate(() => fetch('/api/config').then((r) => r.json()).then((c) => c.sections.windows.structured))).length, 0);
  await context.close();
});
