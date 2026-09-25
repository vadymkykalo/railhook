#!/usr/bin/env bash
set -euo pipefail

# Prints the integration test classes one CI shard runs, as a surefire -Dtest value.
#
# The integration suite is split across parallel jobs in .github/workflows/ci.yml.
# The classes are found on disk by the same suffixes check-test-routing.sh routes
# by, so a new test lands in a shard without anyone listing it. They are sorted by
# fully-qualified name and dealt out round-robin, which keeps the class counts
# within one of each other and the assignment stable between runs.
#
# Usage: scripts/ci-integration-shard.sh <shard 1..N> <N>
#        scripts/ci-integration-shard.sh --list <N>   every class with its shard

cd "$(git rev-parse --show-toplevel)"

INTEGRATION_SUFFIXES='(IntegrationTest|IT|RepositoryTest|ConcurrencyTest|RbacTest|IsolationTest)\.java$'

usage() {
    echo "usage: $0 <shard> <total> | --list <total>" >&2
    exit 2
}

[ $# -eq 2 ] || usage
total=$2
[[ "$total" =~ ^[1-9][0-9]*$ ]] || usage

# Paths relative to src/test/java without .java, e.g. com/webhook/platform/api/FooIT:
# surefire matches that form exactly, and it runs a class's @Nested classes with it.
# git ls-files rather than find: a checkout with worktrees under .claude/ would
# otherwise count every other worktree's copy of each test too.
classes=$(git ls-files --cached --others --exclude-standard -- '*/src/test/java/*.java' \
    | grep -E "$INTEGRATION_SUFFIXES" \
    | sed -E 's|^.*/src/test/java/||; s|\.java$||' \
    | LC_ALL=C sort -u)

[ -n "$classes" ] || { echo "no integration test classes found" >&2; exit 1; }

if [ "$1" = "--list" ]; then
    echo "$classes" | awk -v n="$total" '{ print (NR - 1) % n + 1, $0 }'
    exit 0
fi

shard=$1
[[ "$shard" =~ ^[1-9][0-9]*$ ]] && [ "$shard" -le "$total" ] || usage

selected=$(echo "$classes" | awk -v n="$total" -v s="$shard" '(NR - 1) % n + 1 == s')

# An empty -Dtest makes surefire run every test in the build, unit tests included.
[ -n "$selected" ] || { echo "shard $shard of $total has no classes; lower the shard count" >&2; exit 1; }

echo "$selected" | paste -sd, -
