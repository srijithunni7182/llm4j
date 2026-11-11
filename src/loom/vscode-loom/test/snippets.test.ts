import test from 'node:test';
import assert from 'node:assert/strict';
import * as child_process from 'child_process';
import * as fs from 'fs';
import * as os from 'os';
import * as path from 'path';

const root = path.join(__dirname, '..', '..');
const manifest = JSON.parse(fs.readFileSync(path.join(root, 'package.json'), 'utf8'));
const snippetFile = path.join(root, 'snippets', 'loom.code-snippets');
const snippets: Record<string, { prefix: string; body: string[]; description: string }> = JSON.parse(fs.readFileSync(snippetFile, 'utf8'));

/** What the snippet types when every placeholder keeps its default: ${1:text} is text, ${1|a,b|} is a, $0 is nothing. */
function expand(body: string[]): string {
    return body
        .join('\n')
        .replace(/\$\{\d+\|([^,|]*)[^|]*\|\}/g, '$1')
        .replace(/\$\{\d+:([^}]*)\}/g, '$1')
        .replace(/\$\{\d+\}|\$\d+/g, '')
        .replace(/\t/g, '    ');
}

// Statement-level snippets go inside a workflow; the rest are whole top-level definitions.
const TOP = new Set(['extractor', 'evaluator', 'intentclassifier', 'agent', 'agent-private', 'agent-steerable', 'agent-grounded', 'intervention', 'supervise', 'workflow', 'budget', 'tool', 'tool-class', 'import', 'agent-channel']);
// Not checkable on their own: a task is Java (graph only), an import needs a file.
// halt and return end the path (the frame's own statements after them would never run), and `is ok` needs a real run result.
// remote, agent-channel and ask carry warnings by design: the card is read at run time and the host supplies the channel.
const PARSE_ONLY = new Set(['remote', 'agent-channel', 'ask', 'agent-grounded', 'run', 'halt', 'return', 'isok']);
const SKIP = new Set(['import']);

const PRELUDE = [
    'agent Writer { model: "ollama/llama3" prompt: "writer" }',
    'agent Editor { model: "ollama/llama3" system: "s" }',
    'workflow Analyze(topic_text) {',
    '    delegate "Look into {topic_text}" to Writer -> result',
    '}',
    '',
].join('\n');

