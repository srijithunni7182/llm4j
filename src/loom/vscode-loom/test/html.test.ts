import test from 'node:test';
import assert from 'node:assert/strict';
import { randomBytes } from 'crypto';
import { contentSecurityPolicy, newNonce, renderPanelHtml } from '../src/graph/html';

const html = (nonce = 'abc123') => renderPanelHtml({ cspSource: 'vscode-webview://x', nonce, uri: (name) => `vscode-webview://x/media/${name}` });

test('the policy allows nothing but the extension\'s own files and nonce-marked scripts', () => {
    assert.equal(contentSecurityPolicy('vscode-webview://x', 'abc'), "default-src 'none'; img-src vscode-webview://x; style-src vscode-webview://x; script-src 'nonce-abc'");
    const page = html();
    assert.ok(!/unsafe-inline|unsafe-eval|data:|https?:/.test(page.match(/Content-Security-Policy" content="([^"]*)"/)![1]));
});

test('every script has the nonce and loads a file of the extension, and there is no inline script', () => {
    const scripts = html('N0nce').match(/<script[^>]*>/g)!;
    assert.equal(scripts.length, 2);
    scripts.forEach((s) => assert.ok(/nonce="N0nce"/.test(s) && /src="vscode-webview:\/\/x\/media\//.test(s), s));
    assert.ok(!/<script[^>]*>[^<]+<\/script>/.test(html()), 'a script element with a body');
});

test('there are no inline styles and no event handler attributes', () => {
    const page = html();
    assert.ok(!/\sstyle=/.test(page));
    assert.ok(!/<style/.test(page));
    assert.ok(!/\son[a-z]+=/.test(page));
});

test('nothing is loaded from outside the extension', () => {
    const page = html();
    const loads = [...page.matchAll(/(?:src|href)="([^"]+)"/g)].map((m) => m[1]);
    assert.ok(loads.length >= 6);
    loads.forEach((url) => assert.ok(url.startsWith('vscode-webview://x/media/'), url));
});

test('the toolbar logo is decorative and the loading and empty states carry the name', () => {
    const page = html();
    assert.match(page, /class="tile"><img src="[^"]*loom-mark-128\.png" alt="">/);
    assert.equal((page.match(/loom-logo-320\.png" alt="Loom"/g) || []).length, 2);
});

test('the page has the regions the panel script fills in', () => {
    const page = html();
    for (const id of ['workflow', 'zoomIn', 'zoomOut', 'fit', 'legendButton', 'mermaid', 'crumbs', 'stale', 'diagnostics', 'canvas', 'loading', 'empty', 'legend', 'card', 'files', 'selected']) {
        assert.ok(page.includes(`id="${id}"`), id);
    }
});

test('a nonce is letters and digits only and differs each time', () => {
    const a = newNonce(randomBytes);
    const b = newNonce(randomBytes);
    assert.match(a, /^[A-Za-z0-9]{16,}$/);
    assert.notEqual(a, b);
});
