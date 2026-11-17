import test from 'node:test';
import assert from 'node:assert/strict';
import * as fs from 'fs';
import * as os from 'os';
import * as path from 'path';
import { spawnSync } from 'child_process';
import { launcherFolder, rcFileFor, withPathBlock, windowsPathCommand, PATH_BLOCK_BEGIN } from '../src/terminal/weaveLauncher';
import { agentsSection } from '../src/guide/agentSkills';

const posixOnly = { skip: process.platform === 'win32' ? 'runs a shell script' : false };
const root = path.join(__dirname, '..', '..');

function scratch(): string {
    return fs.mkdtempSync(path.join(os.tmpdir(), 'weave-everywhere-'));
}

/** A copy of the packaged launchers beside a bin/weave.jar, laid out as in an installed extension. */
function installed(): { dir: string; launcher: string; jar: string } {
    const dir = path.join(scratch(), 'srijithunni7182.vscode-llm4j-loom-9.9.9');
    fs.mkdirSync(path.join(dir, 'launchers'), { recursive: true });
    fs.mkdirSync(path.join(dir, 'bin'));
    for (const f of ['weave', 'weave.cmd']) fs.copyFileSync(path.join(root, 'launchers', f), path.join(dir, 'launchers', f));
    fs.chmodSync(path.join(dir, 'launchers', 'weave'), 0o755);
    const jar = path.join(dir, 'bin', 'weave.jar');
    fs.writeFileSync(jar, 'not really a jar');
    return { dir, launcher: path.join(dir, 'launchers', 'weave'), jar };
}

function fakeJava(dir: string): string {
    const file = path.join(dir, 'java');
    fs.writeFileSync(file, '#!/bin/sh\nfor a in "$@"; do printf "%s\\n" "$a"; done\n', { mode: 0o755 });
    return file;
}

test('the extension carries a weave launcher that runs the jar beside it, with no activation needed', posixOnly, () => {
    const { dir, launcher, jar } = installed();
    const java = fakeJava(dir);

    const run = spawnSync(launcher, ['check', 'my flow.loom'], { env: { ...process.env, LOOM_JAVA: java }, encoding: 'utf8' });

    assert.equal(run.status, 0, run.stderr);
    assert.deepEqual(run.stdout.split('\n').slice(0, -1), ['-jar', fs.realpathSync(jar), 'check', 'my flow.loom']);
});

test('the packaged launcher finds Java under JAVA_HOME when LOOM_JAVA is not set', posixOnly, () => {
    const { dir, launcher } = installed();
    fs.mkdirSync(path.join(dir, 'jdk', 'bin'), { recursive: true });
    fakeJava(path.join(dir, 'jdk', 'bin'));

    const env: NodeJS.ProcessEnv = { ...process.env, JAVA_HOME: path.join(dir, "jdk") };
    delete env.LOOM_JAVA;
    const run = spawnSync(launcher, ['--version'], { env, encoding: 'utf8' });

    assert.equal(run.status, 0, run.stderr);
    assert.match(run.stdout, /^-jar\n/);
});

test('without Java the packaged launcher says what to install instead of failing obscurely', posixOnly, () => {
    const { launcher } = installed();

    const run = spawnSync(launcher, ['check'], { env: { PATH: '/nonexistent', HOME: os.tmpdir() }, encoding: 'utf8' });

    assert.equal(run.status, 2);
    assert.match(run.stderr, /Java 17/);
});

test('the Windows launcher in the extension runs the jar beside it and finds Java the same way', () => {
    const text = fs.readFileSync(path.join(root, 'launchers', 'weave.cmd'), 'utf8');

    assert.match(text, /%~dp0\.\.\\bin\\weave\.jar/);
    assert.match(text, /LOOM_JAVA/);
    assert.match(text, /JAVA_HOME/);
    assert.match(text, /%\*/);
    assert.ok(text.includes('\r\n'), 'a .cmd file has Windows line ends');
});

test('the launchers are part of the published extension', () => {
    const manifest = JSON.parse(fs.readFileSync(path.join(root, 'package.json'), 'utf8'));

    assert.ok(manifest.files.includes('launchers/**/*'));
});

test('the launcher the extension writes lives at one place a person and a coding agent can name', () => {
    assert.equal(launcherFolder('/home/asha'), path.join('/home/asha', '.llm4j', 'bin'));
});

test('the shell profile is the one the person uses', () => {
    assert.equal(rcFileFor('/bin/zsh', '/home/asha'), path.join('/home/asha', '.zshrc'));
    assert.equal(rcFileFor('/usr/bin/bash', '/home/asha'), path.join('/home/asha', '.bashrc'));
    assert.equal(rcFileFor('/usr/bin/fish', '/home/asha'), path.join('/home/asha', '.profile'));
    assert.equal(rcFileFor(undefined, '/home/asha'), path.join('/home/asha', '.profile'));
});

test('the PATH block is added once, after what the person wrote, and adding it again changes nothing', () => {
    const mine = 'alias ll="ls -l"\n';

    const once = withPathBlock(mine);
    const twice = withPathBlock(once);

    assert.ok(once.startsWith(mine));
    assert.ok(once.includes(PATH_BLOCK_BEGIN));
    assert.ok(once.includes('export PATH="$PATH:$HOME/.llm4j/bin"'));
    assert.equal(twice, once);
    assert.equal(withPathBlock('').split(PATH_BLOCK_BEGIN).length, 2);
});

test('the Windows command appends the folder to the user PATH only when it is missing, and quotes the folder', () => {
    const command = windowsPathCommand("C:\\Users\\o'neil\\.llm4j\\bin");

    assert.ok(command.startsWith("$f = 'C:\\Users\\o''neil\\.llm4j\\bin';"));
    assert.match(command, /-contains \$f/);
    assert.match(command, /SetEnvironmentVariable\('Path', .*'User'\)/);
    assert.equal(command.includes('\n'), false);
});

test('the instructions for coding agents say where weave is when the shell has none, and how keys are set', () => {
    const section = agentsSection();

    assert.ok(section.includes('~/.llm4j/bin/weave'));
    assert.ok(section.includes('%USERPROFILE%\\.llm4j\\bin\\weave.cmd'));
    assert.ok(section.includes('weave keys --model'));
    assert.match(section, /which models/);
    assert.match(section, /never .*weave secrets set/i);
});
