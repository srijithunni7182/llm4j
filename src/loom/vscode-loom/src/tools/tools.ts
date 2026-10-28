/** The rest of the journey in the editor: test, evaluate, run with the live page, write deployment files, what next, the command reference. Each is one `weave` command typed into an integrated terminal. */

export type EvalMode = 'init' | 'check' | 'mock';
export type DeployTarget = 'docker' | 'compose' | 'k8s';

/** What to type into the terminal, and a sentence to confirm first when the command is a real run. */
export interface Plan {
    name: string;
    args: string[];
    confirm?: string;
}

export const EVAL_MODES: { mode: EvalMode; label: string; detail: string }[] = [
    { mode: 'mock', label: 'Run it with mock models', detail: 'Free: runs the wiring, content checks stay unjudged' },
    { mode: 'check', label: 'Check the dataset', detail: 'Free: validates eval/golden, calls no model' },
    { mode: 'init', label: 'Create a starter dataset', detail: 'Free: a starter case for each agent that has none; never overwrites' },
];

export const DEPLOY_TARGETS: { target: DeployTarget; label: string; detail: string }[] = [
    { target: 'docker', label: 'Docker', detail: 'A Dockerfile, an entrypoint and an .env.example, read from the script' },
    { target: 'compose', label: 'Docker Compose', detail: 'A compose file; optionally with a Fabric server beside it' },
    { target: 'k8s', label: 'Kubernetes', detail: 'Manifests, with a network policy; no secret is written' },
];

export function testPlan(script: string): Plan {
    return { name: 'Loom tests', args: ['test', script, '--coverage'] };
}

export function evalPlan(script: string, mode: EvalMode): Plan {
    const flag = mode === 'mock' ? '--mock' : mode === 'check' ? '--check' : '--init';
    return { name: 'Loom evaluation', args: ['eval', script, flag] };
}

/** A real run with the live page: it uses the person's model keys, so it asks first and is capped by tokens. */
export function observePlan(script: string, maxTokens: number): Plan {
    return {
        name: 'Loom live run',
        args: ['run', script, '--observe', '--max-tokens', String(Math.max(1000, Math.floor(maxTokens)))],
        confirm: `This is a real run of ${script.split(/[\\/]/).pop()}: it uses the model keys you have set and spends tokens (capped at ${maxTokens}). It opens a live page you can watch, pause and stop. Run it?`,
    };
}

export function deployPlan(script: string, target: DeployTarget): Plan {
    return { name: 'Loom deploy files', args: ['deploy', script, '--target', target] };
}

export function nextPlan(folder: string): Plan {
    return { name: 'Loom next', args: ['next', folder] };
}

export function manPlan(): Plan {
    return { name: 'Loom commands', args: ['man'] };
}

/** Which script a command is for: the one open in the editor, else the only one in the folder; with several, the person picks. */
export function chooseScript(active: string | undefined, inWorkspace: string[]): { script?: string; pick?: string[]; none?: true } {
    if (active) return { script: active };
    if (inWorkspace.length === 1) return { script: inWorkspace[0] };
    if (inWorkspace.length > 1) return { pick: [...inWorkspace].sort() };
    return { none: true };
}
