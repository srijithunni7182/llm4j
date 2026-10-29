# Publishing to Maven Central

One command from the repository root builds, tests, signs and uploads **ten libraries and the parent pom** as a single bundle to the
[Sonatype Central Portal](https://central.sonatype.com/):

| Artifact (`io.github.srijithunni7182`) | What it is |
|---|---|
| `llm4j-parent` | the parent pom the others inherit (must be published, or the others cannot be resolved) |
| `ai-agent4j` | the agent library |
| `ai-agent4j-addons` | RAG stores and embeddings (heavy engines are **optional** dependencies) |
| `ai-agent4j-tools` | built-in tools |
| `eval4j`, `eval4j-report` | evaluation library and its reports |
| `ai-agent4j-loom` | Loom: the thin library jar, plus the runnable `weave` jar as the `cli` classifier |
| `loom-inspect-core`, `loom-inspect`, `loom-fabric` | live observability for Loom runs, and the Fabric server (`-all` jar) |
| `llm4j-spring-boot-starter` | Spring Boot auto-configuration (Loom, tools, addons and spring-jdbc are optional) |

Not published: `engram` and the example applications (they are built only with `-Pextended`).

Every release runs the default tests; `-Prelease` never skips them. Slow tests (`integration`, `fragile`) run with `-Pextended`
(see [TESTING_STRATEGY.md](TESTING_STRATEGY.md)) and are worth one run before you tag.

## One-time setup

1. **Central account and namespace.** Sign in at [central.sonatype.com](https://central.sonatype.com/) and verify the namespace
   `io.github.srijithunni7182` (Namespaces → add → create the public GitHub repository they name).
2. **User token.** Account → *Generate User Token*. The token's username and password are your deployment credentials, not your login.
3. **GPG key.** The signing key is `8E195D64FE1D7BFC8092B118902BDC3C6B9FF68E` and is on `keyserver.ubuntu.com`. Check with
   `gpg --keyserver keyserver.ubuntu.com --recv-keys 8E195D64FE1D7BFC8092B118902BDC3C6B9FF68E`.
4. **GitHub secrets** (repository → Settings → Secrets and variables → Actions):

   | Secret | Value |
   |---|---|
   | `MAVEN_CENTRAL_USERNAME` | the token username |
   | `MAVEN_CENTRAL_PASSWORD` | the token password |
   | `GPG_SECRET_KEY` | `gpg --armor --export-secret-keys 8E195D64FE1D7BFC8092B118902BDC3C6B9FF68E` |
   | `GPG_PASSPHRASE` | the key's passphrase |

## Releasing

1. **Between releases `main` carries the next `-SNAPSHOT` version** (for example `6.2.0-SNAPSHOT`), so a jar built from the repository can never be mistaken for the published release of the same number. What must name a version someone can download (the JBang catalogue, generated projects, a deployment's Fabric image, the docs) reads the last published release from one place: the `llm4j.released` property of the parent `pom.xml`. To release, run
   `scripts/release-version.sh release 6.2.0` (sets every pom, `llm4j.released` and `jbang-catalog.json`), commit it, and continue below. A released version can never be changed or deleted on Central. `ReleasedVersionSourceOfTruthTest` fails if the catalogue and `llm4j.released` differ.
   After the tag, `scripts/release-version.sh next 6.2.1-SNAPSHOT` moves `main` on (`llm4j.released` stays `6.2.0`); commit that as `chore: start 6.2.1-SNAPSHOT`.
   A jar says what it is: `weave --version` prints `release` or `development`, the commit and the build time, and the same facts are in every module jar's manifest (`Implementation-Version`, `Build-Kind`, `Build-Commit`, `Build-Dirty`, `Build-Time`). `-Prelease` marks the build a release.
2. Check locally, without a key:
   `make release-check` (= `mvn -Prelease -Dgpg.skip=true clean verify`, then `java scripts/CheckJars.java src`). It builds the sources and javadoc jars and the `cli` jar, and refuses any jar that does not say what it is or holds a half-compiled class from an editor's language server (see [CONTRIBUTING.md](../CONTRIBUTING.md)).
3. Tag and push: `git tag v5.1 && git push origin v5.1`. The workflow `Build, Validate, and Deploy` runs the tests on JDK 17 and 21,
   then `mvn -B -Prelease deploy` (tests again, sign, upload). You can also start it by hand with *Run workflow → deploy*.
4. **Review in the Portal.** By default the upload is *not* published (`central.autoPublish=false`): open
   *Deployments* in the Central Portal, check the six artifacts and their signatures validated, then press **Publish**.
   Publishing is permanent. Once you trust the flow, tick `auto_publish` when starting the workflow by hand, or pass
   `-Dcentral.autoPublish=true`.
5. Central syncs in 10–30 minutes; then the artifacts appear at `search.maven.org` and are resolvable from any build.
6. The VS Code extension is published separately and automatically: a change to it that bumps its version is published by CI when it lands on `main` ([PUBLISHING_VSCODE.md](PUBLISHING_VSCODE.md); it bundles the `cli` jar).
7. Attach `ai-agent4j-loom-<version>-cli.jar` to the GitHub release (the workflow keeps it as the `weave-cli-jar` artifact of the
   *Release check* job) so people can download `weave` without Maven.
8. Add a row to the timeline in [ABOUT.md](ABOUT.md) for the release and any feature worth remembering. The stats block there
   refreshes itself monthly (`scripts/about-stats.sh`, workflow `Refresh About stats`); only the timeline is written by hand.

## From your own machine

```bash
export CENTRAL_USERNAME=<token username> CENTRAL_PASSWORD=<token password>
export MAVEN_GPG_PASSPHRASE=<key passphrase>
mvn -B -s settings.xml -Prelease deploy
```

`settings.xml` in the repository root reads the two token variables from the environment, so no secret is written to disk.

## What consumers get

- `ai-agent4j-addons` declares onnxruntime, DJL, pgvector, PostgreSQL and Pinecone as **optional**: an application that uses local
  embeddings or pgvector adds that dependency itself (a comment in the addons pom lists them). Nobody downloads 150 MB of natives
  they do not use.
- `ai-agent4j-loom` is a normal 1 MB library; `weave` (the CLI and what the VS Code extension bundles) is the `cli` classifier:
  `<classifier>cli</classifier>` or the file from the GitHub release.

## Troubleshooting

- **`Bundle has content that does NOT have a .pom file`, or `Filename '_remote.repositories' is not valid`**: on the CI runner Maven leaves bookkeeping files in the staging folder
  (`maven-metadata-local.xml` in each artifact folder, `_remote.repositories` and its checksums in each version folder) and the Portal rejects them. The last module of the reactor,
  `src/release/bundle-cleanup`, deletes them before the bundle is zipped; keep it last in the root pom's `<modules>`. The deploy job also prints the bundle's contents and keeps
  the zip as the `central-bundle` artifact, so a rejection can be inspected (or the zip cleaned with `zip -d` and uploaded by hand under *Publish Component*).

- **401 Unauthorized**: the token pair is wrong, or you used your login instead of the generated token.
- **Signing failed**: `MAVEN_GPG_PASSPHRASE` is missing or wrong, or the key import secret was truncated; run the deploy step locally to see gpg's message.
- **Validation errors in the Portal**: a missing signature, sources or javadoc jar, or pom metadata (license, developers, scm). Each module's pom inherits these from `llm4j-parent`.
- **`Parent not found` for a consumer**: `llm4j-parent` was not part of the published bundle; publish from the repository root, not from a module.
- **Version already exists**: bump the version; Central never accepts a version twice.
