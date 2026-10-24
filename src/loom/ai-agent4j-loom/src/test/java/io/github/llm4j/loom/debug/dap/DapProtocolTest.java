package io.github.llm4j.loom.debug.dap;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.llm4j.loom.travel.TravelFixtures;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** R38 to R42 of spec 13: an editor debugging a workflow over the Debug Adapter Protocol. */
class DapProtocolTest {

    @TempDir Path dir;

    DapClient client;

    @AfterEach
    void close() throws Exception {
        if (client != null) client.close();
    }

    private Path script(String name) throws Exception {
        var c =
                TravelFixtures.CORPUS.stream()
                        .filter(x -> x.name().equals(name))
                        .findFirst()
                        .orElseThrow();
        Path file = dir.resolve(name.replace(' ', '-') + ".loom");
        Files.writeString(file, c.script());
        return file;
    }

    private void launch(String name, String inputKey, String inputValue) throws Exception {
        client = new DapClient();
        var init = client.request("initialize", client.args().put("adapterID", "loom"));
        assertThat(init.path("success").asBoolean()).isTrue();
        ObjectNode a = client.args();
        a.put("script", script(name).toString());
        a.putObject("input").put(inputKey, inputValue);
        var launched = client.request("launch", a);
        assertThat(launched.path("success").asBoolean()).as(launched.toString()).isTrue();
        client.request("configurationDone");
    }

    @Test
    void theWholeConversationOfAnEditorFromLaunchToTheEnd() throws Exception {
        launch("triage order status", "message", "where is my parcel?");
        assertThat(client.events("initialized")).hasSize(1);
        var stopped = client.next();
        assertThat(stopped.path("event").asText()).isEqualTo("stopped");
        assertThat(stopped.path("body").path("reason").asText()).isEqualTo("entry");
        var threads = client.request("threads");
        assertThat(threads.path("body").path("threads")).hasSize(1);
        var stack = client.request("stackTrace", client.args().put("threadId", 1));
        var top = stack.path("body").path("stackFrames").get(0);
        assertThat(top.path("name").asText()).isEqualTo("classify Intent");
        assertThat(top.path("line").asInt()).isEqualTo(8);
        assertThat(top.path("source").path("path").asText()).endsWith("triage-order-status.loom");
        var scopes = client.request("scopes", client.args().put("frameId", 1));
        int ref = scopes.path("body").path("scopes").get(0).path("variablesReference").asInt();
        var vars = client.request("variables", client.args().put("variablesReference", ref));
        assertThat(vars.path("body").path("variables").get(0).path("name").asText())
                .isEqualTo("message");
        assertThat(vars.path("body").path("variables").get(0).path("value").asText())
                .contains("where is my parcel?")
                .contains("(input)");

        client.request("next", client.args().put("threadId", 1));
        assertThat(client.next().path("event").asText()).isEqualTo("stopped");
        var vars2 = client.request("variables", client.args().put("variablesReference", ref));
        assertThat(vars2.path("body").toString())
                .contains("intent")
                .contains("line 8: intentclassify Intent");

        client.request("continue", client.args().put("threadId", 1));
        var end = client.next();
        // the run's own words, then the end
        assertThat(end.path("event").asText()).isEqualTo("output");
        assertThat(end.path("body").path("output").asText()).contains("The run completed");
        assertThat(client.next().path("event").asText()).isEqualTo("terminated");
        assertThat(client.request("disconnect").path("success").asBoolean()).isTrue();
    }

    @Test
    void lineBreakpointsAreVerifiedToTheNearestStatementAndStopTheRun() throws Exception {
        client = new DapClient();
        client.request("initialize");
        ObjectNode a = client.args();
        a.put("script", script("triage order status").toString());
        a.putObject("input").put("message", "m");
        a.put("stopOnEntry", false);
        client.request("launch", a);
        ObjectNode bp = client.args();
        bp.putObject("source").put("path", dir.resolve("triage-order-status.loom").toString());
        ArrayNode lines = bp.putArray("breakpoints");
        lines.addObject().put("line", 12); // no statement here: the next one is line 13
        lines.addObject().put("line", 999); // nothing at or after it
        var set = client.request("setBreakpoints", bp);
        JsonNode results = set.path("body").path("breakpoints");
        assertThat(results.get(0).path("verified").asBoolean()).isTrue();
        assertThat(results.get(0).path("line").asInt()).isEqualTo(13);
        assertThat(results.get(1).path("verified").asBoolean()).isFalse();
        assertThat(results.get(1).path("message").asText()).contains("no statement");
        client.request("configurationDone");
        var stopped = client.next();
        assertThat(stopped.path("event").asText()).isEqualTo("stopped");
        assertThat(stopped.path("body").path("reason").asText()).isEqualTo("breakpoint");
        var stack = client.request("stackTrace", client.args());
        assertThat(stack.path("body").path("stackFrames").get(0).path("line").asInt())
                .isEqualTo(13);
        // the cost so far is in the console (R42)
        assertThat(client.events("output"))
                .anyMatch(
                        e ->
                                e.path("body").path("output").asText().contains("model calls")
                                        && e.path("body")
                                                .path("output")
                                                .asText()
                                                .contains("tokens so far"));
    }

