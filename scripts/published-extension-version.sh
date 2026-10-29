#!/usr/bin/env bash
# Prints the newest version of the VS Code extension on the Marketplace, or "none" when it is not there yet.
# Usage: scripts/published-extension-version.sh [--from-file reply.json] [extension-folder]
#
# --from-file reads the Marketplace's reply from a file instead of asking it (for tests). A failed request, or a reply this
# cannot read, is an error and never "none": taking a failed lookup for "nothing published" would upload a version twice.
set -euo pipefail

reply_file=""
if [[ "${1-}" == "--from-file" ]]; then
    reply_file="${2:?--from-file needs a file}"
    shift 2
fi
dir="${1:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../src/loom/vscode-loom" && pwd)}"
id="$(node -p "const p = JSON.parse(require('fs').readFileSync(process.argv[1], 'utf8')); p.publisher + '.' + p.name" "$dir/package.json")"

if [[ -n "$reply_file" ]]; then
    reply="$(cat "$reply_file")"
else
    query="{\"filters\":[{\"criteria\":[{\"filterType\":7,\"value\":\"$id\"}]}],\"flags\":914}"
    reply="$(curl --fail --silent --show-error --max-time 30 --retry 3 -X POST 'https://marketplace.visualstudio.com/_apis/public/gallery/extensionquery' \
        -H 'Content-Type: application/json' -H 'Accept: application/json;api-version=7.1-preview.1' -d "$query")"
fi

printf '%s' "$reply" | node -e '
let text = "";
process.stdin.on("data", (chunk) => (text += chunk));
process.stdin.on("end", () => {
    let found;
    try {
        found = JSON.parse(text).results[0].extensions;
    } catch (e) {
        console.error("The Marketplace reply could not be read as a list of extensions: " + e.message);
        process.exit(1);
    }
    if (!Array.isArray(found)) {
        console.error("The Marketplace reply has no list of extensions.");
        process.exit(1);
    }
    if (found.length === 0) {
        console.log("none");
        return;
    }
    const version = found[0].versions && found[0].versions[0] && found[0].versions[0].version;
    if (!/^[0-9]+\.[0-9]+\.[0-9]+$/.test(version)) {
        console.error("The Marketplace lists the extension with a version this cannot read: " + version);
        process.exit(1);
    }
    console.log(version);
});
'
