import { ChildProcess, spawn } from 'child_process';
import { EngineProcess } from './client';

/** Starts `java -jar weave.jar serve --stdio`. Arguments are array elements, never a shell string. */
export function spawnEngineProcess(javaPath: string, jarPath: string): EngineProcess {
    const child: ChildProcess = spawn(javaPath, ['-jar', jarPath, 'serve', '--stdio'], { stdio: ['pipe', 'pipe', 'pipe'] });
    const failed = new Promise<Error>((resolve) => child.once('error', resolve));
    const exitListeners: Array<(code: number | null) => void> = [];
    // a Java that is not there is reported as an exit, so the client's one path handles it
    void failed.then(() => exitListeners.forEach((l) => l(null)));
    child.stdin?.on('error', () => undefined); // a closed pipe is the exit's business
    return {
        write: (bytes) => {
            child.stdin?.write(bytes);
        },
        onData: (listener) => child.stdout?.on('data', listener),
        onStderr: (listener) => child.stderr?.on('data', (chunk: Buffer) => listener(chunk.toString('utf8'))),
        onExit: (listener) => {
            exitListeners.push(listener);
            child.once('exit', (code) => listener(code));
        },
        kill: () => {
            child.kill();
        },
    };
}
