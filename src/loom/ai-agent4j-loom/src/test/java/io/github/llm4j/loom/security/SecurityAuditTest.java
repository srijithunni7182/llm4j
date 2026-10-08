package io.github.llm4j.loom.security;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.execution.LoomLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The static security review finds what it says it finds, maps it to the OWASP Top 10 for LLM Applications, and stays quiet on a well-built script. */
class SecurityAuditTest {

    @TempDir
    Path dir;

    AuditReport audit(String source) throws Exception {
        Path f = dir.resolve("s" + System.nanoTime() + ".loom");
        Files.writeString(f, source);
        return SecurityAudit.audit(new LoomLoader().load(f.toString()), f.getFileName().toString());
    }

    static List<Finding> rule(AuditReport r, String id) {
        return r.findings().stream().filter(f -> f.rule().equals(id)).toList();
    }

    static final String WORKFLOW = "\nworkflow Main(q) {\n    delegate \"{q}\" to A -> r\n}\n";

    @Test
    void oneAgentWithUntrustedContentPrivateDataAndAnOpenWayOutIsTheHighestFinding() throws Exception {
        AuditReport r = audit("""
                tool Files { use: file  root: "data"  mode: read }
                tool Mail  { use: email  host: "smtp.example.com"  from: "a@example.com"  to: "me@example.com" }
                agent A { model: "m"  system: "s"  tools: [web_search, Files, Mail] }
                """ + WORKFLOW);

        Finding f = rule(r, "LA01").get(0);
        assertThat(f.severity()).isEqualTo(Severity.HIGH);
        assertThat(f.owasp()).contains(Owasp.LLM01, Owasp.LLM02, Owasp.LLM06);
        assertThat(f.risk()).contains("web_search").contains("Files").contains("Mail");
        assertThat(r.agents().get(0).trifecta()).isTrue();
        assertThat(r.failsAt(Severity.HIGH)).isTrue();
    }

    @Test
    void theSameAgentWithEveryWayOutApprovedIsMediumAndSplittingItClearsIt() throws Exception {
        String approved = """
                tool Files { use: file  root: "data"  mode: read }
                tool Mail  { use: email  host: "smtp.example.com"  from: "a@example.com"  to: "me@example.com" }
                agent A { model: "m"  system: "s"  tools: [web_search, Files, Mail]  approve: [Mail] }
                """ + WORKFLOW;
        assertThat(rule(audit(approved), "LA01")).singleElement().extracting(Finding::severity).isEqualTo(Severity.MEDIUM);

        AuditReport split = audit("""
                budget { tokens: 1000 }
                tool Files { use: file  root: "data"  mode: read }
                tool Mail  { use: email  host: "smtp.example.com"  from: "a@example.com"  to: "me@example.com" }
                agent A { model: "m"  system: "s"  tools: [web_search] }
                agent B { model: "m"  system: "s"  tools: [Files]  guard { pii: mask } }
                agent C { model: "m"  system: "s"  tools: [Mail]  approve: [Mail] }
                """ + WORKFLOW);
        assertThat(rule(split, "LA01")).isEmpty();
        assertThat(split.findings()).noneMatch(f -> f.severity().atLeast(Severity.MEDIUM));
        assertThat(split.agents()).noneMatch(SecurityAudit.AgentProfile::trifecta);
    }

    @Test
    void effectsWithoutApprovalAreGradedByWhatTheyCanDo() throws Exception {
        AuditReport r = audit("""
                tool Ops   { use: shell  allow: "df"  unattended: true }
                tool Hook  { use: webhook  url: env.HOOK }
                tool Notes { use: file  root: "notes"  mode: write }
                tool Api   { use: http  base_url: "https://api.example.com"  methods: "GET, DELETE" }
                tool Read  { use: http  base_url: "https://api.example.com" }
                agent A { model: "m"  system: "s"  tools: [Ops, Hook, Notes, Api, Read] }
                """ + WORKFLOW);
        List<Finding> la02 = rule(r, "LA02");
        assertThat(la02).filteredOn(f -> f.where().endsWith("Ops")).singleElement().extracting(Finding::severity).isEqualTo(Severity.HIGH);
        assertThat(la02).filteredOn(f -> f.where().endsWith("Hook")).singleElement().extracting(Finding::severity).isEqualTo(Severity.MEDIUM);
        assertThat(la02).filteredOn(f -> f.where().endsWith("Notes")).singleElement().extracting(Finding::severity).isEqualTo(Severity.LOW);
        assertThat(la02).filteredOn(f -> f.where().equals("A / Api")).singleElement().extracting(Finding::severity).isEqualTo(Severity.MEDIUM);
        assertThat(la02).as("a GET-only http tool is a read").noneMatch(f -> f.where().endsWith("Read"));
        assertThat(la02).as("a writing http tool with no allow_paths").anyMatch(f -> f.where().equals("tool Api") && f.severity() == Severity.LOW);
        assertThat(la02).allSatisfy(f -> assertThat(f.owasp()).contains(Owasp.LLM06));
    }

    @Test
    void aMissingRunBudgetIsUnboundedConsumption() throws Exception {
        AuditReport r = audit("agent A { model: \"m\"  system: \"s\" }" + WORKFLOW);
        assertThat(rule(r, "LA03")).singleElement().satisfies(f -> {
            assertThat(f.severity()).isEqualTo(Severity.MEDIUM);
            assertThat(f.owasp()).containsExactly(Owasp.LLM10);
        });
        assertThat(rule(audit("budget { tokens: 1000 }\nagent A { model: \"m\"  system: \"s\" }" + WORKFLOW), "LA03")).isEmpty();
    }

