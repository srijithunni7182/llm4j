#!/usr/bin/env bash
# Decides whether the VS Code extension has a new version to publish.
# Usage: scripts/check-extension-release.sh [--require-bump] <baseline-version|none> [extension-folder]
#
# The baseline is what the extension must be newer than: the version on the Marketplace when a change has landed on main
# ("none" before the first release), or the version on main when a pull request is checked. The Marketplace never takes a
# version twice, so:
#   - newer than the baseline: publish. CHANGELOG.md must have a "## <version>" section saying what changed.
#   - the same as the baseline: nothing to publish and no error, so a change that is not a release leaves the build green;
#     with --require-bump (pull requests) it is an error, because that change would never reach anyone.
#   - older, or not a plain x.y.z (the Marketplace takes no pre-releases): an error.
# When GITHUB_OUTPUT is set, "publish=true" or "publish=false" is written to it, and only after every check has passed.
set -euo pipefail

require_bump=false
if [[ "${1-}" == "--require-bump" ]]; then
    require_bump=true
    shift
fi
baseline="${1-}"
dir="${2:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../src/loom/vscode-loom" && pwd)}"
plain='^[0-9]+\.[0-9]+\.[0-9]+$'

fail() {
    echo "$1" >&2
    exit 1
}
emit() {
    if [[ -n "${GITHUB_OUTPUT-}" ]]; then echo "publish=$1" >> "$GITHUB_OUTPUT"; fi
}
newest() { printf '%s\n%s\n' "$1" "$2" | sort -V | tail -n 1; }

# Both versions are read as data and compared as numbers; neither is ever put in a command.
if [[ "$baseline" != "none" && ! "$baseline" =~ $plain ]]; then
    fail "The version to be newer than must look like 1.2.3, or be none (got: $(printf '%q' "$baseline"))."
fi
declared="$(node -p "JSON.parse(require('fs').readFileSync(process.argv[1], 'utf8')).version" "$dir/package.json")"
if [[ ! "$declared" =~ $plain ]]; then
    fail "package.json declares version $declared. The Marketplace takes only plain versions like 1.2.3, not pre-releases."
fi

if [[ "$baseline" == "$declared" ]]; then
    if [[ "$require_bump" == true ]]; then
        fail "package.json still declares $declared, which is already released. Bump the version (and add a \"## <version>\" section to CHANGELOG.md), or this change will never be published."
    fi
    echo "Version $declared is already published, so there is nothing to publish. Bump the version in package.json to release a change."
    emit false
    exit 0
fi
if [[ "$baseline" != "none" && "$(newest "$baseline" "$declared")" == "$baseline" ]]; then
    fail "package.json declares $declared, which is older than $baseline. Versions only go up."
fi
if ! grep -qxF "## $declared" "$dir/CHANGELOG.md"; then
    fail "CHANGELOG.md has no \"## $declared\" section. Add one that says what changed."
fi
echo "Version $declared is new (baseline: $baseline) and has a changelog section."
emit true
