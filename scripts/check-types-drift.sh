#!/usr/bin/env bash
set -euo pipefail

# Fails when railhook-ui/src/types/api.generated.ts is not what
# openapi.yaml currently generates.
#
# openapi.yaml is itself kept honest against springdoc by
# OpenApiDriftIntegrationTest, so it is a trustworthy description of the
# backend without a running server. This extends that guarantee to the
# frontend: the generated types are committed, and any change to a DTO has to
# show up in the diff of this file rather than surfacing as a runtime
# `undefined` in the browser.
#
# The generated file is not what the app imports — springdoc marks nothing
# `required`, so every generated property is optional and consuming them
# directly would put a null check on every field read. src/types/api.contract.ts
# is what closes that gap: it asserts, at compile time, that the hand-written
# mirror in api.types.ts still matches these schemas. So this script keeps the
# schemas current and `npm run typecheck` keeps the mirror honest against them.
#
# Usage: scripts/check-types-drift.sh
# Regenerate with: cd railhook-ui && npm run types:generate

cd "$(git rev-parse --show-toplevel)"

GENERATED="railhook-ui/src/types/api.generated.ts"

if [ ! -f "$GENERATED" ]; then
    echo "::error::$GENERATED is missing. Run: cd railhook-ui && npm run types:generate"
    exit 1
fi

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

(cd railhook-ui && npx --no-install openapi-typescript ../openapi.yaml -o "$TMP/api.generated.ts" >/dev/null)

if ! diff -u "$GENERATED" "$TMP/api.generated.ts" > "$TMP/drift.diff"; then
    echo "::error::$GENERATED is stale — openapi.yaml generates something else."
    echo ""
    echo "A backend DTO changed and the frontend's copy of the schema did not."
    echo "Regenerate and commit the result:"
    echo ""
    echo "  cd railhook-ui && npm run types:generate"
    echo ""
    echo "Then fix whatever src/types/api.contract.ts now reports: the mirror in"
    echo "api.types.ts is what the app actually imports, and it has to keep matching."
    echo ""
    head -100 "$TMP/drift.diff"
    exit 1
fi

echo "UI types OK: api.generated.ts matches openapi.yaml."
