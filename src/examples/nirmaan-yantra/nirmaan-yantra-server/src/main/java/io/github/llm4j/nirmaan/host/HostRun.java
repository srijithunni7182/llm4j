package io.github.llm4j.nirmaan.host;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.execution.HarnessExecutor;
import io.github.llm4j.loom.execution.LoomLoader;
import io.github.llm4j.loom.execution.RunControl;
import io.github.llm4j.loom.execution.ToolRegistry;
import io.github.llm4j.loom.execution.TraceEvent;
import io.github.llm4j.loom.prompt.PromptSettings;
import io.github.llm4j.loom.prompt.PromptSupport;
import io.github.llm4j.loom.runtime.FileRunJournal;
import io.github.llm4j.loom.runtime.HumanInterface;
import io.github.llm4j.loom.runtime.RunStopped;
import io.github.llm4j.nirmaan.tasks.Sandbox;
import io.github.llm4j.secret.SecretStore;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * One run of the factory, as a screen sees it: a status, a stream of events (replayed to anyone who connects late), the question a
 * person is being asked, and the spend so far. It owns the {@link HarnessExecutor} and the thread that runs it; it decides nothing the
 * workflow decides.
 */
public final class HostRun {

    /** A destination for events: returns false once it can no longer be written to (the browser went away). */
    public interface Sink {
        boolean send(String event, String data);
    }

    /** A question waiting for a person. */
    public record Question(int id, String text, String kind, List<String> choices) {}

    public enum Answer { ACCEPTED, NO_QUESTION, NOT_ONE_OF_THE_CHOICES }

    private static final ObjectMapper JSON = new ObjectMapper();

    private final String id;
    private final String idea;
    private final FactoryHost host;
    private final FactoryConfig config;
    private final List<String[]> history = new ArrayList<>();
    private final List<Sink> sinks = new CopyOnWriteArrayList<>();
    private final AtomicInteger questionIds = new AtomicInteger();
    private volatile String status = "starting";
    private volatile String error;
    private volatile Question question;
    private volatile CompletableFuture<String> reply;
    private volatile HarnessExecutor executor;
    private volatile boolean stopRequested;

    HostRun(String id, String idea, FactoryHost host, FactoryConfig config) {
        this.id = id;
        this.idea = idea;
        this.host = host;
        this.config = config;
    }

    public String id() { return id; }

    public String idea() { return idea; }

    public String status() { return status; }

    public boolean active() { return status.equals("starting") || status.equals("running") || status.equals("waiting"); }

    void start() {
        Thread thread = new Thread(this::run, "nirmaan-run-" + id);
        thread.setDaemon(true);
        thread.start();
    }

    // ---- the run ------------------------------------------------------------------------------------------------------

    private void run() {
        HarnessExecutor ex = null;
        try {
            Path scriptPath = config.script();
            LoomScript script = new LoomLoader().load(scriptPath.toString());
            SecretStore secrets = host.secrets();
            ex = new HarnessExecutor(script, new ToolRegistry(), host.models().factory(secrets));
            ex.setSecretStore(secrets);
            ex.setBaseDir(scriptPath.getParent());
            Function<String, String> env = host.models().envLookup();
            if (env != null) ex.setEnvLookup(env);
            ex.setPromptCatalog(PromptSupport.catalog(script, scriptPath, PromptSettings.NONE));
            ex.setPriceTable(io.github.llm4j.budget.PriceTable.load(config.prices()));
            ex.setJournal(new FileRunJournal(Sandbox.meta(id).resolve("journal.json")));
            ex.setHumanInterface(new HumanInterface() {
                @Override public String promptHuman(String message) { return ask(message, Hints.none()); }

                @Override public String promptHuman(String stepId, String message, Hints hints) { return ask(message, hints); }
            });
            ex.addTraceListener(this::onTrace);
            host.attachObserver(ex, scriptPath);
            executor = ex;
            ex.initialize();
            setStatus("running");
            emit("log", "Starting: " + idea);
            ex.executeWorkflow("Main", Map.of("idea_text", idea, "project_id", id));
            finish("done", null);
        } catch (RunStopped stopped) {
            finish("stopped", "Stopped at " + stopped.point());
        } catch (io.github.llm4j.loom.runtime.RunHalted halted) {
            finish("halted", halted.reason()); // the workflow's own decision to stop, with its reason
        } catch (Throwable t) {
            finish(stopRequested ? "stopped" : "failed", friendly(t));
        } finally {
            if (ex != null) {
                try {
                    ex.shutdown();
                } catch (RuntimeException ignored) {
                    // nothing more to do for a run that has already ended
                }
            }
            host.release(this);
        }
    }

    private void onTrace(TraceEvent e) {
        for (UiEvents.Out out : UiEvents.convert(e)) emit(out.event(), out.data());
        if (e.type().equals(TraceEvent.DELEGATE_END)) emit("spend", json(spend()));
    }