    @Test
    void aConditionalBreakpointAndAFunctionBreakpointOnAnAgent() throws Exception {
        client = new DapClient();
        client.request("initialize");
        ObjectNode a = client.args();
        a.put("script", script("triage order status").toString());
        a.putObject("input").put("message", "m");
        a.put("stopOnEntry", false);
        client.request("launch", a);
        ObjectNode bp = client.args();
        bp.putObject("source").put("path", dir.resolve("triage-order-status.loom").toString());
        bp.putArray("breakpoints")
                .addObject()
                .put("line", 15)
                .put("condition", "reply == \"nope\"");
        var never = client.request("setBreakpoints", bp);
        assertThat(never.path("body").path("breakpoints").get(0).path("verified").asBoolean())
                .isTrue();
        ObjectNode fn = client.args();
        fn.putArray("breakpoints").addObject().put("name", "Replier");
        var set = client.request("setFunctionBreakpoints", fn);
        assertThat(set.path("body").path("breakpoints").get(0).path("verified").asBoolean())
                .isTrue();
        client.request("configurationDone");
        var stopped = client.next();
        assertThat(stopped.path("body").path("reason").asText()).isEqualTo("breakpoint");
        var stack = client.request("stackTrace", client.args());
        assertThat(stack.path("body").path("stackFrames").get(0).path("name").asText())
                .isEqualTo("delegate Replier");
        // the conditional one on the return does not hold; the run goes on to its end
        client.request("continue", client.args());
        assertThat(client.next().path("body").path("output").asText())
                .contains("The run completed");
    }

    @Test
    void stepBackAndReverseContinueAreAdvertisedAndWork() throws Exception {
        client = new DapClient();
        var init = client.request("initialize");
        JsonNode caps = init.path("body");
        assertThat(caps.path("supportsStepBack").asBoolean()).isTrue();
        assertThat(caps.path("supportsConditionalBreakpoints").asBoolean()).isTrue();
        assertThat(caps.path("supportsSetVariable").asBoolean()).isTrue();
        assertThat(caps.path("supportsEvaluateForHovers").asBoolean()).isTrue();
        assertThat(caps.path("supportsFunctionBreakpoints").asBoolean()).isTrue();
        ObjectNode a = client.args();
        a.put("script", script("triage order status").toString());
        a.putObject("input").put("message", "m");
        client.request("launch", a);
        client.request("configurationDone");
        client.next(); // entry
        client.request("stepIn", client.args());
        client.next();
        client.request("stepIn", client.args());
        client.next();
        var stack = client.request("stackTrace", client.args());
        int line = stack.path("body").path("stackFrames").get(0).path("line").asInt();
        assertThat(line).isEqualTo(13);
        client.request("stepBack", client.args());
        client.next();
        var back = client.request("stackTrace", client.args());
        assertThat(back.path("body").path("stackFrames").get(0).path("line").asInt()).isEqualTo(9);
        client.request("reverseContinue", client.args());
        client.next();
        var first = client.request("stackTrace", client.args());
        assertThat(first.path("body").path("stackFrames").get(0).path("line").asInt()).isEqualTo(8);
    }

    @Test
    void setVariableAndEvaluateWork() throws Exception {
        launch("triage order status", "message", "hello");
        client.next();
        var set =
                client.request(
                        "setVariable",
                        client.args()
                                .put("variablesReference", 1)
                                .put("name", "message")
                                .put("value", "bye"));
        assertThat(set.path("body").path("value").asText()).isEqualTo("bye");
        assertThat(
                        client.request(
                                        "evaluate",
                                        client.args()
                                                .put("expression", "message")
                                                .put("context", "hover"))
                                .path("body")
                                .path("result")
                                .asText())
                .isEqualTo("bye");
        assertThat(
                        client.request(
                                        "evaluate",
                                        client.args().put("expression", "message == \"bye\""))
                                .path("body")
                                .path("result")
                                .asText())
                .isEqualTo("true");
        var bad = client.request("evaluate", client.args().put("expression", ""));
        assertThat(bad.path("success").asBoolean()).isFalse();
    }

    @Test
    void thingsItCannotDoAreRefusedWithAReasonAndTheSessionGoesOn() throws Exception {
        client = new DapClient();
        client.request("initialize");
        var unknown = client.request("attach");
        assertThat(unknown.path("success").asBoolean()).isFalse();
        assertThat(unknown.path("message").asText()).contains("not supported");
        var early = client.request("threads");
        assertThat(early.path("success").asBoolean()).isTrue();
        var stack = client.request("stackTrace", client.args());
        assertThat(stack.path("success").asBoolean()).isFalse();
        assertThat(stack.path("message").asText()).contains("not been launched");
        var missing = client.request("launch", client.args());
        assertThat(missing.path("message").asText()).contains("needs a \"script\"");
        var real =
                client.request(
                        "launch",
                        client.args()
                                .put("script", script("straight").toString())
                                .put("effects", "real"));
        assertThat(real.path("message").asText()).contains("simulated effects");
        // after all that, a proper launch still works
        var ok =
                client.request(
                        "launch", client.args().put("script", script("straight").toString()));
        assertThat(ok.path("success").asBoolean()).isTrue();
    }

    @Test
    void messagesThatAreNotProtocolDoNotStopIt() throws Exception {
        client = new DapClient();
        client.raw("{not json".getBytes(StandardCharsets.UTF_8));
        var init = client.request("initialize");
        assertThat(init.path("success").asBoolean()).isTrue();
        assertThat(client.events("output"))
                .anyMatch(e -> e.path("body").path("output").asText().contains("not JSON"));
    }

    @Test
    void whatTheRunHoldsCannotCarryControlCharactersToTheEditor() throws Exception {
        client = new DapClient();
        client.request("initialize");
        ObjectNode a = client.args();
        a.put("script", script("straight").toString());
        a.putObject("input").put("topic", "x\u001b[2Jy‮abc");
        client.request("launch", a);
        client.request("configurationDone");
        client.next();
        var vars = client.request("variables", client.args().put("variablesReference", 1));
        String shown = vars.path("body").path("variables").get(0).path("value").asText();
        assertThat(shown)
                .doesNotContain("\u001b")
                .doesNotContain("‮")
                .contains("\\u001b")
                .contains("\\u202e");
    }
}
