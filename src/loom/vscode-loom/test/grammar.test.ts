import test from 'node:test';
import assert from 'node:assert/strict';
import * as fs from 'fs';
import * as path from 'path';

/**
 * A small stand-in for a TextMate tokenizer: at each position the earliest match of any rule of the grammar wins (the first listed on a tie), which is what
 * the editor does for rules without begin/end. The grammar's patterns are plain regular expressions, so a JavaScript engine reads them.
 */
const grammar = JSON.parse(fs.readFileSync(path.join(__dirname, '..', '..', 'syntaxes', 'loom.tmLanguage.json'), 'utf8'));

interface Rule { name?: string; re: RegExp; captures?: Record<string, { name: string }> }

function rulesOf(patterns: any[]): Rule[] {
    const out: Rule[] = [];
    for (const p of patterns) {
        const rule = p.include ? grammar.repository[p.include.replace('#', '')] : p;
        if (rule.patterns && !rule.match && !rule.begin) out.push(...rulesOf(rule.patterns));
        else if (rule.match) out.push({ name: rule.name, re: new RegExp(rule.match, 'y'), captures: rule.captures });
        else if (rule.begin) out.push({ name: rule.name, re: new RegExp('"[^"\n]*"?', 'y') });
    }
    return out;
}

const RULES = rulesOf(grammar.patterns);

/** The scoped words of a line: [text, scope]. */
function tokenize(line: string): Array<[string, string]> {
    const out: Array<[string, string]> = [];
    let pos = 0;
    while (pos < line.length) {
        let best: { rule: Rule; m: RegExpExecArray; at: number } | undefined;
        for (const rule of RULES) {
            for (let at = pos; at < line.length; at++) {
                rule.re.lastIndex = at;
                const m = rule.re.exec(line);
                if (m && m[0].length > 0) {
                    if (!best || at < best.at) best = { rule, m, at };
                    break;
                }
            }
        }
        if (!best) break;
        if (best.rule.captures) {
            // a rule with captures scopes the parts it names
            const m = best.m;
            let cursor = best.at;
            for (let i = 1; i < m.length; i++) {
                const part = m[i];
                if (part === undefined) continue;
                const start = line.indexOf(part, cursor);
                cursor = start + part.length;
                const scope = best.rule.captures[String(i)];
                if (scope) out.push([part, scope.name]);
            }
        } else if (best.rule.name) {
            out.push([best.m[0], best.rule.name]);
        }
        pos = best.at + best.m[0].length;
    }
    return out;
}

const scopeOf = (line: string, word: string): string | undefined => tokenize(line).find(([t]) => t === word)?.[1];

test('B5: the words of a ladder are highlighted as keywords', () => {
    const lines: Array<[string, string]> = [
        ['ladder RefundLadder {', 'ladder'], ['    rung suggest {', 'rung'], ['        climb when history.count >= 10', 'climb'],
        ['        slip to suggest when history.count < 3', 'slip'], ['        sample 5% blind', 'sample'], ['        escalate when case.amount > 200', 'escalate'],
        ['    climbing needs approval from risk-owner', 'climbing'], ['    ceiling: suggest', 'ceiling'], ['measure edit_size(c) = distance(c.proposal.value, c.response.final)', 'measure'],
        ['    proposes: post', 'proposes'], ['        mode: confirm', 'mode'],
    ];
    for (const [line, word] of lines) assert.equal(scopeOf(line, word), 'keyword.control.loom', `${word} in: ${line}`);
});

test('B5: the three modes are told apart from ordinary names, but only after mode:', () => {
    for (const mode of ['blind', 'confirm', 'autonomous']) assert.equal(scopeOf(`        mode: ${mode}`, mode), 'support.constant.loom', mode);
    assert.notEqual(scopeOf('note "x"  confirm', 'confirm'), 'support.constant.loom');
});

test('B5: durations, percentages and decimals are numbers', () => {
    assert.equal(scopeOf('climb when history.since(30d).count >= 10', '30d'), 'constant.numeric.loom');
    assert.equal(scopeOf('climb when history.rate(agrees) >= 90%', '90%'), 'constant.numeric.loom');
    assert.equal(scopeOf('climb when history.rate(agrees).lower95 >= 0.9', '0.9'), 'constant.numeric.loom');
    assert.equal(scopeOf('escalate when case.amount > 200', '200'), 'constant.numeric.loom');
    assert.equal(scopeOf('sample 5% blind', '5%'), 'constant.numeric.loom');
});

test('B5: comments and strings stay what they were, whatever words they hold', () => {
    assert.equal(scopeOf('    // climb when', '// climb when'), 'comment.line.double-slash.loom');
    assert.equal(tokenize('note "climb when 90%"').find(([t]) => t.startsWith('"'))?.[1], 'string.quoted.double.loom');
});
