package io.github.llm4j.tools;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One program to run in a sandbox (phase 35, D6): a name and an argument list (never a line for a
 * shell), a working directory inside the workspace, what to send on its input, its environment
 * (empty unless the script named a variable), and the limits it runs under.
 *
 * @param program a name from the tool's {@code allow} list
 * @param args the arguments, one element each
 * @param workspace the workspace root, the only directory the program sees
 * @param cwd the working directory, relative to the workspace ("" for its root)
 * @param protect paths relative to the workspace that the program may only read
 * @param stdin what the program reads on its input, or null for nothing
 * @param env the environment, by name; a secret is here only for the length of the run
 * @param limits time, output and resource limits
 * @param network what the program may reach
 * @param name a stable name for the run (a container's name), from the run and step
 */
public record SandboxRequest(
        String program,
        List<String> args,
        Path workspace,
        String cwd,
        List<String> protect,
        String stdin,
        Map<String, String> env,
        SandboxLimits limits,
        SandboxNetwork network,
        String name) {

    /** Checks that nothing is missing and copies the lists. */
    public SandboxRequest {
        Objects.requireNonNull(program, "program");
        args = List.copyOf(args);
        Objects.requireNonNull(workspace, "workspace");
        cwd = cwd == null ? "" : cwd;
        protect = List.copyOf(protect);
        env = Map.copyOf(env);
        Objects.requireNonNull(limits, "limits");
        Objects.requireNonNull(network, "network");
        Objects.requireNonNull(name, "name");
    }

    /** A request's {@code toString} names no environment value: a secret may be one. */
    @Override
    public String toString() {
        return "SandboxRequest[" + program + " " + args + " in " + cwd + ", env " + env.keySet() + ", " + network.mode() + "]";
    }

    /** The limits of one run. Null means not set (a backend that cannot enforce one refuses it). */
    public record SandboxLimits(Duration timeout, long maxOutput, Long memoryBytes, Integer pids, Long diskBytes) {
        /** Checks the limits that are always set. */
        public SandboxLimits {
            Objects.requireNonNull(timeout, "timeout");
            if (maxOutput <= 0) throw new IllegalArgumentException("maxOutput must be above zero");
        }

        /** The same limits with a shorter time, as an agent may ask for. */
        public SandboxLimits withTimeout(Duration shorter) {
            return new SandboxLimits(shorter, maxOutput, memoryBytes, pids, diskBytes);
        }
    }

    /** What the program may reach: nothing, the listed hosts (through a proxy), or anything. */
    public record SandboxNetwork(Mode mode, EgressProxy.Hosts hosts) {
        /** The three values of {@code network:}. */
        public enum Mode {
            NONE,
            ALLOW,
            OPEN
        }

        /** Checks that a list goes with {@code allow} and only with it. */
        public SandboxNetwork {
            Objects.requireNonNull(mode, "mode");
            if ((mode == Mode.ALLOW) != (hosts != null))
                throw new IllegalArgumentException("hosts go with network: allow, and only with it");
        }

        public static SandboxNetwork none() {
            return new SandboxNetwork(Mode.NONE, null);
        }
    }
}
