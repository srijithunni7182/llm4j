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

/** `.mcp.json` with the Guide's server added or updated; every other server is kept as it was. */
export function mergeMcpJson(existing: string | undefined, java: string, jar: string): string {
    let doc: { mcpServers?: Record<string, unknown> } = {};
    if (existing !== undefined && existing.trim() !== '') {
        doc = JSON.parse(existing);
    }
    doc.mcpServers = { ...(doc.mcpServers ?? {}), [MCP_SERVER]: { type: 'stdio', ...serverCommand(java, jar, '.') } };
    return JSON.stringify(doc, null, 2) + '\n';
}

const hash = (text: string): string => crypto.createHash('sha256').update(text).digest('hex');

/** What `writeAgentFiles` did with each file. */
export interface Written {
    file: string;
    outcome: 'written' | 'unchanged' | 'kept-yours';
}

/** The relative paths the Guide's files go to. */
export const COPILOT_FILE = path.join('.github', 'agents', `${AGENT_NAME}.agent.md`);
export const CLAUDE_FILE = path.join('.claude', 'agents', `${AGENT_NAME}.md`);
export const MCP_FILE = '.mcp.json';

/**
 * Writes the agent files. A file this extension wrote before and the developer has since changed
 * is kept as it is (its hash is remembered in `.loom/guide/written.json`).
 */
export function writeAgentFiles(root: string, persona: string, java: string, jar: string): Written[] {
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
    try {
        merged = mergeMcpJson(existing, java, jar);
    } catch {
        merged = undefined;
    }
    if (merged === undefined) {
        out.push({ file: MCP_FILE, outcome: 'kept-yours' });
    } else {
        // only the Guide's own entry changes, so the rest of the file is always the developer's
        put(MCP_FILE, merged, () => true);
    }
    fs.mkdirSync(path.dirname(record), { recursive: true });
    fs.writeFileSync(record, JSON.stringify(hashes, null, 2) + '\n');
    return out;
}
