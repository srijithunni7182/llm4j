package io.github.llm4j.loom.cli;

import io.github.llm4j.loom.debug.dap.DapServer;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;

/**
 * {@code weave dap}: the Debug Adapter Protocol on standard input and output, so an editor's
 * debugger can step through a workflow. It maps the editor's requests onto the same stepper {@code
 * weave step} uses; nothing has an effect.
 */
@Command(
        name = "dap",
        description =
                "Speaks the Debug Adapter Protocol on standard input and output, so an editor can debug a workflow. Nothing has an effect.")
final class DapCommand implements Callable<Integer> {

    @Override
    public Integer call() throws Exception {
        WeaveEnv env = WeaveEnv.system();
        // standard output carries only the protocol: whatever else is printed goes to standard
        // error
        new DapServer(System.in, System.out, env.models(), env.env()).serve();
        return 0;
    }
}