    private void finish(String finalStatus, String message) {
        this.error = message;
        setStatus(finalStatus);
        if (message != null && (finalStatus.equals("failed") || finalStatus.equals("halted"))) emit("log", "The run stopped: " + message);
        emit("log", switch (finalStatus) {
            case "done" -> "Project Ready.";
            case "stopped" -> "Stopped.";
            case "halted" -> "Stopped by the workflow. What was built so far is in the artifacts.";
            default -> "Stopped before the project was finished. What was built so far is in the artifacts.";
        });
        emit("spend", json(spend()));
    }

    /** The reason a run ended, in words: the innermost message, without the "Task X failed:" wrapper. */
    static String friendly(Throwable t) {
        Throwable root = t;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        String message = root.getMessage() != null ? root.getMessage() : t.getMessage();
        if (message == null) message = t.getClass().getSimpleName();
        String lead = "failed: ";
        int at = message.indexOf(lead);
        if (message.startsWith("Task ") && at > 0) message = message.substring(at + lead.length());
        // An agent that answered in the wrong format leaves the field its answer should have had empty. Say that, not the plumbing.
        java.util.regex.Matcher missing = java.util.regex.Pattern.compile("argument (\\w+) refers to (\\w+)\\.(\\w+), which has no value").matcher(message);
        if (missing.find()) {
            return "An agent's answer could not be read: it was not valid JSON, so the field '" + missing.group(3) + "' was missing (" + missing.group(2)
                    + "). Nothing was lost but that step; start the idea again. (" + message + ")";
        }
        return message;
    }

    // ---- questions ----------------------------------------------------------------------------------------------------

    private String ask(String message, HumanInterface.Hints hints) {
        List<String> choices = !hints.choices().isEmpty() ? hints.choices()
                : hints.kind() == HumanInterface.Hints.Kind.APPROVAL || message.toLowerCase().contains("yes or no") ? List.of("yes", "no") : List.of();
        Question q = new Question(questionIds.incrementAndGet(), message, hints.kind().name().toLowerCase(), choices);
        CompletableFuture<String> waiting = new CompletableFuture<>();
        reply = waiting;
        question = q;
        setStatus("waiting");
        emit("question", json(Map.of("id", q.id(), "text", q.text(), "kind", q.kind(), "choices", q.choices())));
        try {
            String answer = waiting.get();
            emit("log", "You: " + answer);
            return answer;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("the run was interrupted while waiting for an answer");
        } catch (java.util.concurrent.ExecutionException e) {
            throw new IllegalStateException(e.getCause());
        } finally {
            question = null;
            if (status.equals("waiting")) setStatus("running");
        }
    }

    /** A person's answer to the question being asked. When the question has choices, only those are accepted. */
    public synchronized Answer answer(String text) {
        Question q = question;
        CompletableFuture<String> waiting = reply;
        if (q == null || waiting == null || waiting.isDone()) return Answer.NO_QUESTION;
        String answer = text == null ? "" : text.strip();
        if (!q.choices().isEmpty() && !q.choices().contains(answer)) return Answer.NOT_ONE_OF_THE_CHOICES;
        waiting.complete(answer);
        return Answer.ACCEPTED;
    }

    /** Stops the run at its next step; a question being asked is answered "no". */
    public void stop() {
        stopRequested = true;
        HarnessExecutor ex = executor;
        if (ex != null) ex.control().request(RunControl.Action.STOP, "ui");
        CompletableFuture<String> waiting = reply;
        if (question != null && waiting != null) waiting.complete("no");
    }

    // ---- events -------------------------------------------------------------------------------------------------------

    private void setStatus(String next) {
        this.status = next;
        emit("status", json(Map.of("status", next, "error", error == null ? "" : error)));
    }

    void emit(String event, String data) {
        synchronized (history) {
            history.add(new String[] {event, data});
            sinks.removeIf(sink -> !sink.send(event, data));
        }
    }

    /** Sends everything so far, then everything from now on. */
    public void subscribe(Sink sink) {
        synchronized (history) {
            for (String[] past : history) if (!sink.send(past[0], past[1])) return;
            sinks.add(sink);
        }
    }

    public Map<String, Object> state() {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("projectId", id);
        state.put("idea", idea);
        state.put("status", status);
        state.put("error", error == null ? "" : error);
        Question q = question;
        state.put("question", q == null ? null : Map.of("id", q.id(), "text", q.text(), "kind", q.kind(), "choices", q.choices()));
        state.put("spend", spend());
        return state;
    }

    private Map<String, Object> spend() {
        HarnessExecutor ex = executor;
        Map<String, Object> spend = new LinkedHashMap<>();
        if (ex == null) {
            spend.put("calls", 0);
            spend.put("tokens", 0);
            spend.put("cost", "0");
            return spend;
        }
        var total = ex.spend().total();
        spend.put("calls", total.calls());
        spend.put("tokens", total.tokens());
        spend.put("cost", total.cost() == null ? "0" : total.cost().toPlainString());
        return spend;
    }

    private static String json(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
