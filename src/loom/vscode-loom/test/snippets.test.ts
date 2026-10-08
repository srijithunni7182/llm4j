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
const TOP = new Set(['agent', 'agent-private', 'workflow', 'budget', 'tool', 'tool-class', 'import']);
// Not checkable on their own: a task is Java (graph only), an import needs a file.
const PARSE_ONLY = new Set(['run']);
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
    if (name === 'agent' || name === 'agent-private') {
        return text + '\nworkflow Main(topic_text) {\n    delegate "x" to ' + /agent (\w+)/.exec(text)![1] + ' -> out_text\n    note "{out_text}"\n}\n';
    }
    if (TOP.has(name)) {
        return PRELUDE + text + '\nworkflow Main(topic_text) {\n    note "{topic_text}"\n}\n';
    }
    // read every variable the snippet sets, so the check's "never used" warning is not about the frame
    const sets = [...text.matchAll(/->\s*(\w+)/g)].map((m) => m[1]);
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

for (const [name, s] of Object.entries(snippets)) {
    if (SKIP.has(name)) {
        continue;
    }
    test(`the "${s.prefix}" snippet, as typed, is a script that weave accepts`, { skip }, () => {
        const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'loom-snippet-'));
        fs.mkdirSync(path.join(dir, 'prompts'));
        fs.writeFileSync(path.join(dir, 'prompts', 'writer.md'), 'You write.\n');
        const file = path.join(dir, 'main.loom');
        fs.writeFileSync(file, frame(name, expand(s.body)));
        const args = PARSE_ONLY.has(name) ? ['graph', file, '--format', 'json'] : ['check', file, '--no-env', '--format', 'json'];
        const r = child_process.spawnSync('java', ['-jar', jar, ...args], { encoding: 'utf8' });
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
