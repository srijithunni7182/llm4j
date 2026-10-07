package web;

import io.github.llm4j.loom.execution.HarnessExecutor;
import io.github.llm4j.loom.execution.LLMClientFactory;
import io.github.llm4j.loom.execution.LoomLoader;
import io.github.llm4j.loom.execution.TraceEvent;
import io.github.llm4j.loom.prompt.PromptSettings;
import io.github.llm4j.loom.prompt.PromptSupport;
import io.github.llm4j.loom.runtime.HumanInterface;
import io.github.llm4j.loom.execution.ToolRegistry;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * One run of main.loom on its own thread, as the web page sees it: a status, a transcript, a question waiting for a person, the spend so far and the result.
 * The page only shows these and sends the person's answer back; every decision stays in the script.
 */
public final class Session {

    private final Path script;
    private final LLMClientFactory models;
    private final List<String> transcript = new ArrayList<>();
    private volatile String status = "idle";           // idle, running, waiting, done, failed
    private volatile String question;                  // what a person is being asked, while status is waiting
    private volatile CompletableFuture<String> answer;
    private volatile String result = "";
    private volatile HarnessExecutor executor;

    public Session(Path script, LLMClientFactory models) {
        this.script = script;
        this.models = models;
    }

    /** Starts a run on its own thread. A run already going is not interrupted: the call is ignored. */
    public synchronized boolean start(String topic) {
        if (status.equals("running") || status.equals("waiting")) return false;
        transcript.clear();
        question = null;
        result = "";
        status = "running";
        Thread t = new Thread(() -> run(topic), "web-run");
        t.setDaemon(true);
        t.start();
        return true;
    }

    private void run(String topic) {
        try {
            var loaded = new LoomLoader().load(script.toAbsolutePath().toString());
            var exec = new HarnessExecutor(loaded, new ToolRegistry(), models);
            executor = exec;
            exec.setBaseDir(script.toAbsolutePath().getParent());
            exec.setPromptCatalog(PromptSupport.catalog(loaded, script, PromptSettings.NONE));
            exec.addTraceListener(this::onEvent);                 // before initialize()
            exec.setHumanInterface(new HumanInterface() {         // the person answers in the page: this thread waits, nothing else does
                @Override public String promptHuman(String message) {
                    CompletableFuture<String> f = new CompletableFuture<>();
                    answer = f;
                    question = message;
                    status = "waiting";
                    String reply = f.join();
                    question = null;
                    status = "running";
                    return reply;
                }
            });
            exec.initialize();
            exec.executeWorkflow("Main", Map.of("topic", topic));
            Object saved = exec.getContext().getAll().get("saved");
            result = lastNote();
            if (saved != null) transcript("saved: " + saved);
            exec.shutdown();
            status = "done";
        } catch (Exception e) {
            transcript("failed: " + e.getMessage());
            status = "failed";
        }
    }

    private void onEvent(TraceEvent e) {
        if (e.type().equals("note")) transcript("note: " + e.text());
        else if (e.type().equals("delegate_start")) transcript(e.agent() + " is working");
        else if (e.type().equals("delegate_end")) transcript(e.agent() + " answered");
    }

    private synchronized void transcript(String line) {
        transcript.add(line);
    }

    private synchronized String lastNote() {
        for (int i = transcript.size() - 1; i >= 0; i--) if (transcript.get(i).startsWith("note: ")) return transcript.get(i).substring(6);
        return "";
    }

    /** The person's answer to the question being asked ("yes" saves; anything else does not). False when nothing is being asked. */
    public boolean answer(String text) {
        CompletableFuture<String> f = answer;
        if (f == null || f.isDone() || !status.equals("waiting")) return false;
        f.complete(text);
        return true;
    }

    /** Everything the page shows, as plain values. Spend is what the models have cost so far: calls, tokens and, when prices are known, money. */
    public synchronized Map<String, Object> state() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", status);
        m.put("question", question);
        m.put("transcript", List.copyOf(transcript));
        m.put("result", result);
        HarnessExecutor e = executor;
        if (e != null) {
            var total = e.spend().total();
            m.put("calls", total.calls());
            m.put("tokens", total.tokens());
            m.put("cost", total.cost() == null ? null : total.cost().toPlainString());
        }
        return m;
    }

    public String status() {
        return status;
    }
}
