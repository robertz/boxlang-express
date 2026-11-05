#!/usr/bin/env bash
# Creates the GitHub release for the newest tag, using that version's CHANGELOG.md section as notes.
set -euo pipefail
cd "$(dirname "$0")/.."

tag="$(git describe --tags --abbrev=0)"
version="${tag#v}"

notes="$(awk -v v="**${version}**" '
    $0 == v { f = 1; next }
    /^\*\*[0-9]+\.[0-9]+\.[0-9]+\*\*$/ { f = 0 }
    f
' CHANGELOG.md)"

if [ -z "${notes//[[:space:]]/}" ]; then
    echo "No CHANGELOG.md section for ${version}" >&2
    exit 1
fi

if gh release view "$tag" >/dev/null 2>&1; then
    echo "Release ${tag} already exists"
    exit 0
fi

gh release create "$tag" --title "$tag" --verify-tag --notes "$notes"
