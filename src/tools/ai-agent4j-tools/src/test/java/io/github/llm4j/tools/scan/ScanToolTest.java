package io.github.llm4j.tools.scan;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.llm4j.agent.task.TaskEffect;
import io.github.llm4j.agent.tool.ToolProfile;
import io.github.llm4j.tools.ScanKind;
import io.github.llm4j.tools.support.RecordingEffects;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Phase 35, D14 and G63: {@code scan} reports a rule and a place for each secret or piece of
 * personal data it finds, and never the match itself, anywhere.
 */
class ScanToolTest {

    static final ObjectMapper JSON = new ObjectMapper();

    // Obviously fake values, made at run time so no scanner flags this file.
    static final String AWS = "AKIA" + "TESTFAKE0000EXAMPLE".substring(0, 16);
    static final String GITHUB = "ghp_" + "x".repeat(36);
    static final String CANARY = "sk-test-" + "CANARYcanary0123456789abcdef";
    static final String KEY_HEADER = "-----BEGIN RSA " + "PRIVATE KEY-----";
    // A token of the right shape, made at run time so the source holds no token: a fake header and
    // subject, and a signature that is only the letter x.
    static final String JWT =
            base64Url("{\"alg\":\"HS256\"}")
                    + "."
                    + base64Url("{\"sub\":\"fake\"}")
                    + "."
                    + "x".repeat(43);

    @TempDir Path dir;
    RecordingEffects effects = new RecordingEffects();

    JsonNode call(Map<String, String> options, Object... args) throws Exception {
        Map<String, Object> a = new HashMap<>();
        for (int i = 0; i < args.length; i += 2) a.put((String) args[i], args[i + 1]);
        String out = new ScanKind().create("Scan", options, dir, effects).execute(a);
        return out.startsWith("Error:") ? JSON.getNodeFactory().textNode(out) : JSON.readTree(out);
    }

    JsonNode call(Object... args) throws Exception {
        return call(Map.of(), args);
    }

    static List<String> rules(JsonNode r) {
        return r.get("findings").findValuesAsText("rule");
    }

    // ---- the kind

    @Test
    void withoutARootItIsPureAndWithOneItReadsFiles() {
        ToolProfile pure = new ScanKind().profile(Map.of());
        assertThat(pure.untrusted() || pure.privateData() || pure.outward()).isFalse();
        ToolProfile reads = new ScanKind().profile(Map.of("root", "out"));
        assertThat(reads.privateData()).isTrue();
        assertThat(reads.effect()).isEqualTo(TaskEffect.READS);
        assertThat(pure.note()).contains("never the match").contains("net");
        assertThat(new ScanKind().uncheckedResult(Map.of())).containsExactly("findings");
    }

    @Test
    void itsOptionsAreChecked() {
        assertThat(new ScanKind().check(Map.of("rules", "secrets, voices"), dir)).contains("rules");
        assertThat(new ScanKind().check(Map.of("pii", "email, dna"), dir)).contains("pii");
        assertThat(new ScanKind().check(Map.of("max_findings", "0"), dir)).contains("max_findings");
        assertThat(new ScanKind().check(Map.of("rules", "pii", "pii", "ip_address, url"), dir))
                .isNull();
    }

