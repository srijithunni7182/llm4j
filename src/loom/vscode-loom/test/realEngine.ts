import { spawn } from 'child_process';
import * as fs from 'fs';
import * as path from 'path';
import { EngineProcess, ProcessEngineClient } from '../src/engine/client';
import { TimeoutScheduler } from '../src/graph/timeoutScheduler';

// The real engine, from the Java module's classes (no VS Code needed). Tests skip when the module has not been built.
const loomModule = path.join(__dirname, '..', '..', '..', 'ai-agent4j-loom');
const classes = path.join(loomModule, 'target', 'classes');
const lib = path.join(loomModule, 'target', 'lib');

export const available = fs.existsSync(path.join(classes, 'io', 'github', 'llm4j', 'loom', 'symbols', 'SymbolService.class')) && fs.existsSync(lib);
export const classpath = [classes, path.join(lib, '*')].join(path.delimiter);

function startReal(): EngineProcess {
    const child = spawn('java', ['-cp', classpath, 'io.github.llm4j.loom.cli.WeaveCLI', 'serve', '--stdio'], { stdio: ['pipe', 'pipe', 'pipe'] });
    return {
        write: (b) => { child.stdin?.write(b); },
        onData: (l) => child.stdout?.on('data', l),
        onStderr: (l) => child.stderr?.on('data', (c: Buffer) => l(c.toString('utf8'))),
        onExit: (l) => child.once('exit', l),
        kill: () => { child.kill(); },
    };
}

/** A client on a real `weave serve` that may read the given folders. */
export function realClient(roots: string[]): ProcessEngineClient {
    const client = new ProcessEngineClient({ start: startReal, roots: () => roots, scheduler: new TimeoutScheduler(), requestTimeoutMs: 60_000 });
    client.start();
    return client;
}
