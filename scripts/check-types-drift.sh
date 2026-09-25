#!/usr/bin/env bash
set -euo pipefail

# Fails when railhook-ui/src/types/api.generated.ts is not what openapi.yaml currently generates.
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
