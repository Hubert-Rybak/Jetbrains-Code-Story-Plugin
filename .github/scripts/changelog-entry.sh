#!/bin/sh
# Prints the CHANGELOG.md entry of one version without its heading, for the release notes.
# Fails when CHANGELOG.md has no entry for the version.
set -eu
version="$1"
notes=$(awk -v heading="## [$version]" '
    index($0, heading) == 1 { found = 1; next }
    found && /^## / { exit }
    found && /^\[[^]]+\]: / { next }
    found && !started && /^[[:space:]]*$/ { next }
    found { started = 1; print }
' CHANGELOG.md)
if [ -z "$notes" ]; then
    echo "CHANGELOG.md has no entry for $version." >&2
    exit 1
fi
printf '%s\n' "$notes"
