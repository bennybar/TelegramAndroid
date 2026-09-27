#!/usr/bin/env bash
# Rebase my-features onto the latest forkgram dev.
# On conflict: fix files, `git add` them, `git rebase --continue` (rerere remembers resolutions).
set -euo pipefail

BRANCH=my-features
UPSTREAM=upstream/dev

cd "$(git rev-parse --show-toplevel)"

if [ -n "$(git status --porcelain)" ]; then
    echo "Working tree not clean; commit or stash first." >&2
    exit 1
fi

git config rerere.enabled true
git fetch upstream
git checkout "$BRANCH"
git rebase "$UPSTREAM"
git submodule update --init --recursive --depth 1
git push --force-with-lease origin "$BRANCH"

echo "Done. My commits on top of $UPSTREAM:"
git log --oneline "$UPSTREAM..HEAD"
