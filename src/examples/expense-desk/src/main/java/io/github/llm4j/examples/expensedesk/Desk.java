package io.github.llm4j.examples.expensedesk;

import io.github.llm4j.agent.task.TaskRegistry;
import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.execution.HarnessExecutor;
import io.github.llm4j.loom.execution.LLMClientFactory;
import io.github.llm4j.loom.execution.LoomLoader;
import io.github.llm4j.loom.execution.ToolRegistry;
import io.github.llm4j.loom.execution.TraceEvent;
import io.github.llm4j.loom.graph.GraphJson;
import io.github.llm4j.loom.graph.GraphService;
import io.github.llm4j.loom.host.AnswerResult;
import io.github.llm4j.loom.host.LoomHost;
import io.github.llm4j.loom.host.Persistence;
import io.github.llm4j.loom.host.RunRecord;
import io.github.llm4j.loom.host.RunRequest;
import io.github.llm4j.loom.security.SecurityAudit;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * The expense desk: takes claims, runs the {@code Claim} workflow for each on a {@link LoomHost},
 * and passes a person's decision to the run that is waiting for it. It owns no rule: the script
 * says what happens in what order, {@link ExpensePolicy} decides and {@link Reimburse} pays.
 */
public final class Desk implements AutoCloseable {

    /** The longest claim text the desk takes, in characters. */
    public static final int MAX_TEXT = 2000;

    /** The longest name of an employee or an approver, in characters. */
    public static final int MAX_NAME = 80;

    private static final String WORKFLOW = "Claim";
    private static final Duration SHUTDOWN_GRACE = Duration.ofSeconds(5);

    /** What the script is and what the offline checks say about it, read once when the desk opens. */
    public record Architecture(String script, String graphJson, String auditJson) {}

    private final LoomHost host;
    private final ClaimBook book;
    private final Ledger ledger = new Ledger();
    private final Clock clock;
    private final Architecture architecture;

    /**
     * Opens a desk.
     *
     * @param script the Loom script with the {@code Claim} workflow
     * @param dataDir where run journals and the claims are kept; made if it is not there
     * @param models the models the script's extractor and agent run on
     * @param clock the time claims and steps are stamped with
     */
    public Desk(Path script, Path dataDir, LLMClientFactory models, Clock clock) {
        Objects.requireNonNull(script, "script");
        Objects.requireNonNull(dataDir, "dataDir");
        Objects.requireNonNull(models, "models");
        this.clock = Objects.requireNonNull(clock, "clock");
        LoomScript loaded;
        String source;
        try {
            loaded = new LoomLoader().load(script.toString());
            source = Files.readString(script);
        } catch (IOException e) {
            throw new UncheckedIOException("the script " + script + " could not be read", e);
        }
        this.architecture =
                new Architecture(
                        source,
                        GraphJson.write(new GraphService().graph(script)),
                        SecurityAudit.audit(loaded, script.getFileName().toString(), Map.of()).json());
        this.book = new ClaimBook(dataDir.resolve("claims.json"));
        this.host =
                LoomHost.builder(loaded, new ToolRegistry(), models)
                        .persistence(Persistence.file(dataDir))
                        .scriptFile(script)
                        .clock(clock)
                        .taskRegistry(new TaskRegistry().register(new ExpensePolicy()).register(new Reimburse(ledger)))
                        .questionListener(this::asked)
                        .customizer(executor -> executor.addTraceListener(event -> happened(executor, event)))
                        .build();
        host.validate();
    }

    /** Takes a claim and starts its run; returns at once with the claim as it stands. */
    public Claim submit(String employee, String text) {
        String who = bounded(employee, "the employee's name", MAX_NAME);
        String what = bounded(text, "the claim", MAX_TEXT);
        // The desk makes the id: an id a user chose could replay another run's journal.
        String id = "claim-" + UUID.randomUUID().toString().substring(0, 8);
        Claim claim = Claim.submitted(id, who, what, clock.instant().toString());
        book.add(claim);
        host.start(RunRequest.of(WORKFLOW, Map.of("employee", who, "text", what)).withRunId(id).from("expense-desk"));
        return claim;
    }

