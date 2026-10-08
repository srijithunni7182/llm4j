package io.github.llm4j.toolcalling;

import static org.junit.jupiter.api.Assertions.*;

import io.github.llm4j.agent.AgentResult;
import io.github.llm4j.agent.ReActAgent;
import io.github.llm4j.toolcalling.Scripted.Recorder;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** TCF-*: the text protocol accepts what real models write, not only the exact fence it asks for. */
class TextProtocolToleranceTest {

    static final String FINAL = "{\"plan\": \"done\", \"final_answer\": \"42\"}";

    AgentResult run(String... replies) {
        return runWith(new Recorder("calc", "4"), replies);
    }

    AgentResult runWith(Recorder calc, String... replies) {
        Scripted model = Scripted.textModel(replies);
        return ReActAgent.builder().llmClient(model).addTool(calc).maxIterations(4).build().run("q");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "```json\n{\"plan\": \"done\", \"final_answer\": \"42\"}\n```",
            "```JSON\n{\"plan\": \"done\", \"final_answer\": \"42\"}\n```",
            "```Json\n{\"plan\": \"done\", \"final_answer\": \"42\"}\n```",
            "```json\r\n{\"plan\": \"done\", \"final_answer\": \"42\"}\r\n```",
            "```json   \n{\"plan\": \"done\", \"final_answer\": \"42\"}\n```",
            "``` json\n{\"plan\": \"done\", \"final_answer\": \"42\"}\n```",
            "```json {\"plan\": \"done\", \"final_answer\": \"42\"} ```",
            "```json\n{\"plan\": \"done\", \"final_answer\": \"42\"}```",
            "Here you go:\n```json\n{\"plan\": \"done\", \"final_answer\": \"42\"}\n```\nHope that helps.",
            "{\"plan\": \"done\", \"final_answer\": \"42\"}",
            "  \n{\"plan\": \"done\", \"final_answer\": \"42\"}\n  "
    })
    void tcf01_everyFenceVariantIsParsedAsTheProtocol(String reply) {
        AgentResult r = run(reply);
        assertEquals("42", r.getFinalAnswer(), reply);
        assertTrue(r.isProtocolFollowed(), "these follow the protocol: " + reply);
    }

    @Test
    void tcf01_anActionInAnyFenceVariantRunsTheTool() {
        Recorder calc = new Recorder("calc", "4");
        AgentResult r = runWith(calc,
                "```JSON\r\n{\"plan\": \"add\", \"action\": \"calc\", \"action_input\": {\"e\": \"2+2\"}}\r\n```",
                "{\"plan\": \"done\", \"final_answer\": \"4\"}");
        assertEquals("4", r.getFinalAnswer());
        assertEquals(Map.of("e", "2+2"), calc.seen.get(0));
        assertTrue(r.isProtocolFollowed());
    }

    @Test
    void tcf01_aBareJsonObjectThatIsNotTheProtocolIsStillNotTheProtocol() {
        AgentResult r = run("{\"name\": \"Asha\", \"age\": 30}");
        assertEquals("{\"name\": \"Asha\", \"age\": 30}", r.getFinalAnswer(), "unchanged: it is the model's answer, as text");
        assertFalse(r.isProtocolFollowed());
    }

    @Test
    void tcf02_aMultiLineActionInputSurvives() {
        Recorder calc = new Recorder("calc", "4");
        AgentResult r = runWith(calc,
                "Thought: I need to add\nAction: calc\nAction Input: {\n  \"a\": 1,\n  \"b\": [2, 3]\n}\nObservation: (to come)",
                "Final Answer: 4");
        assertEquals("4", r.getFinalAnswer());
        assertEquals(Map.of("a", 1, "b", List.of(2, 3)), calc.seen.get(0), "the whole JSON object, not its first line");
    }

    @Test
    void tcf02_crlfLineEndingsInTheLineFormat() {
        Recorder calc = new Recorder("calc", "4");
        AgentResult r = runWith(calc,
                "Thought: add\r\nAction: calc\r\nAction Input: {\r\n  \"a\": 1\r\n}\r\n",
                "Final Answer: done\r\nsecond line");
        assertEquals(Map.of("a", 1), calc.seen.get(0));
        assertEquals("done\r\nsecond line", r.getFinalAnswer());
    }

    @Test
    void tcf02_aSingleLineInputStillWorksAndAStopsAtTheNextProtocolLine() {
        Recorder calc = new Recorder("calc", "4");
        runWith(calc, "Action: calc\nAction Input: {\"a\": 1}\nThought: more\n", "Final Answer: x");
        assertEquals(Map.of("a", 1), calc.seen.get(0));
        Recorder text = new Recorder("calc", "4");
        runWith(text, "Action: calc\nAction Input: plain words here\nObservation: x", "Final Answer: x");
        assertEquals(Map.of("input", "plain words here"), text.seen.get(0), "a non-JSON input becomes the input argument");
    }

    @Test
    void tcf03_thingsThatParsedBeforeStillParse() {
        assertEquals("42", run("```json\n" + FINAL + "\n```").getFinalAnswer());
        assertEquals("plain", run("Final Answer: plain").getFinalAnswer());
        AgentResult nothing = run("no protocol at all");
        assertEquals("no protocol at all", nothing.getFinalAnswer());
        assertFalse(nothing.isProtocolFollowed());
    }
}
