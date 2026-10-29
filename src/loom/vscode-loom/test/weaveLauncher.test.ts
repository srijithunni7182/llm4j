import test from 'node:test';
import assert from 'node:assert/strict';
import * as fs from 'fs';
import * as os from 'os';
import * as path from 'path';
import { spawnSync } from 'child_process';
import { ensureLauncher, launcherFor, pathCollectionFor } from '../src/terminal/weaveLauncher';

const posixOnly = { skip: process.platform === 'win32' ? 'runs a shell script' : false };

function scratch(): string {
    return fs.mkdtempSync(path.join(os.tmpdir(), 'weave-launcher-'));
}

/** A stand-in for `java` that prints each argument on a line of its own and exits with the code in `FAKE_JAVA_EXIT`. */
function fakeJava(dir: string): string {
    const file = path.join(dir, 'fake java');
    fs.writeFileSync(file, '#!/bin/sh\nfor a in "$@"; do printf "%s\\n" "$a"; done\nexit "${FAKE_JAVA_EXIT:-0}"\n', { mode: 0o755 });
    return file;
}

test('the launcher runs the bundled jar with Java and passes every argument through untouched', posixOnly, () => {
    const dir = scratch();
    const jar = path.join(dir, 'a folder', "weave's.jar");
    const launcher = ensureLauncher(path.join(dir, 'bin'), 'linux', fakeJava(dir), jar);

    const args = ['check', 'my script.loom', '$HOME', '`id`', ';touch pwned', "it's", '', '--format=json'];
    const run = spawnSync(path.join(launcher), args, { encoding: 'utf8' });

    assert.equal(run.status, 0, run.stderr);
    assert.deepEqual(run.stdout.split('\n').slice(0, -1), ['-jar', jar, ...args]);
    assert.equal(fs.existsSync(path.join(dir, 'pwned')), false);
});

test('the launcher ends with the exit code of weave, so a script can act on it', posixOnly, () => {
    const dir = scratch();
    const launcher = ensureLauncher(path.join(dir, 'bin'), 'linux', fakeJava(dir), '/x/weave.jar');

    const run = spawnSync(launcher, ['run'], { env: { ...process.env, FAKE_JAVA_EXIT: '3' }, encoding: 'utf8' });

    assert.equal(run.status, 3);
});

test('the launcher is executable by its owner only for writing', posixOnly, () => {
    const dir = scratch();
    const launcher = ensureLauncher(path.join(dir, 'bin'), 'linux', 'java', '/x/weave.jar');

    assert.equal(fs.statSync(launcher).mode & 0o777, 0o755);
});

test('a second call with the same Java and jar leaves the file alone; a new Java rewrites it', posixOnly, () => {
    const dir = scratch();
    const bin = path.join(dir, 'bin');
    const launcher = ensureLauncher(bin, 'linux', 'java', '/x/weave.jar');
    const stamp = new Date(2020, 0, 1);
    fs.utimesSync(launcher, stamp, stamp);

    ensureLauncher(bin, 'linux', 'java', '/x/weave.jar');
    assert.equal(fs.statSync(launcher).mtime.getTime(), stamp.getTime());

    ensureLauncher(bin, 'linux', '/opt/jdk-21/bin/java', '/x/weave.jar');
    assert.match(fs.readFileSync(launcher, 'utf8'), /'\/opt\/jdk-21\/bin\/java'/);
});

test('a jar that moves with a new extension version is followed by the next call', posixOnly, () => {
    const dir = scratch();
    const bin = path.join(dir, 'bin');
    ensureLauncher(bin, 'linux', 'java', '/ext-1.0.2/bin/weave.jar');

    const launcher = ensureLauncher(bin, 'linux', 'java', '/ext-1.1.0/bin/weave.jar');

    assert.match(fs.readFileSync(launcher, 'utf8'), /ext-1\.1\.0/);
    assert.doesNotMatch(fs.readFileSync(launcher, 'utf8'), /ext-1\.0\.2/);
});

