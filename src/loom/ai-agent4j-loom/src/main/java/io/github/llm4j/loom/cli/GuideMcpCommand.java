package io.github.llm4j.loom.cli;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

/**
 * {@code weave guide-mcp}: the Guide's MCP server over standard input and output, started by the
 * developer's assistant (a Copilot custom agent or a Claude Code agent) for one project.
 */
@Command(
        name = "guide-mcp",
        description =
                "Serves the Guide's tools to a coding assistant over MCP (standard input and output): read the project, show things in the editor, record what the developer learnt. None writes a project file or spends money.")
final class GuideMcpCommand implements Callable<Integer> {

    @Parameters(index = "0", arity = "0..1", paramLabel = "<folder or script>", description = "The project (default: here).")
    File where;

    @Override
    public Integer call() throws IOException {
        WeaveEnv env = WeaveEnv.system();
        StringBuilder why = new StringBuilder();
        Path script = ProjectAdvisor.find((where == null ? new File(".") : where).toPath().toAbsolutePath().normalize(), why);
        if (script == null) {
            System.err.println(why);
            return 2;
        }
        Path root = io.github.llm4j.loom.init.ProjectLayout.root(script);
        // Standard output carries protocol messages only: everything a command would print goes to standard error.
        WeaveEnv quiet = env.withStreams(System.err, System.err);
        GuideMcpServer server =
                new GuideMcpServer(
                        script,
                        quiet,
                        new GuideEvents(root, env.clock()),
                        new GuideLearner(Path.of(System.getProperty("user.home")), root, env.clock()),
                        EditorChannel.mailbox(root, env.clock(), env.sleeper()));
        Writer out = new OutputStreamWriter(System.out, StandardCharsets.UTF_8);
        server.serve(new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)), out);
        return 0;
    }
}
