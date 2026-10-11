package io.github.llm4j.loom.inspect;

import io.github.llm4j.loom.observe.ObserveOptions;
import io.github.llm4j.loom.observe.ObserveProvider;
import java.io.PrintStream;

/** The loom-inspect add-on, found by {@code weave --observe} through {@link java.util.ServiceLoader}. */
public final class InspectObserveProvider implements ObserveProvider {

    @Override
    public String name() {
        return "loom-inspect";
    }

    @Override
    public Session open(ObserveOptions options, PrintStream out) {
        return new InspectSession(options, out);
    }

    @Override
    public long flush(ObserveOptions options, io.github.llm4j.secret.SecretStore secrets, PrintStream out) {
        if (options.fabric() == null) throw new IllegalArgumentException("weave observe flush needs --observe-fabric");
        String key;
        try {
            key = secrets == null ? null : secrets.resolve(options.fabric().keySecret());
        } catch (RuntimeException e) {
            key = null;
        }
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("the client key is not in the secret store: pass --secrets, and set it with 'weave secrets set " + options.fabric().keySecret() + "'");
        }
        var f = options.fabric();
        javax.net.ssl.SSLContext tls = "https".equalsIgnoreCase(f.url().getScheme()) ? io.github.llm4j.loom.inspect.tls.Tls.clientContext(f.ca(), f.caOnly(), f.pin()) : null;
        java.time.Clock clock = java.time.Clock.systemUTC();
        return io.github.llm4j.loom.inspect.emit.OutboxTools.flush(options.name(), root(options), new io.github.llm4j.loom.inspect.emit.HttpTransport(f.url(), tls, options.name(), key, clock),
                java.time.Duration.ofSeconds(60), clock, out, io.github.llm4j.loom.inspect.emit.ServerLink.Timing.normal());
    }

    @Override
    public long purge(String name, java.nio.file.Path outbox, PrintStream out) {
        return io.github.llm4j.loom.inspect.emit.OutboxTools.purge(name, outbox != null ? outbox : java.nio.file.Path.of(io.github.llm4j.loom.inspect.emit.Outbound.DEFAULT_OUTBOX), java.time.Clock.systemUTC(), out);
    }

    private static java.nio.file.Path root(ObserveOptions options) {
        java.nio.file.Path dir = options.fabric() != null ? options.fabric().outbox() : options.otlp() != null ? options.otlp().outbox() : null;
        return dir != null ? dir : java.nio.file.Path.of(io.github.llm4j.loom.inspect.emit.Outbound.DEFAULT_OUTBOX);
    }

    @Override
    public int checkRules(io.github.llm4j.loom.ast.LoomScript script, java.nio.file.Path rulesFile, PrintStream out) {
        var parsed = io.github.llm4j.loom.inspect.rules.RulesFile.read(rulesFile);
        var problems = io.github.llm4j.loom.inspect.rules.RulesCheck.check(script, parsed);
        problems.forEach(p -> out.println(rulesFile.getFileName() + ": " + p));
        long errors = problems.stream().filter(io.github.llm4j.loom.inspect.rules.RulesFile.Problem::error).count();
        if (problems.isEmpty()) out.println(rulesFile.getFileName() + ": " + parsed.rules().size() + " block(s), all fine");
        return (int) errors;
    }
}