    /**
     * Records a person's yes or no for a claim that is waiting, and lets its run carry on.
     *
     * @throws IllegalArgumentException when there is no such claim
     * @throws IllegalStateException when the claim is not waiting, or was already answered
     */
    public void decide(String id, boolean approve, String by) {
        String approver = bounded(by, "the approver's name", MAX_NAME);
        Claim claim = book.find(id).orElseThrow(() -> new IllegalArgumentException("there is no claim " + id));
        if (!claim.question().open() || !suspended(id)) {
            throw new IllegalStateException("claim " + id + " is not waiting for a decision");
        }
        AnswerResult result = host.answer(id, claim.question().stepId(), approve ? "yes" : "no", approver);
        if (!result.recorded()) throw new IllegalStateException(result.message());
        book.update(
                id,
                c -> c.withQuestion(Claim.Question.NONE)
                        .withStatus(Claim.Status.READING)
                        .withStep(step("person", approver + (approve ? " approved the payment" : " declined the payment"))));
    }

    /** Every claim, newest first. */
    public List<Claim> claims() {
        for (Claim claim : book.all()) {
            if (claim.status() == Claim.Status.READING && claim.question().open() && suspended(claim.id())) {
                book.update(claim.id(), c -> c.withStatus(Claim.Status.WAITING));
            }
        }
        return book.all();
    }

    /**
     * Whether the host has put the run down. A question is heard a moment before that, and an
     * answer is only taken once the run is suspended, so a claim is shown as waiting from then.
     */
    private boolean suspended(String id) {
        return host.run(id).map(RunRecord::status).filter(RunRecord.Status.SUSPENDED::equals).isPresent();
    }

    /** Every payment the ledger holds, oldest first. */
    public List<Ledger.Payment> payments() {
        return ledger.payments();
    }

    public Architecture architecture() {
        return architecture;
    }

    @Override
    public void close() {
        host.shutdown(SHUTDOWN_GRACE);
        host.close();
    }

    private void asked(String runId, String stepId, String prompt, List<String> choices) {
        book.update(
                runId,
                c -> c.withQuestion(new Claim.Question(stepId, prompt))
                        .withStep(step("desk", "Waiting for a person to approve the payment")));
    }

    private void happened(HarnessExecutor executor, TraceEvent event) {
        String id = executor.getRunId();
        if (id == null) return;
        switch (event.type()) {
            case TraceEvent.EXTRACT_END -> book.update(id, c -> c.withStep(step("model", "Read the claim's fields from its text")));
            case TraceEvent.TASK_END -> book.update(id, c -> seen(c, executor).withStep(step("code", event.text())));
            case TraceEvent.DELEGATE_END -> book.update(id, c -> c.withStep(step("model", event.agent() + " wrote the note to the employee")));
            case TraceEvent.HALT -> book.update(id, c -> c.withStatus(Claim.Status.FAILED).withReason(event.text()).withStep(step("desk", event.text())));
            case TraceEvent.RUN_END -> ended(id, executor, String.valueOf(event.data().get("status")));
            default -> { } // the desk shows the steps a claim's owner cares about, not every trace event
        }
    }

    private void ended(String id, HarnessExecutor executor, String status) {
        if (status.equals("completed")) {
            Map<String, Object> result = map(executor.getContext().getVariable("result"));
            book.update(
                    id,
                    c -> seen(c, executor)
                            .withStatus(Claim.Status.valueOf(String.valueOf(result.get("status"))))
                            .withNote(String.valueOf(result.getOrDefault("note", ""))));
        } else if (status.equals("failed")) {
            book.update(
                    id,
                    c -> c.withStatus(Claim.Status.FAILED)
                            .withReason(c.reason().isEmpty() ? "The run failed; its journal says why." : c.reason()));
        }
    }

    /** Copies what the run knows so far (the fields read, the refusal, the payment) onto the claim. */
    private static Claim seen(Claim claim, HarnessExecutor executor) {
        Map<String, Object> receipt = map(executor.getContext().getVariable("receipt"));
        Map<String, Object> verdict = map(executor.getContext().getVariable("verdict"));
        Map<String, Object> payment = map(executor.getContext().getVariable("payment"));
        Claim next = receipt.isEmpty() ? claim : claim.withFields(receipt);
        if (verdict.get("reason") != null) next = next.withReason(String.valueOf(verdict.get("reason")));
        if (payment.get("value") != null) next = next.withPayment(String.valueOf(payment.get("value")));
        return next;
    }

    private static Map<String, Object> map(Object value) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (value instanceof Map<?, ?> m) {
            m.forEach((k, v) -> { if (v != null) out.put(String.valueOf(k), v); });
        }
        return out;
    }

    private Claim.Step step(String actor, String text) {
        return new Claim.Step(clock.instant().toString(), actor, text);
    }

    private static String bounded(String value, String what, int max) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(what + " is empty");
        String clean = value.strip();
        if (clean.length() > max) {
            throw new IllegalArgumentException(what + " is longer than " + max + " characters");
        }
        return clean;
    }
}
