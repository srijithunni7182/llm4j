import { strict as assert } from 'assert';
import * as fs from 'fs';
import * as path from 'path';
import { test } from 'node:test';
import { RESERVED_WORDS } from '../src/semantic/reserved';

const lexer = path.join(__dirname, '..', '..', '..', 'ai-agent4j-loom', 'src', 'main', 'java', 'io', 'github', 'llm4j', 'loom', 'lexer', 'Lexer.java');

test('R23: the words rename refuses are the words the language\'s lexer treats as keywords', { skip: !fs.existsSync(lexer) && 'the Java module is not here' }, () => {
    const source = fs.readFileSync(lexer, 'utf8');
    const keywords = [...source.matchAll(/KEYWORDS\.put\("([a-z_]+)"/g)].map((m) => m[1]).sort();
    assert.ok(keywords.length > 40);
    assert.deepEqual([...RESERVED_WORDS].sort(), keywords);
});
