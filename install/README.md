# Installing weave

You need Java 17 or newer. Nothing else: no account, no key. Try it first:

    jbang weave@srijithunni7182/llm4j try

That is [JBang](https://www.jbang.dev) fetching the CLI from Maven Central. To put `weave` on your PATH:

    jbang app install weave@srijithunni7182/llm4j

Without JBang, download `weave.jar` and the `weave` launcher (`weave.cmd` on Windows) from the release, keep them in one folder, check the download against `weave.jar.sha256`, and run `./weave try`.

    sha256sum -c weave.jar.sha256

The launcher finds Java (`JAVA_HOME`, then `PATH`), says plainly if it is missing or older than 17, passes every argument through untouched, and honours `JAVA_OPTS`.

**With the VS Code extension.** The Loom extension carries the command line and adds `weave` to VS Code's integrated terminals (after your existing PATH entries, so a `weave` you installed wins; the setting `loom.terminal.addWeaveToPath` turns it off). That covers VS Code only: to type `weave` in any other terminal, install it one of the ways above.

`weave --version` prints the version and the protocol version of `weave serve`; the VS Code extension warns when its bundled engine and a `weave` on your PATH speak different protocols.

Package managers (Homebrew tap, SDKMAN candidate, Scoop manifest) are follow-ups: each needs `weave.jar`, its checksum and a one-line `weave` wrapper, which is exactly what the release publishes.
