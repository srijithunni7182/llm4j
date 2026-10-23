import { strict as assert } from 'assert';
import { test } from 'node:test';
import { applyEdit, offsetAt, positionAt, singleEdit } from '../src/review/edits';

test('identical texts need no edit', () => {
    assert.equal(singleEdit('a\nb', 'a\nb'), undefined);
});

test('an insertion in the middle is one edit that touches only that place', () => {
    const e = singleEdit('agent A {\n  model: "m"\n}\n', 'agent A {\n  model: "m"\n  approve: Mailer\n}\n')!;
    assert.deepEqual(e, { startLine: 2, startColumn: 0, endLine: 2, endColumn: 0, newText: '  approve: Mailer\n' });
});

test('a deletion and a replacement', () => {
    const a = 'one\ntwo\nthree\n';
    assert.equal(applyEdit(a, singleEdit(a, 'one\nthree\n')!), 'one\nthree\n');
    assert.equal(applyEdit(a, singleEdit(a, 'one\nTWO!\nthree\n')!), 'one\nTWO!\nthree\n');
});

test('an edit at the very start and at the very end', () => {
    assert.equal(applyEdit('b\n', singleEdit('b\n', 'a\nb\n')!), 'a\nb\n');
    assert.equal(applyEdit('a\n', singleEdit('a\n', 'a\nb\n')!), 'a\nb\n');
});

test('the whole text replaced, and text made empty', () => {
    assert.equal(applyEdit('abc', singleEdit('abc', 'xyz')!), 'xyz');
    assert.equal(applyEdit('abc', singleEdit('abc', '')!), '');
    assert.equal(applyEdit('', singleEdit('', 'abc')!), 'abc');
});

test('R21: columns count UTF-16 code units, so an emoji is two', () => {
    const before = 'note "😀 hi"\n';
    const after = 'note "😀 hello"\n';
    const e = singleEdit(before, after)!;
    assert.equal(e.startColumn, 'note "😀 h'.length, 'after the emoji, counted in UTF-16 units');
    assert.equal(applyEdit(before, e), after);
});

test('an edit never cuts an emoji in two', () => {
    const before = 'a😀b';
    const after = 'a😁b'; // the two emoji share their first code unit
    const e = singleEdit(before, after)!;
    assert.equal(applyEdit(before, e), after);
    assert.ok(e.newText === '😁' || e.newText.length % 2 === 0, JSON.stringify(e.newText));
});

test('R21: CRLF and lone CR line ends count as one line end each', () => {
    const before = 'a\r\nb\r\nc\r\n';
    const after = 'a\r\nb\r\nX\r\nc\r\n';
    const e = singleEdit(before, after)!;
    assert.equal(e.startLine, 2);
    assert.equal(applyEdit(before, e), after);
    assert.deepEqual(positionAt('a\r\nb', 3), { line: 1, column: 0 });
    assert.deepEqual(positionAt('a\rb', 2), { line: 1, column: 0 });
    assert.equal(offsetAt('a\r\nb\r\nc', 2, 0), 6);
});

test('R21: a tab is one column', () => {
    const before = '\tagent A {}\n';
    const after = '\tagent B {}\n';
    const e = singleEdit(before, after)!;
    assert.equal(e.startColumn, '\tagent '.length);
});

test('an edit never splits a CRLF', () => {
    const before = 'a\r\nb';
    const after = 'a\nb';
    const e = singleEdit(before, after)!;
    assert.equal(applyEdit(before, e), after);
});

test('property: for random texts the one edit always turns before into after', () => {
    const alphabet = ['a', 'b', ' ', '\n', '\r\n', '\t', '😀', 'é', '{', '}'];
    let seed = 12345;
    const rnd = (n: number): number => {
        seed = (seed * 1103515245 + 12345) & 0x7fffffff;
        return seed % n;
    };
    const text = (): string => Array.from({ length: rnd(30) }, () => alphabet[rnd(alphabet.length)]).join('');
    for (let i = 0; i < 2000; i++) {
        const a = text();
        const b = rnd(3) === 0 ? text() : a.slice(0, rnd(a.length + 1)) + text() + a.slice(rnd(a.length + 1));
        const e = singleEdit(a, b);
        assert.equal(e === undefined ? a : applyEdit(a, e), b, JSON.stringify([a, b]));
    }
});
