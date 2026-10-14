'use strict';
/*
 * Shared by the page's browser tests: a real page served over scenario runs (Java), and a real Chromium (Playwright). Everything here skips, with the reason, when a part is
 * missing: Playwright, a browser, or the built jars (build them with: mvn -q -DskipTests -pl src/loom/ai-agent4j-loom,src/loom/loom-inspect -am package).
 */
const fs = require('fs');
const path = require('path');
const { spawn, execSync } = require('child_process');

const ROOT = path.join(__dirname, '..', '..', '..', '..', '..', '..');

function loadPlaywright() {
  const tries = [() => require('playwright'), () => require(path.join(execSync('npm root -g').toString().trim(), 'playwright'))];
  for (const t of tries) { try { return t(); } catch (e) { /* next */ } }
  return null;
}

function jar(dir, re) {
  const d = path.join(ROOT, dir, 'target');
  if (!fs.existsSync(d)) return null;
  const hit = fs.readdirSync(d).filter((f) => re.test(f) && !/sources|javadoc|tests/.test(f)).sort()[0];
  return hit ? path.join(d, hit) : null;
}

function classpath() {
  // the core jar (which holds the page) first: another jar may carry an older copy of its files, and the first on the class path wins
  const parts = [jar('src/loom/loom-inspect-core', /^loom-inspect-core-\d.*\.jar$/), jar('src/loom/loom-inspect', /^loom-inspect-\d.*\.jar$/), jar('src/loom/ai-agent4j-loom', /^ai-agent4j-loom-.*-cli\.jar$/)];
  return parts.every(Boolean) ? parts.join(path.delimiter) : null;
}

/** Starts the fixture server over these scenarios; resolves {base, stop}. */
function startFixtures(scenarios, windows, fabric) {
  const cp = classpath();
  if (!cp) return Promise.reject(new Error('the jars are not built'));
  const args = ['-Dorg.slf4j.simpleLogger.defaultLogLevel=error', '-cp', cp, 'io.github.llm4j.loom.inspect.sim.Fixtures', '--scenario', scenarios.join(',')];
  if (windows) args.push('--windows', windows);
  if (fabric) args.push('--fabric');
  const child = spawn('java', args, { stdio: ['ignore', 'pipe', 'pipe'] });
  return new Promise((resolve, reject) => {
    let out = '';
    const timer = setTimeout(() => { child.kill(); reject(new Error('the fixture server did not start in 60 s: ' + out)); }, 60000);
    child.stdout.on('data', (d) => {
      out += d.toString();
      const m = /READY (\d+)/.exec(out);
      if (m) { clearTimeout(timer); resolve({ base: 'http://127.0.0.1:' + m[1], stop: () => child.kill() }); }
    });
    child.stderr.on('data', () => { /* scenario outcomes */ });
    child.on('exit', (code) => { clearTimeout(timer); reject(new Error('the fixture server exited (' + code + '): ' + out)); });
  });
}

/** What to skip with, or null when everything needed is there. */
function missing() {
  const pw = loadPlaywright();
  if (!pw) return 'playwright is not installed';
  if (!classpath()) return 'the jars are not built (mvn -q -DskipTests -pl src/loom/ai-agent4j-loom,src/loom/loom-inspect -am package)';
  try {
    const exe = pw.chromium.executablePath();
    if (!exe || !fs.existsSync(exe)) return 'no chromium for playwright';
  } catch (e) { return 'no chromium for playwright: ' + e.message; }
  return null;
}

async function launch() {
  const pw = loadPlaywright();
  return pw.chromium.launch();
}

/** A page on the fixture server, with the run named by its scenario selected. */
async function openRun(browser, base, scenario, opts) {
  const o = opts || {};
  const context = await browser.newContext({ viewport: { width: o.width || 1360, height: o.height || 900 }, colorScheme: o.scheme || 'dark' });
  const page = await context.newPage();
  const problems = [];
  page.on('console', (m) => { if (m.type() === 'error' && !/status of 404/.test(m.text())) problems.push('console: ' + m.text()); });
  page.on('pageerror', (e) => problems.push('pageerror: ' + e.message));
  page.on('requestfailed', (r) => problems.push('requestfailed: ' + r.url()));
  // the embedded page asks /api/session and /api/processes to find out whether a Fabric server is behind it, and an embedded server answers 404: that is how it knows
  const probe = (url) => /\/api\/(session|processes)$/.test(url);
  page.on('response', (r) => { if (r.status() >= 400 && !/favicon/.test(r.url()) && !probe(r.url())) problems.push('http ' + r.status() + ': ' + r.url()); });
  page.on('console', (m) => { /* a 404 the page asked for on purpose (above) is logged by the browser as a console error */ });
  await page.goto(base);
  await page.waitForSelector('#wtabs button');
  if (scenario) {
    await page.locator('#wtabs button', { hasText: scenario + ' / ' }).first().click();
    await page.waitForSelector('#main:not([hidden])');
    await page.waitForFunction((name) => document.getElementById('wfname') && document.getElementById('client').textContent.indexOf(name) >= 0, scenario);
  }
  return { page, context, problems };
}

async function openTab(page, name) {
  await page.locator('#runtabs button', { hasText: name }).first().click();
}

const PASSWORD = 'correct horse battery staple';

/** A real Fabric server (its own CLI, an empty data directory, an admin password); the core jar goes first so the page under test is the one served. Resolves {base, stop, password}. */
function startFabric() {
  const core = jar('src/loom/loom-inspect-core', /^loom-inspect-core-\d.*\.jar$/);
  const fab = jar('src/loom/loom-fabric', /^loom-fabric-\d.*-all\.jar$/) || jar('src/loom/loom-fabric', /^loom-fabric-\d.*\.jar$/);
  if (!core || !fab) return Promise.reject(new Error('the fabric jar is not built'));
  const cp = [core, fab].join(path.delimiter);
  const dir = fs.mkdtempSync(path.join(require('os').tmpdir(), 'fabric-ui-'));
  const env = Object.assign({}, process.env, { LOOM_FABRIC_ADMIN_PASSWORD: PASSWORD });
  const main = 'io.github.llm4j.loom.fabric.FabricCLI';
  execSync(['java', '-cp', cp, main, 'init', '--data', dir].join(' '), { env, stdio: 'ignore' });
  return new Promise((resolve, reject) => {
    const net = require('net');
    const probe = net.createServer().listen(0, '127.0.0.1', () => {
      const port = probe.address().port;
      probe.close(() => {
        const child = spawn('java', ['-cp', cp, main, 'serve', '--data', dir, '--port', String(port)], { env, stdio: ['ignore', 'pipe', 'pipe'] });
        const timer = setTimeout(() => { child.kill(); reject(new Error('fabric did not start in 60 s')); }, 60000);
        const tryUp = () => {
          require('http').get({ host: '127.0.0.1', port, path: '/api/session' }, (r) => { r.resume(); clearTimeout(timer); resolve({ base: 'http://127.0.0.1:' + port, password: PASSWORD, dir, stop: () => child.kill() }); })
            .on('error', () => setTimeout(tryUp, 300));
        };
        child.stdout.on('data', () => { /* log */ }); child.stderr.on('data', () => { /* log */ });
        tryUp();
      });
    });
  });
}

module.exports = { missing, launch, startFixtures, startFabric, openRun, openTab };
