# Publishing the Loom extension to the VS Code Marketplace

The extension is `src/loom/vscode-loom`, published as `srijithunni7182.vscode-llm4j-loom`. It bundles the `weave` command line (`bin/weave.jar`, the `cli` jar from the Loom build, about 24 MB),
so a published `.vsix` is about 24 MB, well under the Marketplace's 200 MB limit.

**A change to the extension is published by CI when it lands on `main`.** You do not run `vsce` yourself. The workflow is
[`.github/workflows/publish-vscode-extension.yml`](../.github/workflows/publish-vscode-extension.yml), and it uses Trusted Publishing: GitHub proves to the Marketplace which repository and workflow is asking, so no token is stored anywhere.

## How a change reaches the Marketplace

1. **Change the extension in a pull request, and bump the version.** In `src/loom/vscode-loom`, raise `version` in `package.json` and `package-lock.json` (the two places at the top of each), and add a `## <version>` section to `CHANGELOG.md` that says what changed. The Marketplace takes a version only once, so a change without a new version can never be released: the check **Is there a new version to publish?** fails the pull request with a message saying so.
2. **Merge.** The workflow asks the Marketplace which version it has. If `package.json` is newer it builds `weave.jar` from `main`, packages the extension around it, unpacks the package and runs the bundled jar (`scripts/verify-vsix.sh`), runs the extension's tests, and publishes exactly that file. If the version is not newer there is nothing to publish and the run ends green.
3. **Wait a few minutes.** Microsoft scans the package before it shows. Progress is on [marketplace.visualstudio.com/manage](https://marketplace.visualstudio.com/manage); the page is `marketplace.visualstudio.com/items?itemName=srijithunni7182.vscode-llm4j-loom`.

What starts the workflow: a change under `src/loom/vscode-loom`, except its `test/` folder (tests change nothing for a user), and a change to the workflow or its scripts. A change to the engine the extension carries (`src/loom/ai-agent4j-loom`) does **not** start a release; it ships with the next extension version, which rebuilds the jar from `main`. To release it sooner, bump the extension version in a pull request.

The version is plain `1.2.3`. The Marketplace takes no pre-release versions, and the check refuses one. Versions only go up, and a version cannot be re-used or deleted: fix forward with a new one.

To run it again without a change (a publish that failed after a green build, for example), start the workflow by hand from `main` in the Actions tab (*Run workflow*). It publishes only if the version is new.

## One-time setup (about 10 minutes, in two browser tabs)

1. **Tell the Marketplace to trust the workflow.** Go to [marketplace.visualstudio.com/manage](https://marketplace.visualstudio.com/manage), open the publisher `srijithunni7182`, select **Trusted Publishing**, expand **Create**, and enter:

   | Field | Value |
   |---|---|
   | Policy name | `GitHub release workflow` (anything descriptive) |
   | Repository owner | `srijithunni7182` |
   | Repository | `llm4j` |
   | Workflow | `publish-vscode-extension.yml` |
   | Environment | `vscode-marketplace` |

   Select **Create**. The values must match exactly: the Marketplace compares them with what GitHub signs into the token. (If the repository is private, run the workflow within 48 hours of creating the policy; the first successful run activates it for good.)
2. **Create the GitHub environment and lock it to `main`.** In the repository: *Settings* → *Environments* → *New environment*, name it `vscode-marketplace`, and under *Deployment branches and tags* choose *Selected branches and tags* and add `main`. Without this, a workflow run from any branch could ask for a Marketplace token. Optionally add *Required reviewers*: every release then waits for your approval, which is a manual click, so leave it off if you want releases fully automatic.
3. **Leave the check optional.** Do not mark *Is there a new version to publish?* as a required status check. It runs only for pull requests that change the extension, and GitHub waits forever for a required check on any other pull request.

After this, the first merge that bumps the version publishes it.

## What the workflow does and why it is built this way

- **No stored secret.** The `publish` job asks GitHub for a short-lived OpenID Connect token and trades it for a Marketplace credential that lasts 15 minutes. Each GitHub token can be traded only once.
- **Only one job can publish.** `build` has no publishing rights. `publish` has `id-token: write`, runs nothing from the repository, and publishes the file `build` made, with a pinned `vsce` (`4.1.0`; `4.0.0` has `--oidc` but sends its Marketplace token request without an `api-version`, which the Marketplace refuses with a 400). Raise that version on purpose, not by accident.
- **A failed lookup is never "nothing published".** `scripts/published-extension-version.sh` fails on a bad reply, so a flaky network cannot cause a duplicate upload. The decision logic, `scripts/check-extension-release.sh`, is tested in `src/loom/vscode-loom/test/releaseCheck.test.ts`.
- **A pull request cannot publish.** It runs only the cheap version check, and the `publish` job runs only on `main`.

## Publishing by hand

Keep this for an emergency, or for a first look at a package you built yourself.

```bash
scripts/build-vsix.sh      # builds weave (the cli jar), compiles, packages src/loom/vscode-loom/vscode-llm4j-loom-<version>.vsix
scripts/verify-vsix.sh     # unpacks it and runs the bundled jar the way the extension does
code --install-extension src/loom/vscode-loom/vscode-llm4j-loom-<version>.vsix   # try it in your own VS Code first
```

Then upload the file on [marketplace.visualstudio.com/manage](https://marketplace.visualstudio.com/manage): open the publisher, then *New extension* → *Visual Studio Code* for the first upload, or the **⋮** menu next to the extension → *Update* for a later one.

Publishing from a terminal with `vsce publish` needs a Personal Access Token. Microsoft retires global Azure DevOps tokens on **1 December 2026**, so do not build a process on one.

## Also on Open VSX (optional)

VS Code forks (VSCodium, Gitpod, Cursor and others) read [open-vsx.org](https://open-vsx.org) instead of the Marketplace. Create an account
there, link your GitHub, claim the namespace `srijithunni7182`, create an access token, then:

```bash
npx --yes ovsx@latest publish src/loom/vscode-loom/vscode-llm4j-loom-<version>.vsix -p <token>
```

## What the Marketplace page shows

The listing is built from files in `src/loom/vscode-loom`: `README.md` (the page body; relative image links need `repository` in `package.json`, which is set),
`CHANGELOG.md` (the Changelog tab), `LICENSE`, the icon `media/loom-mark-128.png`, and the display name, description, categories, keywords and banner in `package.json`.
The README already carries screenshots of the workflow graph (`docs/images/vscode-loom/`, linked by `https://raw.githubusercontent.com/...` so they show on the Marketplace). Retake them when the panel changes; the generator is described in `docs/images/vscode-loom/README.md`.
The page shows the README of the newest version only, so a documentation change reaches the page only with a new version.

## Troubleshooting

- **The pull request check says the version must be bumped:** `package.json` has the version that is already published (or on `main`). Raise it, in `package-lock.json` too, and add a `## <version>` section to `CHANGELOG.md`.
- **The `publish` job fails with a 401 or "no trusted publishing policy":** the policy in the Marketplace does not match. Compare the owner, repository, workflow file name and environment name with the table above, letter for letter.
- **The `publish` job is skipped:** the version was not new (read the `release-check` summary), or the run was not on `main`.
- **`The extension ... already exists` or a version is refused:** the version is already on the Marketplace. Bump it.
- **The Marketplace page still shows the old text:** Microsoft is still verifying the new version, which takes a few minutes. Check the status on the manage page.
- **`Make sure the publisher name matches`:** the publisher ID in the Marketplace and `publisher` in `package.json` differ.
- **`bin/weave.jar is missing` when packaging by hand:** run `scripts/build-vsix.sh` from the repository root first.