function frame(name: string, text: string): string {
    if (name === 'workflow') {
        return PRELUDE + text + '\n';
    }
    if (name === 'agent' || name === 'agent-private' || name === 'agent-steerable' || name === 'agent-grounded') {
        return text + '\nworkflow Main(topic_text) {\n    delegate "x" to ' + /agent (\w+)/.exec(text)![1] + ' -> out_text\n    note "{out_text}"\n}\n';
    }
    if (name === 'check') {
        // needs a policy and an evaluator declared above it, and a model-written text to check
        return PRELUDE + 'evaluator Compliance { model: "ollama/llama3" }\npolicy HouseRules: "house-rules" checked by Compliance\n' +
            'workflow Main(topic_text) {\n    delegate "Write about {topic_text}" to Writer -> reply\n' + text.split('\n').map((l) => '    ' + l).join('\n') +
            '\n    return reply\n}\n';
    }
    if (name === 'use-tool') {
        return PRELUDE + 'tool Notify { use: class  class: "shop.Notify" effect: changes }\nworkflow Main(topic_text) {\n    delegate "Write about {topic_text}" to Writer -> publish_text\n' +
            text.split('\n').map((l) => '    ' + l).join('\n') + '\n    note "{receipt}"\n}\n';
    }
    if (name === 'for-each-max') {
        return PRELUDE + 'extractor Plan {\n    model: "ollama/llama3"\n    fields {\n        items: list<{ name: string }>\n    }\n}\nworkflow Main(topic_text) {\n    extract Plan from topic_text -> plan\n' +
            text.split('\n').map((l) => '    ' + l).join('\n') + '\n}\n';
    }
    if (name === 'remote') {
        return PRELUDE + 'agent_channel SupplierDesk { audience: staff }\n' + text + '\nworkflow Main(topic_text) {\n    delegate "Quote {topic_text}" to Supplier -> quote_text\n    note "{quote_text}"\n}\n';
    }
    if (name === 'ask') {
        // needs an agent channel declared above it
        return PRELUDE + 'agent_channel SupplierDesk { audience: staff }\nworkflow Main(topic_text) {\n' +
            text.split('\n').map((l) => '    ' + l).join('\n') + '\n    note "{currency_text.currency}"\n}\n';
    }
    if (name === 'extract') {
        // a statement that needs an extractor declared above it
        return PRELUDE + 'extractor Invoice { model: "ollama/llama3" fields { vendor: string, total: number } optional: [total] }\nworkflow Main(topic_text) {\n' +
            text.split('\n').map((l) => '    ' + l).join('\n') + '\n    note "{fields.vendor}"\n}\n';
    }
    if (name === 'intentclassify') {
        // a statement that needs a classifier declared above it
        return PRELUDE + 'intentclassifier Intent { model: "Jev/jev-latest" choose kind: a | b }\nworkflow Main(topic_text) {\n' +
            text.split('\n').map((l) => '    ' + l).join('\n') + '\n    note "{intent.kind}"\n}\n';
    }
    if (TOP.has(name)) {
        return PRELUDE + text + '\nworkflow Main(topic_text) {\n    note "{topic_text}"\n}\n';
    }
    // read every variable the snippet sets, so the check's "never used" warning is not about the frame
    const sets = [...text.matchAll(/(?:->|\bset)\s+(\w+)/g)].map((m) => m[1]);
    const reads = sets.map((v) => `    note "{${v}}"`).join('\n');
    return (
        PRELUDE +
        'workflow Main(topic_text) {\n' +
        '    delegate "Review it" to Editor -> review_result expecting { verdict: enum["OK", "REWRITE"], advice: string }\n' +
        '    delegate "Write about {topic_text}" to Writer -> publish_text\n' +
        text.split('\n').map((l) => '    ' + l).join('\n') +
        '\n' + reads + '\n    note "{review_result.verdict} {publish_text}"\n}\n'
    );
}

const jar = path.join(root, 'bin', 'weave.jar');
const hasJava = child_process.spawnSync('java', ['-version']).status === 0;
const skip = !fs.existsSync(jar) ? 'no weave.jar: run scripts/build-vsix.sh' : !hasJava ? 'java is not installed' : false;

test('the snippets file is declared for the loom language and shipped in the package', () => {
    assert.deepEqual(manifest.contributes.snippets, [{ language: 'loom', path: './snippets/loom.code-snippets' }]);
    assert.ok(manifest.files.includes('snippets/**/*'));
});

test('every snippet has a prefix, a body and a description, and prefixes are not repeated', () => {
    const prefixes = new Set<string>();
    for (const [name, s] of Object.entries(snippets)) {
        assert.ok(s.prefix && s.body.length > 0 && s.description.length > 20, name);
        assert.ok(!prefixes.has(s.prefix), `${name}: prefix ${s.prefix} is used twice`);
        prefixes.add(s.prefix);
    }
    for (const must of ['agent', 'delegate', 'loop', 'alt', 'human-prompt', 'run', 'call', 'workflow', 'budget', 'tool', 'note']) {
        assert.ok(prefixes.has(must), `a snippet for ${must}`);
    }
});

test('the snippets teach the habits the guide asks for', () => {
    assert.match(snippets['loop'].body.join('\n'), /max \$\{3:2\}/, 'a loop always has a max');
    assert.match(snippets['delegate'].body.join('\n'), /answer_text/, 'variable names are not ordinary words');
    assert.match(snippets['agent'].body.join('\n'), /prompt: /, 'prompts are files');
    assert.match(snippets['agent-private'].body.join('\n'), /guard \{ pii: mask \}/);
    assert.match(snippets['loop'].description, /max/);
});

