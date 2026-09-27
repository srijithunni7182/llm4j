package io.github.llm4j.loom.cli;

import io.github.llm4j.agent.skill.AgentSkill;
import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.execution.*;
import io.github.llm4j.loom.lexer.Lexer;
import io.github.llm4j.loom.parser.LoomParser;
import io.github.llm4j.loom.runtime.LoomEngine;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.jar.*;
import java.util.zip.*;

@Command(name = "weave", mixinStandardHelpOptions = true, version = "weave 1.0",
        description = "Loom Orchestration CLI - Weave workflows into executable reality.")
public class WeaveCLI implements Callable<Integer> {

    @Override
    public Integer call() throws Exception {
        CommandLine.usage(this, System.out);
        return 0;
    }

    @Command(name = "run", description = "Executes a .loom script immediately.")
    static class RunCommand implements Callable<Integer> {
        @Parameters(index = "0", description = "The .loom script file to execute.")
        private File scriptFile;

        @Option(names = {"-l", "--loot"}, description = "The .loot tool mapping file.")
        private File lootFile;

        @Option(names = {"-w", "--workflow"}, description = "The name of the workflow to run.", defaultValue = "Main")
        private String workflowName;

        @Option(names = {"-i", "--input"}, description = "Initial context variables in key=value format.")
        private Map<String, String> inputs = new HashMap<>();

        @Option(names = "--max-tokens", description = "Cap the run's tokens (replaces the script's run budget).")
        private Long maxTokens;

        @Option(names = "--max-calls", description = "Cap the run's LLM calls (replaces the script's run budget).")
        private Long maxCalls;

        @Option(names = "--max-cost", description = "Cap the run's cost, e.g. 0.50 (needs --prices).")
        private String maxCost;

        @Option(names = "--prices", description = "Price table: lines of 'model = input / output' per million tokens.")
        private File prices;

        @Override
        public Integer call() throws Exception {
            if (!scriptFile.exists()) {
                System.err.println("Error: Script file not found: " + scriptFile);
                return 1;
            }
            return execute(scriptFile, lootFile, workflowName, inputs, new DefaultLLMClientFactory(),
                    maxTokens, maxCalls, maxCost, prices, new ConsoleHumanInterface(), System.out, System.err);
        }
    }

    /**
     * Runs a workflow the way {@code weave run} does, with an explicit client factory and output streams
     * (so it can be tested). Exit codes: 0 done, 1 failed, 2 bad budget options, 3 stopped by a budget.
     */
    static int execute(File scriptFile, File lootFile, String workflowName, Map<String, String> inputs,
                       LLMClientFactory clientFactory, Long maxTokens, Long maxCalls, String maxCost, File pricesFile,
                       io.github.llm4j.loom.runtime.HumanInterface human, java.io.PrintStream out,
                       java.io.PrintStream err) throws Exception {
        java.math.BigDecimal cost = null;
        if (maxCost != null) {
            if (pricesFile == null) {
                err.println("Error: --max-cost needs --prices <file> (lines of 'model = input / output' per million tokens).");
                return 2;
            }
            cost = new java.math.BigDecimal(maxCost.strip().replaceFirst("^\\$", ""));
        }
        if ((maxTokens != null && maxTokens <= 0) || (maxCalls != null && maxCalls <= 0)
                || (cost != null && cost.signum() <= 0)) {
            err.println("Error: budget limits must be positive.");
            return 2;
        }

        out.println("🧵 Weaving workflow: " + scriptFile.getName());

        // 1. Parsing
        LoomLoader loader = new LoomLoader();
        LoomScript script = loader.load(scriptFile.getAbsolutePath());

        // 2. Setup
        ToolRegistry registry = new ToolRegistry();
        if (lootFile != null && lootFile.exists()) {
            new LootLoader().loadIntoRegistry(lootFile.getAbsolutePath(), registry);
            out.println("🛠️  Loaded tools from: " + lootFile.getName());
        }

        HarnessExecutor executor = new HarnessExecutor(script, registry, clientFactory);
        executor.setHumanInterface(human);
        if (pricesFile != null) executor.setPriceTable(io.github.llm4j.budget.PriceTable.load(pricesFile.toPath()));
        executor.setBudgetOverrides(maxTokens, maxCalls, cost);
        executor.initialize();

        out.println("🚀 Executing workflow: " + workflowName + "...");
        try {
            executor.executeWorkflow(workflowName, inputs);
            out.println("✅ Workflow completed successfully.");
            return 0;
        } catch (io.github.llm4j.budget.BudgetExceeded stop) {
            out.println("⛔ Stopped: " + stop.getMessage() + ". Everything paid for so far is kept.");
            return 3;
        } catch (Exception e) {
            err.println("❌ Execution failed: " + e.getMessage());
            e.printStackTrace(err);
            return 1;
        } finally {
            if (executor.getRunBudget() != null) {
                out.println();
                out.println("💸 Spend");
                out.print(executor.spend().table());
            }
            executor.shutdown();
        }
    }

