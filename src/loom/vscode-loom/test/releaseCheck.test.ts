import test from 'node:test';
import assert from 'node:assert/strict';
import * as fs from 'fs';
import * as os from 'os';
import * as path from 'path';
import { spawnSync } from 'child_process';

const posixOnly = { skip: process.platform === 'win32' ? 'runs a bash script' : false };
const scripts = path.join(__dirname, '..', '..', '..', '..', '..', 'scripts');
const releaseCheck = path.join(scripts, 'check-extension-release.sh');
const publishedVersion = path.join(scripts, 'published-extension-version.sh');

/** An extension folder holding only what the check reads: a package.json with a version, and a changelog. */
function extension(version: string, changelog: string): string {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'release-check-'));
    fs.writeFileSync(path.join(dir, 'package.json'), JSON.stringify({ name: 'x', version }));
    fs.writeFileSync(path.join(dir, 'CHANGELOG.md'), changelog);
    return dir;
}

interface Outcome {
    status: number | null;
    output: string;
    publish: string | undefined;
}

/** Runs the check the way the workflow does: `publish=...` goes to the file named by GITHUB_OUTPUT. */
function check(args: string[], dir: string): Outcome {
    const out = path.join(fs.mkdtempSync(path.join(os.tmpdir(), 'gh-output-')), 'output');
    fs.writeFileSync(out, '');
    const run = spawnSync('bash', [releaseCheck, ...args, dir], { encoding: 'utf8', env: { ...process.env, GITHUB_OUTPUT: out } });
    const line = fs.readFileSync(out, 'utf8').split('\n').find((l) => l.startsWith('publish='));
    return { status: run.status, output: run.stdout + run.stderr, publish: line?.slice('publish='.length) };
}

const CHANGELOG = (v: string): string => `# Changelog\n\n## ${v}\n\n- a change\n\n## 1.0.2\n`;

test('a version newer than the baseline, with a changelog section, is published', posixOnly, () => {
    const run = check(['1.1.0'], extension('1.2.0', CHANGELOG('1.2.0')));

    assert.equal(run.status, 0, run.output);
    assert.equal(run.publish, 'true');
});

test('the very first release, with nothing published yet, is published', posixOnly, () => {
    const run = check(['none'], extension('1.0.0', CHANGELOG('1.0.0')));

    assert.equal(run.status, 0, run.output);
    assert.equal(run.publish, 'true');
});

test('versions are compared as numbers, so 1.10.0 is newer than 1.9.0', posixOnly, () => {
    const run = check(['1.9.0'], extension('1.10.0', CHANGELOG('1.10.0')));

    assert.equal(run.status, 0, run.output);
    assert.equal(run.publish, 'true');
});

test('the version that is already published is skipped, not an error, so a change that does not release leaves the build green', posixOnly, () => {
    const run = check(['1.2.0'], extension('1.2.0', CHANGELOG('1.2.0')));

    assert.equal(run.status, 0, run.output);
    assert.equal(run.publish, 'false');
    assert.match(run.output, /already/i);
});

test('on a pull request the same version is refused, because the change would never be released', posixOnly, () => {
    const run = check(['--require-bump', '1.2.0'], extension('1.2.0', CHANGELOG('1.2.0')));

    assert.notEqual(run.status, 0);
    assert.match(run.output, /1\.2\.0/);
    assert.match(run.output, /bump/i);
});

test('an older version than the baseline is refused, naming both', posixOnly, () => {
    const run = check(['1.2.0'], extension('1.1.0', CHANGELOG('1.1.0')));

    assert.notEqual(run.status, 0);
    assert.match(run.output, /1\.1\.0/);
    assert.match(run.output, /1\.2\.0/);
    assert.notEqual(run.publish, 'true');
});

test('1.9.0 is older than 1.10.0 even though it sorts after it as text', posixOnly, () => {
    const run = check(['1.10.0'], extension('1.9.0', CHANGELOG('1.9.0')));

    assert.notEqual(run.status, 0);
});

test('a version the Marketplace does not take (a pre-release) is refused', posixOnly, () => {
    const run = check(['1.1.0'], extension('1.2.0-beta.1', CHANGELOG('1.2.0-beta.1')));

    assert.notEqual(run.status, 0);
    assert.match(run.output, /1\.2\.0-beta\.1/);
    assert.notEqual(run.publish, 'true');
});

test('a new version with no changelog section is refused, so a release always says what changed', posixOnly, () => {
    const run = check(['1.1.0'], extension('1.2.0', '# Changelog\n\n## 1.1.0\n'));

    assert.notEqual(run.status, 0);
    assert.match(run.output, /CHANGELOG/);
    assert.notEqual(run.publish, 'true');
});

test('a changelog line that only starts like the version does not count (1.2.0 is not 1.2.01)', posixOnly, () => {
    const run = check(['1.1.0'], extension('1.2.0', '## 1.2.01\n'));

    assert.notEqual(run.status, 0);
});

for (const baseline of ['', 'latest', '1.2', '1.2.0-rc1', '1.2.0;echo pwned', '$(id)']) {
    test(`the baseline ${JSON.stringify(baseline)} is not a version, and nothing is published on a guess`, posixOnly, () => {
        const run = check([baseline], extension('1.2.0', CHANGELOG('1.2.0')));

        assert.notEqual(run.status, 0);
        assert.notEqual(run.publish, 'true');
    });
}

/** The reply of the Marketplace's extension query, with the newest version first as it sends them. */
const reply = (versions: string[]): string => JSON.stringify({ results: [{ extensions: versions.length ? [{ versions: versions.map((version) => ({ version })) }] : [] }] });

function published(json: string): { status: number | null; output: string } {
    const file = path.join(fs.mkdtempSync(path.join(os.tmpdir(), 'reply-')), 'reply.json');
    fs.writeFileSync(file, json);
    const run = spawnSync('bash', [publishedVersion, '--from-file', file], { encoding: 'utf8' });
    return { status: run.status, output: run.stdout.trim() + run.stderr };
}

test('the published version is the newest the Marketplace lists', posixOnly, () => {
    const run = published(reply(['1.1.0', '1.0.2', '1.0.1']));

    assert.equal(run.status, 0, run.output);
    assert.equal(run.output, '1.1.0');
});

test('an extension the Marketplace does not know yet is reported as none', posixOnly, () => {
    const run = published(reply([]));

    assert.equal(run.status, 0, run.output);
    assert.equal(run.output, 'none');
});

for (const [name, body] of [
    ['an empty reply', ''],
    ['a reply that is not JSON', '<html>503</html>'],
    ['a reply of another shape', '{"message":"rate limited"}'],
] as const) {
    test(`${name} is an error, never read as "nothing published", so a failed lookup cannot cause a duplicate upload`, posixOnly, () => {
        const run = published(body);

        assert.notEqual(run.status, 0);
        assert.notEqual(run.output, 'none');
    });
}
