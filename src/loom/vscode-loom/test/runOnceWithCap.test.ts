import test from 'node:test';
import assert from 'node:assert/strict';
import { parseCap, runOnceWithCap, RunOnceHost } from '../src/commands/runOnceWithCap';

class Host implements RunOnceHost {
    asked: string[] = [];
    typed: string[] = [];
    said: string[] = [];
    constructor(private readonly caps: Array<string | undefined>, private readonly inputs: Array<string | undefined> = [undefined], private readonly worst: string | null = '$0.42', private readonly script: string | null = '/p/main.loom') {}
    scriptFile() { return this.script ?? undefined; }
    async estimate() { return this.worst ?? undefined; }
    async askCap(prompt: string) { this.asked.push('cap: ' + prompt); return this.caps.shift(); }
    async askInput(prompt: string) { this.asked.push('input: ' + prompt); return this.inputs.shift(); }
    type(command: string) { this.typed.push(command); }
    say(message: string) { this.said.push(message); }
}

test('R6: the cap is the first question, with the worst-case estimate beside it', async () => {
    const host = new Host(['$2']);
    await runOnceWithCap(host);
    assert.match(host.asked[0], /^cap: /);
    assert.match(host.asked[0], /worst case \$0\.42/);
});

test('R6: the command is typed with the cap, and not run', async () => {
    const host = new Host(['$2']);
    await runOnceWithCap(host);
    assert.deepEqual(host.typed, ['weave run "/p/main.loom" --max-cost 2']);
});

test('R6: a token cap becomes --max-tokens, and inputs are added after it', async () => {
    const host = new Host(['50000 tokens'], ['topic=solar power', 'tone=plain', '']);
    await runOnceWithCap(host);
    assert.deepEqual(host.typed, ['weave run "/p/main.loom" --max-tokens 50000 -i topic="solar power" -i tone="plain"']);
});

test('R6: cancelling the cap question runs nothing', async () => {
    const host = new Host([undefined]);
    await runOnceWithCap(host);
    assert.deepEqual(host.typed, []);
    assert.equal(host.asked.length, 1, 'no later question is asked without a cap');
});

test('R6: a cap without a unit or with nothing in it is not a cap', async () => {
    for (const bad of ['', '  ', '50000', 'lots', '$0', '-5 tokens', '$2; rm -rf /']) {
        assert.equal(parseCap(bad), undefined, JSON.stringify(bad));
    }
    assert.deepEqual(parseCap('$1.50'), { flag: '--max-cost', value: '1.50' });
    assert.deepEqual(parseCap('200,000 tokens'), { flag: '--max-tokens', value: '200000' });
});

test('R6: an unusable cap asks again once with the reason instead of running', async () => {
    const host = new Host(['50000', '$1']);
    await runOnceWithCap(host);
    assert.equal(host.asked.length >= 2, true);
    assert.match(host.said[0], /unit/);
    assert.deepEqual(host.typed, ['weave run "/p/main.loom" --max-cost 1']);
});

test('R6: an input that could break out of the command is refused, not typed', async () => {
    const host = new Host(['$1'], ['topic=a"; rm -rf ~; "', '']);
    await runOnceWithCap(host);
    assert.ok(host.typed.every((c) => !c.includes('rm -rf')));
    assert.match(host.said.join(' '), /quote|special/i);
});

test('R6: with no Loom file there is nothing to run and it says so', async () => {
    const host = new Host(['$1'], [undefined], null, null);
    await runOnceWithCap(host);
    assert.deepEqual(host.typed, []);
    assert.match(host.said[0], /Open a \.loom file/);
});

test('R6: when no estimate is known the prompt says so instead of leaving it out', async () => {
    const host = new Host(['$1'], [undefined], null);
    await runOnceWithCap(host);
    assert.match(host.asked[0], /no estimate yet/);
});
