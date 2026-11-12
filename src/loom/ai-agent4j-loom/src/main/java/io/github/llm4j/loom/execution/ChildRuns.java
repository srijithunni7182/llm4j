package io.github.llm4j.loom.execution;

import io.github.llm4j.loom.runtime.RunJournal;

/**
 * Where a started workflow keeps its own run (decisions D3, D4 and D18). The host that builds an
 * executor supplies one: {@code LoomHost} over its {@code Persistence}, the {@code weave} command
 * over sibling folders. An executor given only a bare {@link RunJournal} has none, so {@code start}
 * there fails closed.
 *
 * <p>A child is a run like any other: it has its own journal, found under its own id, and a record
 * that names its parent.
 */
@FunctionalInterface
public interface ChildRuns {

    /**
     * The journal of the child run {@code childRunId}: the same journal every time the same id is
     * asked for, so a resumed parent re-attaches to the child it started and not to a new one.
     */
    RunJournal openJournal(String childRunId);

    /**
     * Notes that {@code childRunId} is a child of {@code parentRunId}, running {@code workflow}, so
     * the registry can list and link it. Called every time a parent starts or re-attaches to the
     * child, so it must be idempotent.
     */
    default void record(String childRunId, String parentRunId, String workflow) {}

    /**
     * Notes that the child ended up as {@code status} (see {@code ChildStatus#word}), so its record
     * can say so. Called from the thread that ran the child, once it has stopped for now.
     */
    default void settled(String childRunId, io.github.llm4j.loom.runtime.ChildStatus status) {}
}
