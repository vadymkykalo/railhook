#!/usr/bin/env bash
# Needs BASE_URL, ACCESS_TOKEN, PROJECT_ID, RECEIVER_INTERNAL_URL (public URL of the receiver, e.g.
# a `railhook listen 9000` tunnel) and RECEIVER_CONTROL_URL.
set -uo pipefail

: "${BASE_URL:?}" "${ACCESS_TOKEN:?}" "${PROJECT_ID:?}" "${RECEIVER_INTERNAL_URL:?}"
RECEIVER_CONTROL_URL="${RECEIVER_CONTROL_URL:-http://localhost:9000}"
WAIT_SECONDS="${WAIT_SECONDS:-30}"

auth=(-H "Authorization: Bearer ${ACCESS_TOKEN}" -H 'Content-Type: application/json')
api="${BASE_URL}/api/v1/projects/${PROJECT_ID}"
suffix="$(date +%s)"
failures=0

pass() { echo "PASS  $1"; }
fail() { echo "FAIL  $1"; failures=$((failures + 1)); }

post() { curl -s -m 20 -w '\n%{http_code}' -X POST "$@"; }
body_of() { sed '$d' <<<"$1"; }
code_of() { tail -n1 <<<"$1"; }

received_seq_ok() {
  curl -s "${RECEIVER_CONTROL_URL}/_control/received" \
    | jq --argjson s "$1" '[.[] | select(.seq == $s and .status >= 200 and .status < 300)] | length'
}

wait_for_seq() {
  local seq=$1 deadline=$((SECONDS + WAIT_SECONDS))
  while [ $SECONDS -lt $deadline ]; do
    [ "$(received_seq_ok "$seq")" -ge 1 ] && return 0
    sleep 1
  done
  return 1
}

curl -s -X POST "${RECEIVER_CONTROL_URL}/_control/reset" >/dev/null

r=$(post "${auth[@]}" "${api}/api-keys" -d "{\"name\":\"smoke-${suffix}\",\"scope\":\"READ_WRITE\"}")
[ "$(code_of "$r")" = 201 ] || { echo "setup: api key $(code_of "$r") $(body_of "$r")"; exit 2; }
api_key=$(body_of "$r" | jq -r .key)

r=$(post "${auth[@]}" "${api}/endpoints" \
  -d "{\"url\":\"${RECEIVER_INTERNAL_URL}/webhook?smoke=out\",\"description\":\"smoke ${suffix}\",\"enabled\":true}")
[ "$(code_of "$r")" = 201 ] || { echo "setup: endpoint $(code_of "$r") $(body_of "$r")"; exit 2; }
endpoint_id=$(body_of "$r" | jq -r .id)
echo "endpoint verificationStatus: $(body_of "$r" | jq -r .verificationStatus)"

r=$(post "${auth[@]}" "${api}/subscriptions" \
  -d "{\"endpointId\":\"${endpoint_id}\",\"eventType\":\"smoke.test\",\"enabled\":true}")
[ "$(code_of "$r")" = 201 ] || { echo "setup: subscription $(code_of "$r") $(body_of "$r")"; exit 2; }

seq=$((suffix * 1000 + 1))
event="{\"type\":\"smoke.test\",\"data\":{\"seq\":${seq},\"sentAtMs\":$(date +%s%3N)}}"
r=$(post -H "X-API-Key: ${api_key}" -H 'Content-Type: application/json' -H "Idempotency-Key: smoke-${suffix}" \
  "${BASE_URL}/api/v1/events" -d "$event")
echo "event: HTTP $(code_of "$r") $(body_of "$r" | head -c 200)"
if wait_for_seq "$seq"; then pass "outgoing event delivered"; else fail "outgoing event not delivered in ${WAIT_SECONDS}s"; fi

r=$(post -H "X-API-Key: ${api_key}" -H 'Content-Type: application/json' -H "Idempotency-Key: smoke-${suffix}" \
  "${BASE_URL}/api/v1/events" -d "$event")
echo "duplicate: HTTP $(code_of "$r") deliveriesCreated=$(body_of "$r" | jq -r '.deliveriesCreated // "?"')"
sleep 10
n=$(received_seq_ok "$seq")
if [ "$n" = 1 ]; then pass "idempotent resend delivered once"; else fail "idempotent resend: seq answered 2xx ${n} times"; fi

secret="smoke-secret-${suffix}"
r=$(post "${auth[@]}" "${api}/incoming-sources" \
  -d "{\"name\":\"smoke-${suffix}\",\"providerType\":\"GENERIC\",\"verificationMode\":\"HMAC_GENERIC\",\"hmacSecret\":\"${secret}\",\"hmacHeaderName\":\"X-Signature\"}")
[ "$(code_of "$r")" = 201 ] || { echo "setup: source $(code_of "$r") $(body_of "$r")"; exit 2; }
source_id=$(body_of "$r" | jq -r .id)
ingress_url=$(body_of "$r" | jq -r .ingressUrl)
case "$ingress_url" in http*) ;; *) ingress_url="${BASE_URL}/ingress/$(body_of "$r" | jq -r .ingressPathToken)";; esac

r=$(post "${auth[@]}" "${api}/incoming-sources/${source_id}/destinations" \
  -d "{\"url\":\"${RECEIVER_INTERNAL_URL}/webhook?smoke=in\",\"enabled\":true}")
[ "$(code_of "$r")" = 201 ] || { echo "setup: destination $(code_of "$r") $(body_of "$r")"; exit 2; }

seq=$((suffix * 1000 + 2))
payload="{\"type\":\"smoke.incoming\",\"data\":{\"seq\":${seq},\"sentAtMs\":$(date +%s%3N)}}"
sig=$(printf '%s' "$payload" | openssl dgst -sha256 -hmac "$secret" -hex | sed 's/^.* //')
r=$(post -H 'Content-Type: application/json' -H "X-Signature: ${sig}" "$ingress_url" -d "$payload")
echo "ingress: HTTP $(code_of "$r") $(body_of "$r" | head -c 200)"
if wait_for_seq "$seq"; then pass "incoming webhook forwarded"; else fail "incoming webhook not forwarded in ${WAIT_SECONDS}s"; fi

r=$(post -H 'Content-Type: application/json' -H "X-Signature: deadbeef" "$ingress_url" \
  -d "{\"type\":\"smoke.incoming\",\"data\":{\"seq\":$((suffix * 1000 + 3))}}")
if [ "$(code_of "$r")" = 401 ]; then pass "wrong signature refused (401)"; else fail "wrong signature: HTTP $(code_of "$r")"; fi

r=$(post "${auth[@]}" "${api}/test-endpoints" -d "{\"name\":\"smoke-${suffix}\",\"ttlHours\":1}")
case "$(code_of "$r")" in 200|201) ;; *) false;; esac || { echo "setup: test endpoint $(code_of "$r") $(body_of "$r")"; exit 2; }
te_id=$(body_of "$r" | jq -r .id)
te_url=$(body_of "$r" | jq -r .url)
case "$te_url" in http*) ;; *) te_url="${BASE_URL}${te_url}";; esac
post -H 'Content-Type: application/json' "$te_url" -d "{\"smoke\":\"body-${suffix}\"}" >/dev/null
sleep 2
captured=$(curl -s -m 20 "${auth[@]}" "${api}/test-endpoints/${te_id}/requests" | jq -r '[.. | .body? // empty] | first // ""')
if grep -q "body-${suffix}" <<<"$captured"; then pass "test endpoint captured the body"; else fail "test endpoint body not captured (got: '${captured:0:80}')"; fi

echo "---"
echo "failures: ${failures}"
exit "$failures"