/** The checks are started together, a few at a time, because each is a JVM start of about half a second; the tests below await their own. */
const CONCURRENT_CHECKS = 4;
let running = 0;
const waiting: Array<() => void> = [];
function slot(): Promise<void> {
    if (running < CONCURRENT_CHECKS) { running++; return Promise.resolve(); }
    return new Promise((resolve) => waiting.push(() => { running++; resolve(); }));
}
function release(): void { running--; waiting.shift()?.(); }

interface Run { status: number | null; stdout: string; stderr: string }

function runWeave(args: string[]): Promise<Run> {
    return slot().then(() => new Promise<Run>((resolve) => {
        const child = child_process.spawn('java', ['-jar', jar, ...args]);
        let stdout = '';
        let stderr = '';
        child.stdout.on('data', (c) => { stdout += c; });
        child.stderr.on('data', (c) => { stderr += c; });
        child.on('close', (status) => { release(); resolve({ status, stdout, stderr }); });
    }));
}

/** What a checked project has beside its script, written to a fresh folder; returns the script's path and the weave arguments. */
function prepare(name: string, snippet: { body: string[] }): { file: string; args: string[] } {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'loom-snippet-'));
    fs.mkdirSync(path.join(dir, 'prompts'));
    // the rules of a policy, and golden cases for each extractor and evaluator
    fs.mkdirSync(path.join(dir, 'policies'));
    fs.writeFileSync(path.join(dir, 'policies', 'house-rules.md'), '# House rules\n\n## no_refund_promise\nThe reply promises a refund.\n');
    fs.mkdirSync(path.join(dir, 'eval', 'golden'), { recursive: true });
    const fieldCase = '- id: a-001\n  name: one\n  input: "ACME total 5"\n  expected_fields: { %s: x }\n';
    fs.writeFileSync(path.join(dir, 'eval', 'golden', 'invoice.yaml'), fieldCase.replace('%s', 'vendor'));
    fs.writeFileSync(path.join(dir, 'eval', 'golden', 'plan.yaml'), fieldCase.replace('%s', 'items'));
    fs.writeFileSync(path.join(dir, 'eval', 'golden', 'compliance.yaml'),
        '- id: c-001\n  name: breaks it\n  policy: HouseRules\n  text: "We will refund you."\n  expected: { no_refund_promise: violated }\n' +
        '- id: c-002\n  name: near miss\n  policy: HouseRules\n  near_miss: true\n  text: "I cannot promise a refund."\n  expected: { no_refund_promise: clean }\n');
    fs.writeFileSync(path.join(dir, 'prompts', 'writer.md'), 'You write.\n');
    const file = path.join(dir, 'main.loom');
    fs.writeFileSync(file, frame(name, expand(snippet.body)));
    const args = PARSE_ONLY.has(name) ? ['graph', file, '--format', 'json'] : ['check', file, '--no-env', '--format', 'json'];
    return { file, args };
}

const started = new Map<string, { file: string; run: Promise<Run> }>();
if (!skip) {
    for (const [name, snippet] of Object.entries(snippets)) {
        if (SKIP.has(name)) { continue; }
        const { file, args } = prepare(name, snippet);
        started.set(name, { file, run: runWeave(args) });
    }
}

for (const [name, s] of Object.entries(snippets)) {
    if (SKIP.has(name)) {
        continue;
    }
    test(`the "${s.prefix}" snippet, as typed, is a script that weave accepts`, { skip }, async () => {
        const { file, run } = started.get(name)!;
        const r = await run;
        const where = `${name}\n${fs.readFileSync(file, 'utf8')}\n${r.stdout}${r.stderr}`;
        assert.equal(r.status, 0, where);
        const json = JSON.parse(r.stdout);
        if (PARSE_ONLY.has(name)) {
            assert.ok(!(json.diagnostics ?? []).some((d: { severity: string }) => d.severity === 'error'), where);
        } else {
            assert.equal(json.ok, true, where);
            assert.deepEqual(json.diagnostics, [], where);
        }
    });
}
