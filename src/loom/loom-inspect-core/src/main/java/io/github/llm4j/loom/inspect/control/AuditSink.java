package io.github.llm4j.loom.inspect.control;

import java.util.Map;

/**
 * Where a record of every control and every sign-in goes: who, when, from where, what, to which
 * run, and the outcome. Never a password or a value.
 */
public interface AuditSink {

    void record(Map<String, Object> entry);

    /** Records nothing. For tests that do not care. */
    AuditSink NONE = entry -> {};
}
