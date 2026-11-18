import * as crypto from 'crypto';
import * as fs from 'fs';
import * as path from 'path';
import { guideFolder } from './model';

/**
 * The files that make the Guide an agent of the developer's own assistant: a Copilot custom agent,
 * a Claude Code agent, and the project's MCP entry for Claude Code. Both agents carry the same
 * persona and may use only reading and the Guide's MCP tools. A file the developer changed is
 * left alone.
 */

export const AGENT_NAME = 'llm4j-guide';
export const MCP_SERVER = 'llm4j-guide';
const DESCRIPTION =
    'The Loom Guide: explains, shows and asks while your llm4j workflow is built, towards a tested, evaluated, durable workflow. It never edits files.';

/** The tools each agent may use: reading the project and the Guide's MCP server, nothing that edits or runs. */
export const COPILOT_TOOLS = ['read', 'search', `${MCP_SERVER}/*`];
export const CLAUDE_TOOLS = ['Read', 'Grep', 'Glob', `mcp__${MCP_SERVER}__*`];

export function copilotAgent(persona: string): string {
    return [
        '---',
        `name: ${AGENT_NAME}`,
        `description: ${DESCRIPTION}`,
        `tools: [${COPILOT_TOOLS.map((t) => `'${t}'`).join(', ')}]`,
        '---',
        '',
        persona.trim(),
        '',
    ].join('\n');
}

export function claudeAgent(persona: string): string {
    return [
        '---',
        `name: ${AGENT_NAME}`,
        `description: ${DESCRIPTION}`,
        `tools: ${CLAUDE_TOOLS.join(', ')}`,
        '---',
        '',
        persona.trim(),
        '',
    ].join('\n');
}

/** The MCP server line both hosts run: `java -jar <weave.jar> guide-mcp <project>`. */
export function serverCommand(java: string, jar: string, root: string): { command: string; args: string[] } {
    return { command: java, args: ['-jar', jar, 'guide-mcp', root] };
}

/**
 * The Guide's entry in `.mcp.json`. It names no path of this machine, so the file can be committed: `weave` is the launcher
 * the extension keeps on the PATH (Loom: Make weave Available in Every Terminal), and Claude Code expands the project folder.
 */
export const MCP_ENTRY = { type: 'stdio', command: 'weave', args: ['guide-mcp', '${CLAUDE_PROJECT_DIR:-.}'] };

/** `.mcp.json` with the Guide's server added or updated; every other server is kept as it was. */
export function mergeMcpJson(existing: string | undefined): string {
    let doc: { mcpServers?: Record<string, unknown> } = {};
    if (existing !== undefined && existing.trim() !== '') {
        doc = JSON.parse(existing);
    }
    doc.mcpServers = { ...(doc.mcpServers ?? {}), [MCP_SERVER]: MCP_ENTRY };
    return JSON.stringify(doc, null, 2) + '\n';
}

const hash = (text: string): string => crypto.createHash('sha256').update(text).digest('hex');

/** What `writeAgentFiles` or `refreshAgentFiles` did with each file. */
export interface Written {
    file: string;
    outcome: 'written' | 'unchanged' | 'kept-yours' | 'absent';
}

/** The relative paths the Guide's files go to. */
export const COPILOT_FILE = path.join('.github', 'agents', `${AGENT_NAME}.agent.md`);
export const CLAUDE_FILE = path.join('.claude', 'agents', `${AGENT_NAME}.md`);
export const MCP_FILE = '.mcp.json';

/**
 * Writes the agent files, when the developer asks (Set Up). A file this extension wrote before and the developer has since
 * changed is kept as it is (its hash is remembered in `.loom/guide/written.json`).
 */
export function writeAgentFiles(root: string, persona: string): Written[] {
    return apply(root, persona, false);
}

/**
 * Brings the agent files up to date with this version's persona, at start-up, without being asked. It creates nothing: a
 * file that is not there (never set up, or deleted by the developer), a file this extension did not write, and a file the
 * developer changed are all left alone, and the `.mcp.json` entry is updated only while it is still there.
 */
export function refreshAgentFiles(root: string, persona: string): Written[] {
    return apply(root, persona, true);
}

function apply(root: string, persona: string, refreshOnly: boolean): Written[] {
    const record = path.join(guideFolder(root), 'written.json');
    let hashes: Record<string, string> = {};
    try {
        hashes = JSON.parse(fs.readFileSync(record, 'utf8'));
    } catch {
        hashes = {};
    }
    const out: Written[] = [];
    const put = (rel: string, text: string, ours: (current: string) => boolean): void => {
        const file = path.join(root, rel);
        const current = fs.existsSync(file) ? fs.readFileSync(file, 'utf8') : undefined;
        if (current === undefined && refreshOnly) {
            out.push({ file: rel, outcome: 'absent' });
            return;
        }
        if (current === text) {
            out.push({ file: rel, outcome: 'unchanged' });
            hashes[rel] = hash(text);
            return;
        }
        if (current !== undefined && !ours(current)) {
            out.push({ file: rel, outcome: 'kept-yours' });
            return;
        }
        fs.mkdirSync(path.dirname(file), { recursive: true });
        fs.writeFileSync(file, text);
        hashes[rel] = hash(text);
        out.push({ file: rel, outcome: 'written' });
    };
    const unchangedSinceWritten = (rel: string) => (current: string) => hashes[rel] === hash(current);
    put(COPILOT_FILE, copilotAgent(persona), unchangedSinceWritten(COPILOT_FILE));
    put(CLAUDE_FILE, claudeAgent(persona), unchangedSinceWritten(CLAUDE_FILE));
    const mcpPath = path.join(root, MCP_FILE);
    const existing = fs.existsSync(mcpPath) ? fs.readFileSync(mcpPath, 'utf8') : undefined;
    let merged: string | undefined;
    let hasEntry = false;
    try {
        hasEntry = existing !== undefined && existing.trim() !== '' && JSON.parse(existing)?.mcpServers?.[MCP_SERVER] !== undefined;
        merged = mergeMcpJson(existing);
    } catch {
        merged = undefined;
    }
    if (refreshOnly && !hasEntry) {
        // the developer removed the Guide's server, or the file is not JSON: a refresh does not bring it back
        out.push({ file: MCP_FILE, outcome: existing === undefined ? 'absent' : 'kept-yours' });
    } else if (merged === undefined) {
        out.push({ file: MCP_FILE, outcome: 'kept-yours' });
    } else {
        // only the Guide's own entry changes, so the rest of the file is always the developer's
        put(MCP_FILE, merged, () => true);
    }
    if (!refreshOnly || out.some((w) => w.outcome === 'written' || w.outcome === 'unchanged')) {
        fs.mkdirSync(path.dirname(record), { recursive: true });
        fs.writeFileSync(record, JSON.stringify(hashes, null, 2) + '\n');
    }
    return out;
}
