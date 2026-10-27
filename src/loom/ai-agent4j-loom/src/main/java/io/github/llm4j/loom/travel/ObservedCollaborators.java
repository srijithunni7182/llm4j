package io.github.llm4j.loom.travel;

import io.github.llm4j.LLMClient;
import io.github.llm4j.agent.Tool;
import io.github.llm4j.agent.task.Task;
import io.github.llm4j.agent.task.TaskContext;
import io.github.llm4j.agent.task.TaskEffect;
import io.github.llm4j.agent.task.TaskResult;
import io.github.llm4j.loom.ast.ToolDef;
import io.github.llm4j.loom.execution.ClassifierFactory;
import io.github.llm4j.loom.execution.LLMClientFactory;
import io.github.llm4j.loom.runtime.HumanInterface;
import io.github.llm4j.loom.runtime.RunSuspended;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import io.github.llm4j.ratelimit.Sleeper;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.stream.Stream;

/**
 * The collaborators of an observing replay. None of them does anything: a model, a classifier, a
 * tool, or a task that is asked for stops the replay with {@link ObserveStop}; a person who is
 * asked suspends it, as a person who has not answered would; time is frozen and a sleep takes no
 * time.
 */
final class ObservedCollaborators {

    private ObservedCollaborators() {}

    static final String PREFIX = "observe:";

    /**
     * Failures to inject: a target's calls fail with a category, this many times (then it is asked
     * for what it needs, which stops the replay as usual).
     */
    static final class Failing {
        private final Map<String, String> category = new java.util.HashMap<>();
        private final Map<String, Integer> left = new java.util.HashMap<>();
        private final Map<String, Integer> calls = new java.util.HashMap<>();

        Failing(Map<String, ObserveReplay.Fail> specs) {
            specs.forEach(
                    (target, f) -> {
                        category.put(target, f.category());
                        left.put(target, f.times());
                    });
        }

        /** The category the next call to {@code target} fails with, or null when it goes on. */
        synchronized String next(String target) {
            calls.merge(target, 1, Integer::sum);
            Integer n = left.get(target);
            if (n == null || n <= 0) return null;
            left.put(target, n - 1);
            return category.get(target);
        }

        synchronized int call(String target) {
            return calls.getOrDefault(target, 0);
        }
    }

    /** Models that cannot be called: they fail as told to, or stop the replay. */
    static LLMClientFactory models(Failing failing) {
        return model ->
                new LLMClient() {
                    @Override
                    public LLMResponse chat(LLMRequest request) {
                        String agent = name(model);
                        String category = failing.next(agent);
                        if (category != null)
                            throw io.github.llm4j.loom.wtest.Failures.model(
                                    category, agent, failing.call(agent));
                        throw new ObserveStop("a model answer from " + agent);
                    }

                    @Override
                    public Stream<LLMResponse> chatStream(LLMRequest request) {
                        return Stream.of(chat(request));
                    }
                };
    }

    /** Classifiers that cannot be asked: they fail as told to, or stop the replay. */
    static ClassifierFactory classifiers(Failing failing) {
        return (model, samples) -> {
            String name = name(model);
            return request -> {
                String category = failing.next(name);
                if (category != null)
                    throw io.github.llm4j.loom.wtest.Failures.model(
                            category, name, failing.call(name));
                throw new ObserveStop("a classifier answer from " + name);
            };
        };
    }

    static final LLMClientFactory MODELS = models(new Failing(Map.of()));
    static final ClassifierFactory CLASSIFIERS = classifiers(new Failing(Map.of()));

    /** People who answer from a queue; with none left the run waits for a person. */
    static HumanInterface people(java.util.Deque<String> answers) {
        return new HumanInterface() {
            @Override
            public String promptHuman(String message) {
                return next("", message);
            }

            @Override
            public String promptHuman(String stepId, String message) {
                return next(stepId, message);
            }

            @Override
            public String promptHuman(String stepId, String message, Hints hints) {
                return next(stepId, message);
            }

            private synchronized String next(String stepId, String message) {
                String answer = answers.pollFirst();
                if (answer == null) throw new RunSuspended(stepId, message);
                return answer;
            }
        };
    }

    /** A person who has not answered: the run waits for them. */
    static final HumanInterface PEOPLE =
            new HumanInterface() {
                @Override
                public String promptHuman(String message) {
                    throw waiting("", message);
                }

                @Override
                public String promptHuman(String stepId, String message) {
                    throw waiting(stepId, message);
                }

                @Override
                public String promptHuman(String stepId, String message, Hints hints) {
                    throw waiting(stepId, message);
                }

                private RunSuspended waiting(String stepId, String message) {
                    return new RunSuspended(stepId, message);
                }
            };

    /** A tool that is never run (the agent that would have called it is not run either). */
    static Tool tool(String name, ToolDef declared, Map<String, String> results, Failing failing) {
        return new Tool() {
            @Override
            public String getName() {
                return name;
            }

            @Override
            public String getDescription() {
                return "Observed tool "
                        + name
                        + (declared == null ? "" : " (" + declared.getKind() + ")");
            }

            @Override
            public String execute(Map<String, Object> args) throws Exception {
                String category = failing.next("tool:" + name);
                if (category != null)
                    throw io.github.llm4j.loom.wtest.Failures.tool(
                            category, name, failing.call("tool:" + name));
                String given = results.get(name);
                if (given != null) return given;
                throw new ObserveStop("a result from tool " + name);
            }
        };
    }

    /** A task that is never run: its result is the journal's, or the replay stops. */
    static Task task(String name, Failing failing) {
        return new Task() {
            @Override
            public String getName() {
                return name;
            }

            @Override
            public TaskEffect effect() {
                return TaskEffect.NONE;
            }

            @Override
            public TaskResult run(TaskContext context) throws Exception {
                String category = failing.next("task:" + name);
                if (category != null)
                    throw io.github.llm4j.loom.wtest.Failures.tool(
                            category, name, failing.call("task:" + name));
                throw new ObserveStop("a result from task " + name);
            }
        };
    }

    /** Time that does not pass. */
    static final class FrozenClock extends Clock implements Sleeper {
        private final Instant now;

        FrozenClock(Instant now) {
            this.now = now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public void sleep(Duration duration) {
            // a sleep in a replay takes no time: its wake time is the journal's
        }
    }

    static String name(String model) {
        return io.github.llm4j.loom.execution.ModelNames.agentOf(model);
    }
}
