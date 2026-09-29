package io.github.llm4j.loom.depth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.agent.prompt.PromptRegistry;
import io.github.llm4j.agent.prompt.PromptTemplate;
import io.github.llm4j.loom.execution.LoomLoadException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Verification plan V6 (remote skills and discovery) and V7 (personas and templates). */
class SkillsAndPersonasTest {

    @TempDir
    Path dir;

    MockWebServer web;

    @BeforeEach
    void start() throws Exception {
        web = new MockWebServer();
        web.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest r) {
                return switch (r.getPath()) {
                    case "/skills/refunds.md" -> new MockResponse().setBody("# Refunds\nAlways offer store credit first.");
                    case "/registry/search?q=tone" -> new MockResponse().setBody(
                            "{\"skills\": [{\"id\": \"brand-voice\", \"name\": \"Brand voice\", \"description\": \"How we sound\"}]}");
                    case "/registry/brand-voice" -> new MockResponse().setBody(
                            "{\"name\": \"Brand voice\", \"content\": \"Warm, brief, no jargon.\"}");
                    default -> new MockResponse().setResponseCode(404);
                };
            }
        });
        web.start();
    }

    @AfterEach
    void stop() throws Exception {
        web.shutdown();
    }

    String url(String path) {
        return "http://localhost:" + web.getPort() + path;
    }

    @Test
    void v6_1_remoteSkillsAreLoadedIntoThePrompt() {
        Harness h = new Harness(dir);
        h.ready("""
                agent Support { model: "m" skills: ["%s"] }
                workflow Main() { delegate "help" to Support -> out }
                """.formatted(url("/skills/refunds.md"))).executeWorkflow("Main", Map.of());
        assertThat(h.requests.get(0).getMessages().get(0).getContent()).contains("Always offer store credit first.");
    }

    @Test
    void v6_1_unreachableOrInsecureSkillsFailTheLoad() {
        assertThatThrownBy(() -> new Harness(dir).ready("agent A { model: \"m\" skills: [\"" + url("/skills/missing.md") + "\"] }"))
                .isInstanceOf(LoomLoadException.class).hasMessageContaining("HTTP 404");
        assertThatThrownBy(() -> new Harness(dir).ready("agent A { model: \"m\" skills: [\"http://example.com/x.md\"] }"))
                .hasMessageContaining("remote skills must use https://");
    }

    @Test
    void v6_2_aSkillRegistryToolSearchesAndReads() {
        Harness h = new Harness(dir).answers(
                Harness.call("Skills", "{\"action\": \"search\", \"query\": \"tone\"}"),
                Harness.call("Skills", "{\"action\": \"read\", \"skillId\": \"brand-voice\"}"),
                Harness.done("done"));
        h.env.put("SKILLS_KEY", "k-123");
        h.ready("""
                tool Skills { use: skill_registry  url: "%s"  api_key: env.SKILLS_KEY }
                agent Writer { model: "m" tools: [Skills] }
                workflow Main() { delegate "write" to Writer -> out }
                """.formatted(url("/registry"))).executeWorkflow("Main", Map.of());
        assertThat(h.task(1)).contains("ID: brand-voice").contains("How we sound");
        assertThat(h.task(2)).contains("Warm, brief, no jargon.");
        assertThat(h.requests.get(0).getMessages().get(0).getContent()).contains("Skills");
    }

    @Test
    void v7_1_aScriptPersonaComesBeforeTheSystemPrompt() {
        Harness h = new Harness(dir);
        h.ready("""
                persona Mentor {
                    role: "senior engineer who mentors juniors"
                    expertise: "Java, testing"
                    tone: "patient"
                    description: "Explains the why."
                    constraints: ["Never write the code for them", "Ask one question at a time"]
                }
                agent Coach { model: "m" persona: Mentor system: "Review the student's pull request." }
                workflow Main() { delegate "review" to Coach -> out }
                """).executeWorkflow("Main", Map.of());
        String system = h.requests.get(0).getMessages().get(0).getContent();
        assertThat(system).contains("You are Mentor, a senior engineer who mentors juniors.")
                .contains("Your expertise: Java, testing").contains("Communication style: patient")
                .contains("Never write the code for them").contains("Review the student's pull request.");
        assertThat(system.indexOf("You are Mentor")).isLessThan(system.indexOf("Review the student's"));
    }

    @Test
    void v7_2_libraryPersonasStillWorkAndUnknownOnesFail() {
        Harness h = new Harness(dir);
        h.ready("""
                agent A { model: "m" persona: "technicalAnalyst" }
                workflow Main() { delegate "x" to A -> out }
                """).executeWorkflow("Main", Map.of());
        assertThat(h.requests.get(0).getMessages().get(0).getContent()).contains("You are");
        assertThatThrownBy(() -> new Harness(dir).ready("agent A { model: \"m\" persona: Ghost }"))
                .hasMessageContaining("line 1: agent A: persona Ghost is not defined").hasMessageContaining("technicalAnalyst");
        assertThatThrownBy(() -> new Harness(dir).ready("persona P { tone: \"x\" }"))
                .hasMessageContaining("persona P needs role:");
        assertThatThrownBy(() -> new Harness(dir).ready("persona P { role: \"x\" mood: \"y\" }"))
                .hasMessageContaining("unknown persona field mood");
    }

    @Test
    void v7_3_templatesNeedARegistryAndAKnownId() {
        assertThatThrownBy(() -> new Harness(dir).ready("agent A { model: \"m\" system_template: \"support-v1\" }"))
                .hasMessageContaining("system_template support-v1 needs a prompt registry");
        PromptRegistry registry = new PromptRegistry() {
            @Override
            public Optional<PromptTemplate> get(String id) {
                return id.equals("support-v1") ? Optional.of(new PromptTemplate(id, "1", "You are the support desk.")) : Optional.empty();
            }

            @Override
            public Optional<PromptTemplate> get(String id, String version) {
                return get(id);
            }

            @Override
            public void reload() { }
        };
        assertThatThrownBy(() -> new Harness(dir).executor("agent A { model: \"m\" system_template: \"nope\" }",
                e -> e.setPromptRegistry(registry)).initialize())
                .hasMessageContaining("system_template nope is not in the prompt registry");
        Harness h = new Harness(dir);
        var e = h.executor("""
                agent A { model: "m" system_template: "support-v1" }
                workflow Main() { delegate "x" to A -> out }
                """, x -> x.setPromptRegistry(registry));
        e.initialize();
        e.executeWorkflow("Main", Map.of());
        assertThat(h.requests.get(0).getMessages().get(0).getContent()).contains("You are the support desk.");
    }
}
