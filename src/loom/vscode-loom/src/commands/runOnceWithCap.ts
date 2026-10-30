/**
 * "Loom: Run Once With a Cap…": the first real run, with the cap as the first question. The command
 * is typed into a terminal and the person presses Enter, so a run never starts without them seeing
 * exactly what it will do. Everything the person types is validated, because it ends up in a shell.
 */

export interface RunOnceHost {
    /** The Loom file to run, or undefined when there is none. */
    scriptFile(): string | undefined;
    /** The worst-case cost of one run, as text, when the review knows it. */
    estimate(script: string): Promise<string | undefined>;
    askCap(prompt: string): Promise<string | undefined>;
    /** One `name=value` input, or an empty string / undefined when there are no more. */
    askInput(prompt: string): Promise<string | undefined>;
    /** Types the command into a terminal without pressing Enter. */
    type(command: string): void;
    say(message: string): void;
}

export interface Cap {
    flag: '--max-cost' | '--max-tokens';
    value: string;
}

/** A cap needs a unit: `$2` for money, `50000 tokens` for tokens. Anything else is not a cap. */
export function parseCap(text: string): Cap | undefined {
    const t = text.trim();
    const money = /^\$\s*(\d+(?:\.\d{1,4})?)$/.exec(t);
    if (money && Number(money[1]) > 0) {
        return { flag: '--max-cost', value: money[1] };
    }
    const tokens = /^(\d[\d,_]*)\s*(?:tokens?|tok)$/i.exec(t);
    if (tokens) {
        const n = Number(tokens[1].replace(/[,_]/g, ''));
        if (n > 0) {
            return { flag: '--max-tokens', value: String(n) };
        }
    }
    return undefined;
}

const SHELL_SPECIAL = /["`$\\\r\n]/;
const INPUT = /^([A-Za-z_]\w*)=(.*)$/;

function quote(value: string): string {
    return `"${value}"`;
}

export async function runOnceWithCap(host: RunOnceHost): Promise<void> {
    const script = host.scriptFile();
    if (!script) {
        host.say('Open a .loom file first; the run is of that workflow.');
        return;
    }
    if (SHELL_SPECIAL.test(script)) {
        host.say('This file\'s path has a quote, dollar sign or backslash in it, which cannot be typed safely into a terminal. Rename it or run `weave run` yourself.');
        return;
    }
    const worst = await host.estimate(script);
    const estimate = worst ? `worst case ${worst} for one run` : 'no estimate yet (run Loom: Review Workflow to get one)';
    const prompt = `Spend cap for this run, with a unit: $2 or 50000 tokens. (${estimate})`;
    let cap: Cap | undefined;
    for (let attempt = 0; attempt < 2 && !cap; attempt++) {
        const answer = await host.askCap(prompt);
        if (answer === undefined) {
            return;
        }
        cap = parseCap(answer);
        if (!cap) {
            host.say('That is not a cap. Say the unit: $2 for money, or 50000 tokens.');
        }
    }
    if (!cap) {
        return;
    }
    const inputs: string[] = [];
    for (;;) {
        const raw = await host.askInput('An input for the workflow, as name=value (leave empty when there are no more)');
        if (raw === undefined || raw.trim() === '') {
            break;
        }
        const match = INPUT.exec(raw.trim());
        if (!match) {
            host.say('An input looks like name=value.');
            continue;
        }
        if (SHELL_SPECIAL.test(match[2])) {
            host.say('That value has a quote, dollar sign, backtick or backslash in it, which cannot be typed safely into a terminal. Leave it out and give it when you press Enter.');
            continue;
        }
        inputs.push(`-i ${match[1]}=${quote(match[2])}`);
    }
    host.type(['weave run', quote(script), cap.flag, cap.value, ...inputs].join(' '));
}
