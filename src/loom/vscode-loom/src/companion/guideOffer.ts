/**
 * The one-time offer to set up the Loom Guide. It is a question, never an action: "Set Up the Guide" runs the same command as
 * the walkthrough step, which shows what it writes first. It comes once the walkthrough has been seen in an earlier session,
 * in a folder with a `.loom` script, when an assistant that can run the Guide is installed and the Guide is not set up yet.
 */

export const GUIDE_OFFER_SET_UP = 'Set Up the Guide';
export const GUIDE_OFFER_NOT_NOW = 'Not now';
export const GUIDE_OFFER_NEVER = "Don't ask again";

/** Copilot Chat and Claude Code, the assistants the Guide runs on. */
export const GUIDE_HOSTS: Record<string, string> = { 'GitHub.copilot-chat': 'Copilot', 'anthropic.claude-code': 'Claude Code' };

export interface GuideOfferFacts {
    hasLoom: boolean;
    guideSetUp: boolean;
    /** The walkthrough was shown in an earlier session (never the session it first opens in). */
    walkthroughSeenBefore: boolean;
    /** Names of the installed assistants that can run the Guide. */
    hosts: string[];
    dismissedHere: boolean;
    dismissedEverywhere: boolean;
}

export function shouldOfferGuide(f: GuideOfferFacts): boolean {
    return f.hasLoom && !f.guideSetUp && f.walkthroughSeenBefore && f.hosts.length > 0 && !f.dismissedHere && !f.dismissedEverywhere;
}

export function guideOfferMessage(hosts: string[]): string {
    return `Set up the Loom Guide? It explains each stage while your coding agent builds, opens the graph, review and report at the right moment, and never edits a file. It runs on your ${hosts.join(' or ')}; you see what it writes first.`;
}

export interface GuideOfferHost {
    facts(): Promise<GuideOfferFacts>;
    show(message: string, buttons: string[]): Promise<string | undefined>;
    setFlag(scope: 'workspace' | 'global'): Promise<void>;
    runSetUp(): Promise<void>;
}

/** Asks once; remembers "Not now" for this folder and "Don't ask again" everywhere. */
export async function offerGuideSetUp(host: GuideOfferHost): Promise<void> {
    const f = await host.facts();
    if (!shouldOfferGuide(f)) {
        return;
    }
    const choice = await host.show(guideOfferMessage(f.hosts), [GUIDE_OFFER_SET_UP, GUIDE_OFFER_NOT_NOW, GUIDE_OFFER_NEVER]);
    if (choice === GUIDE_OFFER_SET_UP) {
        await host.runSetUp();
    } else if (choice === GUIDE_OFFER_NOT_NOW) {
        await host.setFlag('workspace');
    } else if (choice === GUIDE_OFFER_NEVER) {
        await host.setFlag('global');
    }
}
