import { strict as assert } from 'assert';
import * as fs from 'fs';
import * as path from 'path';
import { test } from 'node:test';

const root = path.join(__dirname, '..', '..');
const run = fs.readFileSync(path.join(root, 'src', 'commands', 'runWorkflow.ts'), 'utf8');
const body = run.slice(run.indexOf('export function runWorkflowCommand'));

test('R42, A17: Run starts the workflow first; the review is only mentioned afterwards, and Run never waits for anything', () => {
    assert.ok(body.indexOf("spawn('java'") >= 0 && body.indexOf('offerReviewAfterRun(') > body.indexOf("spawn('java'"), 'the run is started before the review is mentioned');
    assert.ok(!/\bawait\b/.test(body), 'Run does not await a review, a prompt or anything else');
    assert.ok(!/showQuickPick|showInputBox|modal\s*:\s*true/.test(body), 'Run never asks');
});

test('R42: the notice is a quiet message with two ways out and no condition for running', () => {
    const code = fs.readFileSync(path.join(root, 'src', 'review', 'vscodeReview.ts'), 'utf8');
    const notice = code.slice(code.indexOf('export function offerReviewAfterRun'), code.indexOf('export function registerReview') > 0 ? code.indexOf('/**\n * Wires the review') : undefined);
    assert.match(notice, /showInformationMessage/);
    assert.match(notice, /'Review'/);
    assert.match(notice, /Don't show again for this workspace/);
    assert.ok(!/modal/.test(notice), 'not modal');
    assert.ok(!/showWarningMessage|showErrorMessage/.test(notice), 'not a warning');
});

test('R43: nothing the extension registers is disabled by the state of a review', () => {
    const code = fs.readFileSync(path.join(root, 'src', 'review', 'vscodeReview.ts'), 'utf8');
    assert.ok(!/setContext/.test(code), 'no context key about the review is set, so no menu can depend on it');
});
