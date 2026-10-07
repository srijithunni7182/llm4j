# Publishing the Loom extension to the VS Code Marketplace

The extension is `loom/vscode-loom`. It bundles the `weave` command line (`bin/weave.jar`, the `cli` jar from the Loom build, about 22 MB),
so a published `.vsix` is about 21 MB, well under the Marketplace's 200 MB limit. Everything below is a one-time setup, then one command per release.

## One-time setup (about 20 minutes, all in a browser)

1. **A Microsoft account** (any personal one works) and an **Azure DevOps organization**: sign in at
   [dev.azure.com](https://dev.azure.com) and create an organization if you have none. It exists only to issue the access token.
2. **A Personal Access Token (PAT).** In Azure DevOps: *User settings* (top right) → *Personal access tokens* → *New Token*:
   - Organization: **All accessible organizations** (this is required, a token for one organization is rejected)
   - Scopes: *Custom defined* → *Show all scopes* → **Marketplace → Manage**
   - Expiration: up to a year. Copy the token now; it is shown once. Treat it like a password.
3. **A publisher.** Go to [marketplace.visualstudio.com/manage](https://marketplace.visualstudio.com/manage) → *Create publisher*.
   The **ID must be exactly** `srijithunni7182` (it is `publisher` in `loom/vscode-loom/package.json`, and part of the extension's permanent name
   `srijithunni7182.vscode-loom`). The display name can be anything.
4. **Log in once** from the repository root:
   ```bash
   cd loom/vscode-loom
   npx --yes @vscode/vsce@3.2.1 login srijithunni7182     # paste the PAT when asked
   ```

## Every release

```bash
# 1. bump the version in loom/vscode-loom/package.json and add a section to loom/vscode-loom/CHANGELOG.md
# 2. build the jar and the extension, and check the package
scripts/build-vsix.sh                  # builds weave (the cli jar), compiles, packages loom/vscode-loom/vscode-loom-<version>.vsix
scripts/verify-vsix.sh                 # unpacks it and runs the bundled jar the way the extension does

# 3. try it before the world can: install the file into your own VS Code
code --install-extension loom/vscode-loom/vscode-loom-<version>.vsix

# 4. publish
cd loom/vscode-loom && npx --yes @vscode/vsce@3.2.1 publish        # compiles, packages and uploads (or add --packagePath <file>.vsix to upload the one you tried)
```

The extension appears at `marketplace.visualstudio.com/items?itemName=srijithunni7182.vscode-loom` after a few minutes of verification
(Microsoft scans the package; the first publish can take longer). `vsce publish` (without `--packagePath`) fails if `bin/weave.jar` is missing, so an extension that
cannot draw a graph is never published.

Versions cannot be re-used or deleted from the file you uploaded: fix forward with a new version. `vsce unpublish` exists but removes the
listing for everyone, so avoid it.

## Also on Open VSX (optional)

VS Code forks (VSCodium, Gitpod, Cursor and others) read [open-vsx.org](https://open-vsx.org) instead of the Marketplace. Create an account
there, link your GitHub, claim the namespace `srijithunni7182`, create an access token, then:

```bash
npx --yes ovsx@latest publish loom/vscode-loom/vscode-loom-<version>.vsix -p <token>
```

## What the Marketplace page shows

The listing is built from files in `loom/vscode-loom`: `README.md` (the page body; relative image links need `repository` in `package.json`, which is set),
`CHANGELOG.md` (the Changelog tab), `LICENSE`, the icon `media/loom-mark-128.png`, and the categories, keywords and banner in `package.json`.
Add screenshots of the workflow graph to the README (use `https://` or repository-relative paths) before the first publish; the graph is the best advertisement.

## Troubleshooting

- **`Error: Failed request: Unauthorized (401)`** or *Access Denied*: the PAT was created for one organization, lacks *Marketplace → Manage*, or expired.
- **`The Personal Access Token verification has failed`**: log in again with `vsce login srijithunni7182`.
- **`Make sure the publisher name matches`**: the publisher ID in the Marketplace and `package.json` differ.
- **`Missing publisher`/`license` warnings**: `package.json` needs `publisher` and `license`, and a `LICENSE` file beside it (both present).
- **`bin/weave.jar is missing`**: run `scripts/build-vsix.sh` from the repository root first.
