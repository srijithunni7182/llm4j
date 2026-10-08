/** The pages `weave guide` serves, in the order a newcomer reads them. The jar is the source of truth; this is only the menu. */
export const GUIDE_PAGES: { page: string; label: string }[] = [
    { page: 'readme', label: 'Start here: the path, and whether you want tests first' },
    { page: '1', label: '1. Decide your agents' },
    { page: '2', label: '2. Build a golden dataset (optional)' },
    { page: '3', label: '3. Test the prompts' },
    { page: '4', label: '4. Optimize the prompts' },
    { page: '5', label: '5. Test each agent, with spend caps' },
    { page: '6', label: '6. Build the workflow' },
    { page: '7', label: '7. Validate and audit' },
    { page: '8', label: '8. Test the trajectory' },
    { page: '9', label: '9. Go live' },
    { page: '10', label: '10. Best practices and checklist' },
    { page: 'loom', label: 'The Loom reference' },
];

/** What the two guide commands need from the editor and from `weave`; the commands give the real ones, tests give fakes. */
export interface GuideHost {
    /** Runs `java -jar weave.jar <args>`; resolves with what it printed, rejects with a message a person can read. */
    weave(args: string[]): Promise<string>;
    /** The folder the person has open, or undefined when none is. */
    workspaceFolder(): string | undefined;
    pick(choices: { page: string; label: string }[]): Promise<{ page: string; label: string } | undefined>;
    /** Shows text in a read-only markdown tab. */
    showMarkdown(text: string): Promise<void>;
    confirm(message: string, action: string): Promise<boolean>;
    report(message: string, level: 'info' | 'warning' | 'error'): void;
}

/** "Loom: Open Guide": the page the person picks, printed by the jar they already have, with no repository and no network. */
export async function openGuide(host: GuideHost): Promise<boolean> {
    const picked = await host.pick(GUIDE_PAGES);
    if (!picked) {
        return false;
    }
    try {
        await host.showMarkdown(await host.weave(['guide', picked.page]));
        return true;
    } catch (e) {
        host.report(`Loom: could not read the guide: ${(e as Error).message}`, 'error');
        return false;
    }
}

/** "Install the Loom skill in this project": writes the skill and the guide under .claude/skills, asking before it replaces an older copy. */
export async function installSkill(host: GuideHost): Promise<boolean> {
    const folder = host.workspaceFolder();
    if (!folder) {
        host.report('Loom: open a folder first; the skill is installed inside the project.', 'error');
        return false;
    }
    try {
        host.report(await host.weave(['guide', '--install-skill', folder]), 'info');
        return true;
    } catch (first) {
        if (!/already in/.test((first as Error).message)) {
            host.report(`Loom: could not install the skill: ${(first as Error).message}`, 'error');
            return false;
        }
        if (!(await host.confirm('The Loom skill is already in this project. Replace it with the one that ships with this extension?', 'Replace'))) {
            return false;
        }
        try {
            host.report(await host.weave(['guide', '--install-skill', folder, '--force']), 'info');
            return true;
        } catch (second) {
            host.report(`Loom: could not install the skill: ${(second as Error).message}`, 'error');
            return false;
        }
    }
}
