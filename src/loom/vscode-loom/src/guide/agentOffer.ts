/**
 * The one-time offer to set up the project's coding agent. It is a question, never an action: it
 * writes nothing itself; "Set up" runs the same command as the button in the welcome view, which
 * shows the person what it will write first.
 */

export const OFFER_SET_UP = 'Set up';
export const OFFER_NOT_NOW = 'Not now';
export const OFFER_NEVER = "Don't ask again";

export interface OfferHost {
    hasLoomFile(): Promise<boolean>;
    /** True when a block or skill written by "Set Up My Coding Agent" is already in the folder. */
    agentInstructionsPresent(): Promise<boolean>;
    getFlag(scope: 'workspace' | 'global'): boolean;
    setFlag(scope: 'workspace' | 'global'): Promise<void>;
    /** Shows the non-modal notification; resolves to the button pressed, or undefined when it was closed. */
    show(message: string, buttons: string[]): Promise<string | undefined>;
    runSetUp(): Promise<void>;
}

export function shouldOffer(s: { hasLoom: boolean; agentSetUp: boolean; dismissedHere: boolean; dismissedEverywhere: boolean }): boolean {
    return s.hasLoom && !s.agentSetUp && !s.dismissedHere && !s.dismissedEverywhere;
}

export async function offerAgentSetup(host: OfferHost): Promise<void> {
    const ask = shouldOffer({
        hasLoom: await host.hasLoomFile(),
        agentSetUp: await host.agentInstructionsPresent(),
        dismissedHere: host.getFlag('workspace'),
        dismissedEverywhere: host.getFlag('global'),
    });
    if (!ask) {
        return;
    }
    const choice = await host.show('Set up your coding agent to build with Loom? It writes the instructions your agent reads (you see the list first).', [OFFER_SET_UP, OFFER_NOT_NOW, OFFER_NEVER]);
    if (choice === OFFER_SET_UP) {
        await host.runSetUp();
    } else if (choice === OFFER_NOT_NOW) {
        await host.setFlag('workspace');
    } else if (choice === OFFER_NEVER) {
        await host.setFlag('global');
    }
}