    @Test
    void privateDataWithoutAPiiGuardAndMemoryFedByTheWebAreFound() throws Exception {
        AuditReport r = audit("""
                tool Db { use: sql  url: env.DB_URL }
                agent A { model: "m"  system: "s"  tools: [Db] }
                agent B { model: "m"  system: "s"  tools: [web_search]  memory { facts: "memory/facts.json" } }
                agent C { model: "m"  system: "s"  tools: [Db]  guard { pii: mask } }
                """ + WORKFLOW);
        assertThat(rule(r, "LA04")).extracting(Finding::where).containsExactlyInAnyOrder("A", "B");
        assertThat(rule(r, "LA09")).singleElement().satisfies(f -> {
            assertThat(f.where()).isEqualTo("B");
            assertThat(f.owasp()).contains(Owasp.LLM04);
        });
    }

    @Test
    void riskyToolSettingsAreFound() throws Exception {
        AuditReport r = audit("""
                tool Internal { use: http  base_url: "http://10.0.0.5"  allow_private: true  allow_http: true }
                tool Ops      { use: shell  allow: "bash"  allow_interpreters: true }
                tool Mail     { use: email  host: "localhost"  security: none  allow_insecure: true  from: "a@x.com"  allow_to: "*@x.com" }
                tool Notes    { use: file  root: "notes"  mode: write  overwrite: true }
                agent A { model: "m"  system: "s"  tools: [Ops]  approve: [Ops] }
                """ + WORKFLOW);
        assertThat(rule(r, "LA05")).extracting(Finding::severity).containsExactlyInAnyOrder(Severity.HIGH, Severity.MEDIUM);
        assertThat(rule(r, "LA06")).singleElement().extracting(Finding::severity).isEqualTo(Severity.HIGH);
        assertThat(rule(r, "LA07")).extracting(Finding::severity).containsExactlyInAnyOrder(Severity.LOW, Severity.MEDIUM);
        assertThat(rule(r, "LA12")).hasSize(1);
    }

    @Test
    void whatComesFromOutsideTheRepositoryIsSupplyChain() throws Exception {
        AuditReport r = audit("""
                mcp Tools { transport: "stdio" cmd: "npx some-server" }
                tool Pets   { use: openapi  spec: "https://example.com/petstore.json" }
                tool Skills { use: skill_registry  url: "https://skills.example.com" }
                tool Custom { use: class  class: "com.acme.Tool" }
                knowledge Handbook { source: "docs/" }
                agent A { model: "m"  system: "s"  tools: [Custom, host_tool]  mcp_servers: [Tools] }
                """ + WORKFLOW);
        assertThat(rule(r, "LA08")).hasSize(3).allSatisfy(f -> assertThat(f.owasp()).contains(Owasp.LLM03));
        assertThat(rule(r, "LA14")).extracting(Finding::where).containsExactlyInAnyOrder("A / Custom", "A / host_tool");
        assertThat(rule(r, "LA13")).singleElement().satisfies(f -> assertThat(f.owasp()).contains(Owasp.LLM04, Owasp.LLM08));
        assertThat(r.agents().get(0).outward()).contains("mcp Tools");
    }

    @Test
    void autonomyGrantedWithoutAPersonAndRewindsThatRepeatEffectsAreFound() throws Exception {
        AuditReport r = audit("""
                budget { tokens: 1000 }
                agent Triager { model: "m"  system: "s"  output_schema: { choice: string, reasoning: string } }
                tool Hook { use: webhook  url: env.HOOK }
                agent Sender { model: "m"  system: "s"  tools: [Hook]  approve: [Hook] }
                decision Refund {
                    proposed by: Triager
                    choices: approve, reject
                    group cases by: tier
                    ask: lead
                    trust {
                        start at watch
                        never go above act
                        to suggest: after 5 cases, agreeing at least 50%
                        to act: after 8 cases, agreeing at least 50%
                        moving up is automatic
                    }
                }
                workflow Main(tier) {
                    checkpoint a
                    decide Refund -> verdict
                    delegate "send {verdict}" to Sender -> sent
                    rewind to a at most 1 time side effects: repeat
                }
                """);
        assertThat(rule(r, "LA10")).extracting(Finding::severity).containsExactlyInAnyOrder(Severity.MEDIUM, Severity.LOW);
        assertThat(rule(r, "LA11")).singleElement().satisfies(f -> assertThat(f.line()).isGreaterThan(0));
    }

    @Test
    void theReportNamesEveryOwaspRiskAndTheJsonParses() throws Exception {
        AuditReport r = audit("agent A { model: \"m\"  system: \"s\"  tools: [web_search] }" + WORKFLOW);
        String md = r.markdown();
        for (Owasp o : Owasp.values()) assertThat(md).contains(o.id() + " " + o.title());
        assertThat(md).contains("## What each agent can do").contains("## Findings").contains("## Not covered by this audit");
        assertThat(md).matches("(?s)[\\x00-\\x7F]*").as("plain ASCII, so any terminal shows it");
        var json = new com.fasterxml.jackson.databind.ObjectMapper().readTree(r.json());
        assertThat(json.get("summary").get("medium").asInt()).isEqualTo(1);
        assertThat(json.get("findings").get(0).get("owasp").get(0).asText()).isEqualTo("LLM10:2025");
        assertThat(json.get("agents").get(0).get("untrusted").get(0).asText()).isEqualTo("web_search");
    }
}