    // ---- what it finds

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "aws_access_key | key id AWS",
                "github_token | token GITHUB",
                "private_key | KEY_HEADER",
                "assigned_secret | password = hunter2hunter2",
                "jwt | JWT"
            })
    void knownSecretShapesAreFound(String rule, String text) throws Exception {
        String t = text.replace("AWS", AWS).replace("GITHUB", GITHUB).replace("KEY_HEADER", KEY_HEADER).replace("JWT", JWT);
        assertThat(rules(call("text", t))).contains(rule);
    }

    @Test
    void personalDataIsFoundThroughThePiiDetector() throws Exception {
        JsonNode r = call("text", "write to jane.doe@example.com or call 555-123-4567");
        assertThat(rules(r)).contains("pii_email", "pii_phone");
    }

    @Test
    void aLongRandomStringIsFoundButAHashIsNot() throws Exception {
        assertThat(rules(call("text", "token Zx8q7Lm2Pw9Rt4Yv1Nb6Hc3Kd0Fg5Js8Qa2Wx7Ec")))
                .contains("high_entropy");
        assertThat(rules(call("text", "commit 3f786850e387550fdab836ed7e6dc881de23001b")))
                .doesNotContain("high_entropy");
        assertThat(rules(call("text", "sha256 " + "ab".repeat(32)))).isEmpty();
    }

    @Test
    void cleanTextFindsNothingAndSaysItIsANet() throws Exception {
        JsonNode r = call("text", "Quarterly revenue grew by 4% in the north region.");
        assertThat(r.get("count").asInt()).isZero();
        assertThat(r.get("rules_version").asText()).isEqualTo("scan-1");
        assertThat(r.get("note").asText()).contains("nothing the rules know");
    }

    @Test
    void aFindingIsARuleAndAPlace() throws Exception {
        JsonNode r = call("text", "first line\nthe key is " + AWS + " ok");
        JsonNode f = r.get("findings").get(0);
        assertThat(f.get("rule").asText()).isEqualTo("aws_access_key");
        assertThat(f.get("line").asInt()).isEqualTo(2);
        assertThat(f.get("start").asInt()).isEqualTo(12);
        assertThat(f.get("end").asInt()).isEqualTo(12 + AWS.length());
    }

    @Test
    void onlyTheChosenRulesRun() throws Exception {
        String text = "mail jane@example.com key " + AWS;
        assertThat(rules(call(Map.of("rules", "secrets"), "text", text)))
                .containsExactly("aws_access_key");
        assertThat(rules(call(Map.of("rules", "pii"), "text", text))).containsExactly("pii_email");
        assertThat(
                        rules(
                                call(
                                        Map.of("rules", "pii", "pii", "ip_address"),
                                        "text",
                                        "host 10.0.0.1 jane@example.com")))
                .containsExactly("pii_ip_address");
    }

    // ---- never the match (G63)

    @Test
    void aSecretItFindsAppearsNowhere() throws Exception {
        Files.createDirectories(dir.resolve("out"));
        Files.writeString(dir.resolve("out/notes.txt"), "config\napi_key = " + CANARY + "\n");
        String fromText = call("text", "the key: " + CANARY).toString();
        String fromFile = call(Map.of("root", "out"), "path", "notes.txt").toString();
        assertThat(fromText).contains("\"rule\"").doesNotContain("CANARY");
        assertThat(fromFile).contains("notes.txt").doesNotContain("CANARY");
        assertThat(effects.everything()).doesNotContain("CANARY");
    }

    @Test
    void anErrorNeverEchoesTheInput() throws Exception {
        JsonNode r = call(Map.of("max_bytes", "1k"), "text", CANARY + "x".repeat(2000));
        assertThat(r.asText())
                .startsWith("Error:")
                .contains("larger than")
                .doesNotContain("CANARY");
        assertThat(effects.everything()).doesNotContain("CANARY");
    }

    // ---- files

    @Test
    void aDirectoryIsScannedFileByFileWithinItsRoot() throws Exception {
        Files.createDirectories(dir.resolve("out/sub"));
        Files.writeString(dir.resolve("out/a.txt"), "nothing here");
        Files.writeString(dir.resolve("out/sub/b.env"), "TOKEN=" + GITHUB);
        Files.write(dir.resolve("out/c.bin"), new byte[] {0, 1, 2, 'A', 'K', 'I', 'A'});
        JsonNode r = call(Map.of("root", "out"), "path", ".");
        assertThat(r.get("files").asInt()).isEqualTo(2);
        assertThat(r.get("skipped").asInt()).isEqualTo(1);
        assertThat(r.get("findings").get(0).get("file").asText()).isEqualTo("sub/b.env");
        assertThat(rules(r)).contains("github_token");
    }

    @Test
    void aLinkOutOfTheRootIsNotFollowed() throws Exception {
        Files.createDirectories(dir.resolve("out"));
        Files.writeString(dir.resolve("outside.txt"), "TOKEN=" + GITHUB);
        Files.createSymbolicLink(dir.resolve("out/link.txt"), dir.resolve("outside.txt"));
        JsonNode r = call(Map.of("root", "out"), "path", ".");
        assertThat(r.get("count").asInt()).isZero();
        assertThat(call(Map.of("root", "out"), "path", "link.txt").asText())
                .startsWith("Error:")
                .contains("outside");
        assertThat(call(Map.of("root", "out"), "path", "../outside.txt").asText())
                .startsWith("Error:");
        assertThat(call("path", "x.txt").asText()).startsWith("Error:").contains("root");
    }

    @Test
    void itIsBounded() throws Exception {
        String many = (AWS + "\n").repeat(30);
        JsonNode r = call(Map.of("max_findings", "5"), "text", many);
        assertThat(r.get("findings").size()).isEqualTo(5);
        assertThat(r.get("truncated").asBoolean()).isTrue();
        assertThat(r.get("count").asInt()).isEqualTo(30);

        Files.createDirectories(dir.resolve("out"));
        for (int i = 0; i < 6; i++) Files.writeString(dir.resolve("out/f" + i + ".txt"), "x");
        JsonNode capped = call(Map.of("root", "out", "max_files", "3"), "path", ".");
        assertThat(capped.get("files").asInt()).isEqualTo(3);
        assertThat(capped.get("truncated").asBoolean()).isTrue();

        assertThat(call("nothing", "x").asText()).startsWith("Error:").contains("text");
    }

    private static String base64Url(String json) {
        return java.util.Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
