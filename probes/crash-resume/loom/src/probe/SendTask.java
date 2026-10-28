package probe;

import io.github.llm4j.agent.task.Task;
import io.github.llm4j.agent.task.TaskContext;
import io.github.llm4j.agent.task.TaskResult;
import io.github.llm4j.agent.tool.EffectPolicy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * A task that sends something (here: appends a line to a file, so the sends can be counted). With
 * CRASH_AFTER_SEND set, the process dies right after the send and before the runtime can record that it
 * was done: the window the probe is about.
 */
public abstract class SendTask implements Task {

    private final boolean idempotent;

    SendTask(boolean idempotent) {
        this.idempotent = idempotent;
    }

    @Override
    public String getName() {
        return "Send";
    }

    @Override
    public EffectPolicy policy() {
        return new EffectPolicy(EffectPolicy.OnUnknown.SKIP, idempotent, 0);
    }

    @Override
    public TaskResult run(TaskContext ctx) throws Exception {
        try {
            Path log = Path.of(System.getenv("PROBE_SENDS"));
            Files.writeString(log, ctx.idempotencyKey() + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        if (System.getenv("CRASH_AFTER_SEND") != null) Runtime.getRuntime().halt(137);
        return TaskResult.value("sent");
    }

    /** Not idempotent: the default and the safe assumption. */
    public static final class Plain extends SendTask {
        public Plain() {
            super(false);
        }
    }

    /** The receiver deduplicates by the key, so the runtime may run it again with the same key. */
    public static final class Idempotent extends SendTask {
        public Idempotent() {
            super(true);
        }
    }

    /** A pure step after the send that can kill the process: the crash that comes after the send was recorded. */
    public static final class Boom implements Task {
        @Override
        public String getName() {
            return "Boom";
        }

        @Override
        public io.github.llm4j.agent.task.TaskEffect effect() {
            return io.github.llm4j.agent.task.TaskEffect.NONE;
        }

        @Override
        public TaskResult run(TaskContext ctx) {
            if (System.getenv("CRASH_LATE") != null) Runtime.getRuntime().halt(137);
            return TaskResult.ok();
        }
    }
}
