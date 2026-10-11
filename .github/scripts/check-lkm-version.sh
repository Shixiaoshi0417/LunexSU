#!/usr/bin/env bash
# Verify the compiled Core version before publishing a module artifact.
set -euo pipefail

module=${1:?Usage: check-lkm-version.sh MODULE}
repo=${GITHUB_WORKSPACE:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)}

if [[ $(git -C "$repo" rev-parse --is-shallow-repository) != false ]]; then
    echo "LKM version verification requires complete Git history" >&2
    exit 1
fi

count=$(git -C "$repo" rev-list --count HEAD)
if [[ ! $count =~ ^[1-9][0-9]*$ ]]; then
    echo "Invalid Git commit count: $count" >&2
    exit 1
fi

expected=$((30000 + count))
# Do not use grep -q: it can cause strings to fail with SIGPIPE under pipefail.
if ! LC_ALL=C strings -- "$module" | grep -F "welcome to LunexSU version $expected, package name " > /dev/null; then
    echo "LKM version mismatch: expected $expected from Git count $count ($module)" >&2
    exit 1
fi

echo "Verified LKM Core version $expected at $(git -C "$repo" rev-parse HEAD): $module"