    @Command(name = "package", description = "Packages a .loom workflow into a runnable JAR.")
    static class PackageCommand implements Callable<Integer> {
        @Parameters(index = "0", description = "The .loom script file.")
        private File scriptFile;

        @Option(names = {"-l", "--loot"}, description = "The .loot tool mapping file.")
        private File lootFile;

        @Option(names = {"-o", "--out"}, description = "Output JAR filename.", defaultValue = "loom-app.jar")
        private String outputName;

        @Option(names = {"--fat"}, description = "Create a fat JAR containing all dependencies.")
        private boolean fatJar;

        @Option(names = {"--thin"}, description = "Create a thin JAR (default).", defaultValue = "true")
        private boolean thinJar;

        @Override
        public Integer call() throws Exception {
            if (!scriptFile.exists()) {
                System.err.println("Error: Script file not found: " + scriptFile);
                return 1;
            }

            File outFile = new File(outputName);
            System.out.println("📦 Packaging workflow into: " + outFile.getAbsolutePath());

            Manifest manifest = new Manifest();
            manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
            manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, PackagedLoomApp.class.getName());

            try (JarOutputStream jos = new JarOutputStream(new FileOutputStream(outFile), manifest)) {
                // Add script and loot
                addFileToJar(jos, scriptFile, "app.loom");
                if (lootFile != null && lootFile.exists()) {
                    addFileToJar(jos, lootFile, "app.loot");
                }

                if (fatJar) {
                    System.out.println("🚀 Building Fat JAR (bundling dependencies)...");
                    bundleDependencies(jos);
                } else {
                    System.out.println("📄 Building Thin JAR (referencing dependencies)...");
                    // bundleCurrentProject adds all classes in the current classpath entry for this project
                    bundleCurrentProject(jos);
                }
            }

            System.out.println("✅ Packaging complete: " + outputName);
            return 0;
        }

        private void addFileToJar(JarOutputStream jos, File file, String entryName) throws IOException {
            jos.putNextEntry(new JarEntry(entryName));
            Files.copy(file.toPath(), jos);
            jos.closeEntry();
        }

        private void addClassToJar(JarOutputStream jos, Class<?> clazz) throws IOException {
            String entryName = clazz.getName().replace('.', '/') + ".class";
            try (InputStream is = clazz.getResourceAsStream("/" + entryName)) {
                if (is != null) {
                    jos.putNextEntry(new JarEntry(entryName));
                    is.transferTo(jos);
                    jos.closeEntry();
                }
            }
        }

        private void bundleCurrentProject(JarOutputStream jos) throws IOException {
            // Find where our classes are (usually target/classes)
            String resourcePath = PackagedLoomApp.class.getName().replace('.', '/') + ".class";
            java.net.URL url = PackagedLoomApp.class.getResource("/" + resourcePath);
            if (url != null && url.getProtocol().equals("file")) {
                String rootPath = url.getPath().substring(0, url.getPath().length() - resourcePath.length());
                Path root = Paths.get(rootPath);
                Files.walk(root).filter(Files::isRegularFile).forEach(path -> {
                    try {
                        String name = root.relativize(path).toString().replace('\\', '/');
                        if (!name.equals("META-INF/MANIFEST.MF")) {
                            try {
                                jos.putNextEntry(new JarEntry(name));
                                Files.copy(path, jos);
                                jos.closeEntry();
                            } catch (ZipException e) {
                                // Skip duplicate
                            }
                        }
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });
            }
        }

        private void bundleDependencies(JarOutputStream jos) throws IOException {
            String classpath = System.getProperty("java.class.path");
            String[] entries = classpath.split(File.pathSeparator);
            for (String entry : entries) {
                if (entry.endsWith(".jar")) {
                    // Avoid bundling the output jar if it's already there
                    if (entry.contains(outputName)) continue;
                    
                    try (JarFile jar = new JarFile(entry)) {
                        Enumeration<JarEntry> jarEntries = jar.entries();
                        while (jarEntries.hasMoreElements()) {
                            JarEntry je = jarEntries.nextElement();
                            if (je.isDirectory() || je.getName().equals("META-INF/MANIFEST.MF") || je.getName().startsWith("META-INF/SIG-")) {
                                continue;
                            }
                            // Avoid duplicates
                            try {
                                jos.putNextEntry(new JarEntry(je.getName()));
                                try (InputStream is = jar.getInputStream(je)) {
                                    is.transferTo(jos);
                                }
                                jos.closeEntry();
                            } catch (ZipException e) {
                                // Ignore duplicate entries
                            }
                        }
                    }
                } else {
                    // It's a directory (project classes)
                    bundleCurrentProject(jos);
                }
            }
        }
    }

    public static void main(String[] args) {
        int exitCode = new CommandLine(new WeaveCLI())
                .addSubcommand(new RunCommand())
                .addSubcommand(new PackageCommand())
                .execute(args);
        System.exit(exitCode);
    }
}
