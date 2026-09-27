package io.github.llm4j.loom.trigger.system;

import java.io.IOException;

/**
 * An operating-system or cloud scheduler that can wake Loom by running {@code weave tick <store>}. It only
 * writes user-level entries, never needs root, and tags what it writes so that uninstalling removes
 * exactly that. Planning may read the current state (e.g. {@code crontab -l}) but changes nothing.
 */
public interface SystemTriggerBackend {

    String name();

    Plan install(InstallRequest request, CommandRunner runner) throws IOException;

    Plan uninstall(InstallRequest request, CommandRunner runner) throws IOException;
}
