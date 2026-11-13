package io.github.llm4j.tools.scan;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.llm4j.tools.ScanKind;
import io.github.llm4j.tools.support.RecordingEffects;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The exact edges of {@code scan}: entropy, overlaps, order, places and sizes. */
class ScanEdgesTest {

    static final ObjectMapper JSON = new ObjectMapper();
    static final String AWS = ScanToolTest.AWS;

    @TempDir Path dir;

    JsonNode call(Map<String, String> options, Object... args) throws Exception {
        Map<String, Object> a = new HashMap<>();
        for (int i = 0; i < args.length; i += 2) a.put((String) args[i], args[i + 1]);
        String out = new ScanKind().create("Scan", options, dir, new RecordingEffects()).execute(a);
        return out.startsWith("Error:") ? JSON.getNodeFactory().textNode(out) : JSON.readTree(out);
    }

    JsonNode text(String t) throws Exception {
        return call(Map.of(), "text", t);
    }

    @Test
    void aMixedStringWithLittleVarietyIsNotRandom() throws Exception {
        assertThat(text("Aa1".repeat(12)).get("count").asInt()).isZero();
        assertThat(
                        text("x Zx8q7Lm2Pw9Rt4Yv1Nb6Hc3Kd0Fg5Js8Q y")
                                .get("findings")
                                .get(0)
                                .get("rule")
                                .asText())
                .isEqualTo("high_entropy");
        // one character short of the length the rule looks at
        assertThat(text("Zx8q7Lm2Pw9Rt4Yv1Nb6Hc3Kd0Fg5Js").get("count").asInt()).isZero();
        assertThat(text("Zx8q7Lm2Pw9Rt4Yv1Nb6Hc3Kd0Fg5Js8").get("count").asInt()).isEqualTo(1);
        assertThat(text("zx8q7lm2pw9rt4yv1nb6hc3kd0fg5js8qa").get("count").asInt()).isZero();
        assertThat(text("ZX8Q7LM2PW9RT4YV1NB6HC3KD0FG5JS8QA").get("count").asInt()).isZero();
        assertThat(text("Zxbq_Lmapwkrtcyvdnbehcfkdzfgojsiqa").get("count").asInt()).isZero();
    }

    @Test
    void findingsComeInTheOrderOfTheTextAndTouchingOnesAreBothKept() throws Exception {
        JsonNode r = text("mail jane@example.com then " + AWS);
        assertThat(r.get("findings").findValuesAsText("rule"))
                .containsExactly("pii_email", "aws_access_key");
        JsonNode two = text(AWS + " " + AWS);
        assertThat(two.get("count").asInt()).isEqualTo(2);
        assertThat(two.get("findings").get(1).get("start").asInt()).isEqualTo(AWS.length() + 2);
        JsonNode touching = text("-----BEGIN RSA PRIVATE KEY-----" + AWS);
        assertThat(touching.get("findings").findValuesAsText("rule"))
                .containsExactly("private_key", "aws_access_key");
        // a known shape is named rather than the general rule it also matches: one finding, not two
        JsonNode inside = text("password=" + AWS);
        assertThat(inside.get("findings").findValuesAsText("rule"))
                .containsExactly("aws_access_key");
    }

    @Test
    void placesCountLinesFromOneAndColumnsFromOne() throws Exception {
        JsonNode r = text("x\n" + AWS);
        assertThat(r.get("findings").get(0).get("line").asInt()).isEqualTo(2);
        assertThat(r.get("findings").get(0).get("start").asInt()).isEqualTo(1);
        JsonNode first = text(AWS);
        assertThat(first.get("findings").get(0).get("line").asInt()).isEqualTo(1);
        assertThat(first.get("findings").get(0).get("start").asInt()).isEqualTo(1);
        assertThat(first.get("findings").get(0).has("file")).isFalse();
        assertThat(first.has("files")).isFalse();
    }

    @Test
    void sizesAreBoundedExactly() throws Exception {
        String at = "a".repeat(1024);
        assertThat(call(Map.of("max_bytes", "1k"), "text", at).get("count").asInt()).isZero();
        assertThat(call(Map.of("max_bytes", "1k"), "text", at + "a").asText())
                .isEqualTo("Error: text is larger than 1024 bytes (max_bytes)");
        Files.createDirectories(dir.resolve("out"));
        Files.writeString(dir.resolve("out/big.txt"), "a".repeat(1025));
        Files.writeString(dir.resolve("out/ok.txt"), AWS);
        JsonNode r = call(Map.of("root", "out", "max_bytes", "1k"), "path", ".");
        assertThat(r.get("skipped").asInt()).isEqualTo(1);
        assertThat(r.get("files").asInt()).isEqualTo(1);
        JsonNode exact = call(Map.of("root", "out", "max_files", "2"), "path", ".");
        assertThat(exact.get("files").asInt()).isEqualTo(2);
        assertThat(exact.get("truncated").asBoolean()).isFalse();
        JsonNode findingsAt = call(Map.of("max_findings", "2"), "text", AWS + "\n" + AWS);
        assertThat(findingsAt.get("truncated").asBoolean()).isFalse();
    }

    @Test
    void aSingleFileIsScannedByItself() throws Exception {
        Files.createDirectories(dir.resolve("out"));
        Files.writeString(dir.resolve("out/key.txt"), "\n" + AWS);
        JsonNode r = call(Map.of("root", "out"), "path", "key.txt");
        assertThat(r.get("files").asInt()).isEqualTo(1);
        assertThat(r.get("findings").get(0).get("file").asText()).isEqualTo("key.txt");
        assertThat(r.get("findings").get(0).get("line").asInt()).isEqualTo(2);
        assertThat(r.get("note").asText()).contains("never shown");
    }

    @Test
    void theDescriptionOffersFilesOnlyWithARoot() throws Exception {
        assertThat(
                        new ScanKind()
                                .create("S", Map.of(), dir, new RecordingEffects())
                                .getDescription())
                .doesNotContain("path:");
        assertThat(
                        new ScanKind()
                                .create("S", Map.of("root", "out"), dir, new RecordingEffects())
                                .getDescription())
                .contains("or path: a file or directory");
        assertThat(call(Map.of(), "text", 5).asText()).isEqualTo("Error: text is required");
        assertThat(call(Map.of("root", "out"), "x", 1).asText())
                .isEqualTo("Error: text is required (or path: a file or directory)");
    }
}
