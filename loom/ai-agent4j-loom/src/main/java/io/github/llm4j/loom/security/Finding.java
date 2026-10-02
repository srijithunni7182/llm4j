package io.github.llm4j.loom.security;

import java.util.List;

/**
 * One thing the audit found in a script.
 *
 * @param rule   a stable id, such as {@code LA01}
 * @param where  what it is about: an agent, a tool, a decision, a rewind
 * @param line   the script line, or 0 when it is about the whole script
 * @param risk   why it matters, in plain words
 * @param fix    what to change
 */
public record Finding(String rule, Severity severity, List<Owasp> owasp, String where, int line, String title, String risk, String fix) {
    public Finding {
        owasp = List.copyOf(owasp);
    }
}
