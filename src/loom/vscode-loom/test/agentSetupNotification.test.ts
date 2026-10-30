import test from 'node:test';
import assert from 'node:assert/strict';
import { offerAgentSetup, shouldOffer, OfferHost, OFFER_SET_UP, OFFER_NOT_NOW, OFFER_NEVER } from '../src/guide/agentOffer';

function host(over: Partial<{ hasLoom: boolean; agentSetUp: boolean; flags: Record<string, boolean>; choice: string | undefined }> = {}) {
    const state = { hasLoom: true, agentSetUp: false, flags: {} as Record<string, boolean>, choice: undefined as string | undefined, shown: 0, ran: 0, ...over };
    const h: OfferHost & { state: typeof state } = {
        state,
        async hasLoomFile() { return state.hasLoom; },
        async agentInstructionsPresent() { return state.agentSetUp; },
        getFlag(key: string) { return state.flags[key] === true; },
        async setFlag(key: string) { state.flags[key] = true; },
        async show() { state.shown++; return state.choice; },
        async runSetUp() { state.ran++; },
    };
    return h;
}

test('A8: the offer is made for a folder with a .loom file and no coding-agent set-up, once', async () => {
    const h = host({ choice: OFFER_NOT_NOW });
    await offerAgentSetup(h);
    assert.equal(h.state.shown, 1);
});

test('A8: nothing is offered without a .loom file, or when the agent is already set up', async () => {
    const none = host({ hasLoom: false });
    await offerAgentSetup(none);
    assert.equal(none.state.shown, 0);
    const done = host({ agentSetUp: true });
    await offerAgentSetup(done);
    assert.equal(done.state.shown, 0);
});

test('A8: "Set up" runs the existing command and writes nothing itself', async () => {
    const h = host({ choice: OFFER_SET_UP });
    await offerAgentSetup(h);
    assert.equal(h.state.ran, 1);
});

test('A8: a closed notification is not a yes', async () => {
    const h = host({ choice: undefined });
    await offerAgentSetup(h);
    assert.equal(h.state.ran, 0);
    assert.deepEqual(h.state.flags, {});
});

test('A8: "Not now" is remembered for this workspace and "Don\'t ask again" for every workspace', async () => {
    const later = host({ choice: OFFER_NOT_NOW });
    await offerAgentSetup(later);
    assert.equal(later.state.flags['workspace'], true);
    assert.equal(later.state.flags['global'], undefined);
    await offerAgentSetup(later);
    assert.equal(later.state.shown, 1, 'not asked again in the same workspace');

    const never = host({ choice: OFFER_NEVER });
    await offerAgentSetup(never);
    assert.equal(never.state.flags['global'], true);
    const other = host({ flags: { global: true } });
    await offerAgentSetup(other);
    assert.equal(other.state.shown, 0, 'not asked in any workspace');
});

test('shouldOffer is the whole rule', () => {
    assert.equal(shouldOffer({ hasLoom: true, agentSetUp: false, dismissedHere: false, dismissedEverywhere: false }), true);
    for (const off of [{ hasLoom: false }, { agentSetUp: true }, { dismissedHere: true }, { dismissedEverywhere: true }]) {
        assert.equal(shouldOffer({ hasLoom: true, agentSetUp: false, dismissedHere: false, dismissedEverywhere: false, ...off }), false, JSON.stringify(off));
    }
});
