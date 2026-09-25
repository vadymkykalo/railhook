#!/usr/bin/env bash
# Usage: ci-integration-shard.sh <shard> <total> | --list <total>
set -euo pipefail

cd "$(git rev-parse --show-toplevel)"

usage() {
    echo "usage: $0 <shard> <total> | --list <total>" >&2
    exit 2
}

[ $# -eq 2 ] || usage
total=$2
[[ "$total" =~ ^[1-9][0-9]*$ ]] || usage

# git ls-files, not find: worktrees under .claude/ hold copies of every test.
classes=$(git ls-files --cached --others --exclude-standard -- '*/src/test/java/*.java' \
    | grep -E '(IntegrationTest|IT|RepositoryTest|ConcurrencyTest|RbacTest|IsolationTest)\.java$' \
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

# An empty -Dtest makes surefire run every test, unit tests included.
[ -n "$selected" ] || { echo "shard $shard of $total is empty" >&2; exit 1; }

echo "$selected" | paste -sd, -
