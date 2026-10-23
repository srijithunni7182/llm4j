package io.github.llm4j.loom.travel;

import io.github.llm4j.loom.execution.VariableView;
import io.github.llm4j.loom.runtime.RunJournal;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * The replay as it stands before a statement. Everything here is meant to be used from the thread
 * the gate was called on, while it waits.
 *
 * @param step the id of the statement about to run
 * @param variables the variables now, cut and masked
 * @param journal the replay's own journal now (what the steps before this one left)
 * @param calls the model calls made so far by a live replay
 * @param tokens the tokens they used
 * @param resolve the value of a variable path now (null when it has none)
 * @param test whether a condition holds now
 * @param bind sets a variable, as an edit from outside the script
 */
public record StepView(
        String step,
        Supplier<List<VariableView>> variables,
        Supplier<Map<String, RunJournal.Entry>> journal,
        long calls,
        long tokens,
        Function<String, Object> resolve,
        Predicate<String> test,
        BiConsumer<String, Object> bind) {}
