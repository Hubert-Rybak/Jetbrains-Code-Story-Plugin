#!/usr/bin/env bash
# Creates the payments demo project in DIR: a Git repository with a story of every step kind
# in .codestory/local/. The sandbox IDE plays the story when it opens the project.
#
#   tools/ide-sandbox/make-demo.sh DIR
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
dir="${1:?Usage: make-demo.sh DIR}"
if [ -e "$dir" ]; then
    echo "$dir exists. Remove it, or choose another directory." >&2
    exit 1
fi

mkdir -p "$dir"
cp -R "$here/demo/." "$dir/"
git -C "$dir" init -q -b main
git -C "$dir" add .
git -C "$dir" -c user.name=Demo -c user.email=demo@example.com commit -q -m "Payments demo"
mkdir -p "$dir/.codestory/local"
cp "$here/demo-story.json" "$dir/.codestory/local/2026-10-23-how-a-payment-is-charged.story.json"
echo "Created the demo project in $dir"
