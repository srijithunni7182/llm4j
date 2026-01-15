package io.github.llm4j.headhunter;

import io.github.llm4j.DefaultLLMClient;
import io.github.llm4j.agent.AgentResult;
import io.github.llm4j.agent.ReActAgent;
import io.github.llm4j.config.LLMConfig;
import io.github.llm4j.mcp.McpClient;
import io.github.llm4j.mcp.McpToolAdapter;
import io.github.llm4j.mcp.StdioMcpTransport;
import io.github.llm4j.provider.google.GoogleProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.io.File;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Scanner;

@SpringBootApplication
public class HeadhunterApplication implements CommandLineRunner {

    private static final Logger logger = LoggerFactory.getLogger(HeadhunterApplication.class);

    public static void main(String[] args) {
        SpringApplication.run(HeadhunterApplication.class, args);
    }

    @Override
    public void run(String... args) throws Exception {
        logger.info("Starting Open Source Headhunter Agent...");
        Scanner scanner = new Scanner(System.in);
        String githubToken = System.getenv("GITHUB_TOKEN");
        GitHubClient gitHubClient = new GitHubClient(githubToken);

        // 0. Setup AI
        String apiKey = System.getenv("GOOGLE_API_KEY");
        if (apiKey == null || apiKey.isEmpty()) {
            System.err.println("ERROR: GOOGLE_API_KEY environment variable is not set.");
            System.err.println("Please run: export GOOGLE_API_KEY=your_key_here");
            return;
        }

        LLMConfig config = LLMConfig.builder()
                .apiKey(apiKey)
                .defaultModel("gemini-1.5-pro") // Use a capable model for coding
                .build();
        GoogleProvider provider = new GoogleProvider(config);
        DefaultLLMClient llmClient = new DefaultLLMClient(provider);

        // 1. Discovery Phase (Cloud/REST)
        System.out.println("\n--- PHASE 1: DISCOVERY ---");
        System.out.print("Enter search term (e.g., 'java', 'spring'): ");
        String query = scanner.nextLine();

        List<Map<String, Object>> issues = gitHubClient.findGoodFirstIssues(query);
        if (issues.isEmpty()) {
            System.out.println("No issues found. Exiting.");
            return;
        }

        System.out.println("\nFound " + issues.size() + " potential jobs:");
        for (int i = 0; i < issues.size(); i++) {
            Map<String, Object> issue = issues.get(i);
            System.out.printf("[%d] %s (Repo: %s)\n", i + 1, issue.get("title"), issue.get("repository_url"));
        }

        System.out.print("\nSelect an issue to tackle (1-" + issues.size() + "): ");
        int choice = Integer.parseInt(scanner.nextLine()) - 1;
        Map<String, Object> selectedIssue = issues.get(choice);
        String repoUrl = (String) selectedIssue.get("html_url");
        String cloneUrl = repoUrl.split("/issues/")[0] + ".git";
        String repoName = cloneUrl.split("/")[4].replace(".git", "");
        String issueBody = (String) selectedIssue.get("body");
        String issueTitle = (String) selectedIssue.get("title");

        System.out.println("\nSelected Job: " + issueTitle);
        System.out.println("Target Repo: " + cloneUrl);

        // 2. Acquisition Phase (Local/MCP - Git)
        System.out.println("\n--- PHASE 2: ACQUISITION (MCP) ---");
        System.out.println("Connecting to Git MCP Server...");

        // Simulating Git MCP clone
        File workspaceDir = new File("workspace/" + repoName);
        if (workspaceDir.exists()) {
            System.out.println("Directory exists, skipping clone.");
        } else {
            Process p = new ProcessBuilder("git", "clone", cloneUrl, workspaceDir.getPath()).inheritIO().start();
            p.waitFor();
        }

        // 3. Analysis & Resolution Phase (Local/AI + MCP)
        System.out.println("\n--- PHASE 3: ANALYSIS & RESOLUTION (AI + MCP) ---");
        String absPath = workspaceDir.getAbsolutePath();

        // Connect to Filesystem MCP
        StdioMcpTransport transport = new StdioMcpTransport(
                List.of("npx", "-y", "@modelcontextprotocol/server-filesystem", absPath),
                Collections.emptyMap());

        try (McpClient mcpClient = new McpClient(transport)) {
            mcpClient.initialize();
            System.out.println("Connected to Filesystem MCP.");

            // Adapt MCP tools for ReAct Agent
            List<Map<String, Object>> mcpTools = mcpClient.listTools();
            ReActAgent.Builder agentBuilder = ReActAgent.builder()
                    .llmClient(llmClient)
                    .maxIterations(15); // Give it enough steps to explore

            for (Map<String, Object> toolDef : mcpTools) {
                agentBuilder.addTool(new McpToolAdapter(mcpClient, toolDef));
            }

            ReActAgent agent = agentBuilder.build();

            String prompt = String.format(
                    """
                            I have cloned the repository '%s' to the local directory.

                            I need you to fix the following issue:
                            Title: %s
                            Description:
                            %s

                            Please explore the codebase using the filesystem tools, analyze the relevant files to understand the bug, and then generate a fix by writing to the files.

                            Focus on the most likely files related to the issue title.
                            """,
                    repoName, issueTitle, issueBody);

            System.out.println("Dispatching Agent...");
            AgentResult result = agent.run(prompt);

            System.out.println("\n--- AGENT REPORT ---");
            System.out.println("Steps Taken: " + result.getSteps().size());
            System.out.println("Final Answer: " + result.getFinalAnswer());

            // --- PHASE 4: CONTRIBUTION (Git + GitHub) ---
            System.out.println("\n--- PHASE 4: CONTRIBUTION (Git + GitHub) ---");
            System.out.print("Do you want to commit these changes and create a PR? (y/n): ");
            String proceed = scanner.nextLine();
            if (!"y".equalsIgnoreCase(proceed.trim())) {
                System.out.println("Exiting. You can manually check the changes in: " + absPath);
                return;
            }

            // 1. Commit & Push
            String branchName = "fix/issue-" + Math.abs(selectedIssue.get("number").hashCode()); // Simple branch name
            System.out.println("Creating branch: " + branchName);

            try {
                // git checkout -b <branch>
                new ProcessBuilder("git", "checkout", "-b", branchName).directory(workspaceDir).inheritIO().start()
                        .waitFor();

                // git add .
                new ProcessBuilder("git", "add", ".").directory(workspaceDir).inheritIO().start().waitFor();

                // git commit -m "..."
                new ProcessBuilder("git", "commit", "-m", "fix: resolved issue " + selectedIssue.get("title"))
                        .directory(workspaceDir).inheritIO().start().waitFor();

                System.out.println("Changes committed locally.");

                // git push origin <branch>
                System.out.println("Attempting to push to origin (this requires git auth)...");
                int exitCode = new ProcessBuilder("git", "push", "origin", branchName).directory(workspaceDir)
                        .inheritIO().start().waitFor();

                if (exitCode != 0) {
                    System.err.println("Git push failed. Please push manually.");
                } else {
                    // 2. Create PR only if push succeeded
                    if (githubToken == null || githubToken.isEmpty()) {
                        System.out.println(
                                "GITHUB_TOKEN not found. Skipping PR creation. Please create PR manually from branch: "
                                        + branchName);
                    } else {
                        System.out.println("Creating Pull Request...");
                        String[] parts = cloneUrl.replace(".git", "").split("/");
                        String owner = parts[parts.length - 2];
                        String repo = parts[parts.length - 1];

                        try {
                            GitHubClient authenticatedClient = new GitHubClient(githubToken);
                            String prUrl = authenticatedClient.createPullRequest(owner, repo,
                                    "Fix: " + selectedIssue.get("title"),
                                    "Fixed the issue using Open Source Headhunter Agent.\n\nGenerated by AI.",
                                    branchName,
                                    "main");
                            System.out.println("SUCCESS! Pull Request Created: " + prUrl);
                        } catch (Exception e) {
                            System.err.println("Failed to create PR: " + e.getMessage());
                        }
                    }
                }

            } catch (Exception e) {
                System.err.println("Git operations failed: " + e.getMessage());
            }
        }

        System.out.println("\n--- MISSION ACCOMPLISHED ---");
    }
}