test('on Windows the launcher is a .cmd file that passes the arguments on and quotes the paths', () => {
    const l = launcherFor('win32', 'C:\\Program Files\\Java\\bin\\java.exe', 'C:\\Users\\me\\ext\\bin\\weave.jar');

    assert.equal(l.fileName, 'weave.cmd');
    assert.match(l.content, /"C:\\Program Files\\Java\\bin\\java\.exe" -jar "C:\\Users\\me\\ext\\bin\\weave\.jar" %\*/);
    assert.ok(l.content.includes('\r\n'), 'a batch file wants CRLF line ends');
});

test('on Windows a percent sign in a path is doubled so the shell does not expand it', () => {
    const l = launcherFor('win32', 'java', 'C:\\100%\\weave.jar');

    assert.ok(l.content.includes('"C:\\100%%\\weave.jar"'));
});

test('on macOS and Linux the launcher is a /bin/sh script that execs Java', () => {
    const l = launcherFor('darwin', 'java', '/Users/me/.vscode/ext/bin/weave.jar');

    assert.equal(l.fileName, 'weave');
    assert.ok(l.content.startsWith('#!/bin/sh\n'));
    assert.ok(l.content.includes(`exec 'java' -jar '/Users/me/.vscode/ext/bin/weave.jar' "$@"`));
});

test('a path with a single quote is escaped so it cannot end the quoting', () => {
    const l = launcherFor('linux', 'java', "/tmp/o'brien/weave.jar");

    assert.ok(l.content.includes(`'/tmp/o'\\''brien/weave.jar'`));
});

for (const [name, platform, java, jar] of [
    ['a newline in the jar path', 'linux', 'java', '/x/a\nb/weave.jar'],
    ['a newline in the java path', 'linux', 'ja\nva', '/x/weave.jar'],
    ['a NUL in the jar path', 'linux', 'java', '/x/a\0b/weave.jar'],
    ['a double quote in a Windows jar path', 'win32', 'java', 'C:\\a"b\\weave.jar'],
    ['a carriage return in a Windows java path', 'win32', 'ja\rva', 'C:\\weave.jar'],
] as const) {
    test(`${name} is refused, so a setting cannot add a line to the script`, () => {
        assert.throws(() => launcherFor(platform, java, jar), /cannot be used|control character|quote/i);
    });
}

test('with the setting on, the terminal PATH gets the launcher folder after the existing entries, so a weave of your own wins', () => {
    const calls: string[] = [];
    const collection = { description: undefined as string | undefined, clear: () => calls.push('clear'), append: (v: string, x: string) => calls.push(`append ${v} ${x}`) };

    pathCollectionFor(collection, true, ':', () => '/store/bin', (m) => calls.push(`warn ${m}`));

    assert.deepEqual(calls, ['clear', 'append PATH :/store/bin']);
    assert.match(collection.description ?? '', /weave/);
});

test('with the setting off, nothing is written and the terminal PATH is left alone', () => {
    const calls: string[] = [];
    let wrote = false;
    const collection = { description: undefined as string | undefined, clear: () => calls.push('clear'), append: () => calls.push('append') };

    pathCollectionFor(collection, false, ':', () => ((wrote = true), '/store/bin'), (m) => calls.push(`warn ${m}`));

    assert.deepEqual(calls, ['clear']);
    assert.equal(wrote, false);
});

test('when the launcher cannot be written the PATH is left alone and the person is told once, with the reason', () => {
    const calls: string[] = [];
    const collection = { description: undefined as string | undefined, clear: () => calls.push('clear'), append: () => calls.push('append') };

    pathCollectionFor(collection, true, ':', () => {
        throw new Error('EACCES: permission denied');
    }, (m) => calls.push(`warn ${m}`));

    assert.deepEqual(calls.slice(0, 1), ['clear']);
    assert.equal(calls.includes('append'), false);
    assert.ok(calls.some((c) => c.startsWith('warn ') && c.includes('EACCES')));
});
