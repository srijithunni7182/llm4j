import * as fs from 'fs';
import * as path from 'path';

export interface Starter {
    /** The folder name under src/loom/starters, for example 07-approval. */
    folder: string;
    category: string;
    label: string;
    detail: string;
}

/** The starters by what they teach. Every one runs with scripted models, so none needs a key; a test checks the list against the folders. */
export const STARTERS: readonly Starter[] = [
    { folder: '01-hello', category: 'First steps', label: 'Hello', detail: 'One agent answers one question' },
    { folder: '02-chain', category: 'First steps', label: 'Chain', detail: 'Summarize, critique, rewrite' },
    { folder: '03-review-loop', category: 'Write and review loops', label: 'Review loop', detail: 'Write, review, rewrite until approved' },
    { folder: '13-rewind-and-retry', category: 'Write and review loops', label: 'Rewind and retry', detail: 'Go back to a checkpoint with feedback' },
    { folder: '19-report-writer', category: 'Write and review loops', label: 'Report writer', detail: 'Check a report against a schema, restore and retry' },
    { folder: '04-router', category: 'Classifier routing', label: 'Router', detail: 'A classifier picks the specialist' },
    { folder: '08-extract-and-validate', category: 'Classifier routing', label: 'Extract and validate', detail: 'Free text to checked fields' },
    { folder: '05-parallel-research', category: 'Parallel fan-out', label: 'Parallel research', detail: 'Two researchers at once; one may fail' },
    { folder: '06-plan-and-fan-out', category: 'Parallel fan-out', label: 'Plan and fan out', detail: 'A plan becomes parallel work' },
    { folder: '18-supervise-workflows', category: 'Parallel fan-out', label: 'Supervise workflows', detail: 'Start three researchers at once and watch them' },
    { folder: '07-approval', category: 'Human-in-the-loop approvals', label: 'Approval', detail: 'A person approves once, before anything is sent' },
    { folder: '12-wait-for-a-person', category: 'Human-in-the-loop approvals', label: 'Wait for a person', detail: 'Waits days for an answer' },
    { folder: '15-earned-autonomy', category: 'Human-in-the-loop approvals', label: 'Earned autonomy', detail: 'An agent earns the right to act alone' },
    { folder: '14-supervised-agent', category: 'Budgeted agents', label: 'Supervised agent', detail: 'Rules that watch an agent' },
    { folder: '16-step-by-step-agent', category: 'Budgeted agents', label: 'Step-by-step agent', detail: 'A long job whose progress the script owns' },
    { folder: '17-compliance-boundary', category: 'Budgeted agents', label: 'Compliance boundary', detail: 'A reply is checked against house rules before it leaves' },
    { folder: '20-data-analyst', category: 'Budgeted agents', label: 'Data analyst', detail: 'Runs a model\'s script on a CSV in a sandbox' },
    { folder: '21-connect-a-system', category: 'Budgeted agents', label: 'Connect a system', detail: 'An API operation and a command line as tools, with their effects' },
    { folder: '09-handbook-qa', category: 'Memory, documents and schedules', label: 'Handbook Q&A', detail: 'Answers from your own documents (checked, not run)' },
    { folder: '10-memory-assistant', category: 'Memory, documents and schedules', label: 'Memory assistant', detail: 'Remembers each person' },
    { folder: '11-daily-digest', category: 'Memory, documents and schedules', label: 'Daily digest', detail: 'Runs itself on weekday mornings' },
];

/** Where the starters are: bundled in the package, or next to the extension when it runs from the repository. */
export function findStartersDir(extensionPath: string): string | undefined {
    return [path.join(extensionPath, 'starters'), path.join(extensionPath, '..', 'starters')].find((d) => fs.existsSync(path.join(d, STARTERS[0].folder)));
}

/** The Quick Pick rows: a separator per category, then its starters. */
export function pickItems(): Array<{ kind: 'separator'; label: string } | { kind: 'starter'; label: string; description: string; starter: Starter }> {
    const items: ReturnType<typeof pickItems> = [];
    let last = '';
    for (const starter of groupedStarters()) {
        if (starter.category !== last) {
            items.push({ kind: 'separator', label: starter.category });
            last = starter.category;
        }
        items.push({ kind: 'starter', label: starter.label, description: starter.detail, starter });
    }
    return items;
}

/** The pick sorted so a category's starters sit together, whatever order STARTERS lists them in. */
export function groupedStarters(): Starter[] {
    const order = [...new Set(STARTERS.map((s) => s.category))];
    return [...STARTERS].sort((a, b) => order.indexOf(a.category) - order.indexOf(b.category));
}

/**
 * Copies one starter into `<workspace>/<folder>` (main.loom, its .loot and tests, README, everything but a local .env) and returns the
 * path of its main.loom. Refuses to overwrite: an existing folder is a user's work.
 */
export function copyStarter(startersDir: string, starter: Starter, workspace: string): string {
    const source = path.join(startersDir, starter.folder);
    const target = path.join(workspace, starter.folder);
    if (fs.existsSync(target)) {
        throw new Error(`${starter.folder} already exists in this folder. Rename or delete it first, or open ${path.join(starter.folder, 'main.loom')}.`);
    }
    fs.cpSync(source, target, { recursive: true, filter: (src) => path.basename(src) !== '.env' });
    return path.join(target, 'main.loom');
}
