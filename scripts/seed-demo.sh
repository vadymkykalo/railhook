#!/usr/bin/env bash
# The demo receivers have private addresses, so this needs WEBHOOK_ALLOW_PRIVATE_IPS=true: a
# development machine only. The API key is shown once, so each run replaces the previous one.
set -euo pipefail

RAILHOOK_URL="${RAILHOOK_URL:-http://localhost:8080}"
RAILHOOK_URL="${RAILHOOK_URL%/}"
RAILHOOK_EMAIL="${RAILHOOK_EMAIL:-demo@railhook.local}"
RAILHOOK_PASSWORD="${RAILHOOK_PASSWORD:-Demo12345!}"
SEED_PROJECT="${SEED_PROJECT:-Production}"
SEED_EVENTS="${SEED_EVENTS:-200}"
SEED_DURATION_SECONDS="${SEED_DURATION_SECONDS:-210}"
SEED_START_RECEIVERS="${SEED_START_RECEIVERS:-1}"

OK_CONTAINER="railhook-demo-ok"
FLAKY_CONTAINER="railhook-demo-flaky"
OK_ALIASES=(billing.northwind.internal analytics.northwind.internal hooks.northwind.internal)
FLAKY_ALIASES=(notify.northwind.internal warehouse.northwind.internal)
RECEIVER_IMAGE="python:3.12-alpine"

log() { printf '\033[1;34m==>\033[0m %s\n' "$*" >&2; }
die() { printf '\033[1;31merror:\033[0m %s\n' "$*" >&2; exit 1; }

command -v curl >/dev/null || die "curl is required"
command -v jq >/dev/null || die "jq is required (apt install jq / brew install jq)"

read -r -d '' OK_RECEIVER <<'PY' || true
import http.server, random, time
class H(http.server.BaseHTTPRequestHandler):
    def do_POST(self):
        self.rfile.read(int(self.headers.get("Content-Length") or 0))
        time.sleep(random.uniform(0.02, 0.25))
        body = b'{"received":true}'
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)
    def log_message(self, *a): pass
http.server.ThreadingHTTPServer(("", 80), H).serve_forever()
PY

read -r -d '' FLAKY_RECEIVER <<'PY' || true
import http.server, random, time
FAIL_RATE = {"/slack": 0.35, "/warehouse": 0.8}
class H(http.server.BaseHTTPRequestHandler):
    def do_POST(self):
        self.rfile.read(int(self.headers.get("Content-Length") or 0))
        time.sleep(random.uniform(0.05, 0.6))
        rate = next((r for p, r in FAIL_RATE.items() if self.path.startswith(p)), 0.5)
        if random.random() < rate:
            code, body = 503, b'{"error":"upstream unavailable"}'
        else:
            code, body = 200, b'{"ok":true}'
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)
    def log_message(self, *a): pass
http.server.ThreadingHTTPServer(("", 80), H).serve_forever()
PY

start_receiver() {
  local name=$1 code=$2 network=$3; shift 3
  if [ "$(docker inspect -f '{{.State.Running}}' "$name" 2>/dev/null || true)" = "true" ]; then
    log "receiver $name already running"
    return
  fi
  local alias_flags=()
  for a in "$@"; do alias_flags+=(--network-alias "$a"); done
  docker rm -f "$name" >/dev/null 2>&1 || true
  docker run -d --name "$name" --network "$network" "${alias_flags[@]}" --restart unless-stopped \
    "$RECEIVER_IMAGE" python -c "$code" >/dev/null
  log "started receiver $name on $network as $*"
}

if [ "$SEED_START_RECEIVERS" = "1" ]; then
  command -v docker >/dev/null || die "docker is required to start the receivers (or SEED_START_RECEIVERS=0)"
  network="${SEED_DOCKER_NETWORK:-$(docker network ls --format '{{.Name}}' | grep -E 'webhook-network$' | head -n1 || true)}"
  [ -n "$network" ] || die "no Docker network ending in webhook-network; is the stack up? (or set SEED_DOCKER_NETWORK)"
  start_receiver "$OK_CONTAINER" "$OK_RECEIVER" "$network" "${OK_ALIASES[@]}"
  start_receiver "$FLAKY_CONTAINER" "$FLAKY_RECEIVER" "$network" "${FLAKY_ALIASES[@]}"
fi

TOKEN=""

# api METHOD PATH [JSON_BODY] — bearer-authenticated; prints the body, dies on non-2xx.
api() {
  local method=$1 path=$2 body=${3:-} out status
  out=$(mktemp)
  status=$(curl -sS -o "$out" -w '%{http_code}' -X "$method" "$RAILHOOK_URL$path" \
    -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
    ${body:+--data "$body"})
  if [ "${status:0:1}" != "2" ]; then
    printf '%s %s -> HTTP %s\n' "$method" "$path" "$status" >&2
    cat "$out" >&2; echo >&2
    rm -f "$out"
    exit 1
  fi
  cat "$out"
  rm -f "$out"
}

log "signing in to $RAILHOOK_URL as $RAILHOOK_EMAIL"
TOKEN=$(curl -sS -X POST "$RAILHOOK_URL/api/v1/auth/login" -H 'Content-Type: application/json' \
  --data "$(jq -n --arg e "$RAILHOOK_EMAIL" --arg p "$RAILHOOK_PASSWORD" '{email:$e,password:$p}')" \
  | jq -r '.accessToken // empty')
[ -n "$TOKEN" ] || die "login failed for $RAILHOOK_EMAIL"

PROJECT_ID=$(api GET /api/v1/projects | jq -r --arg n "$SEED_PROJECT" '[.[] | select(.name == $n)][0].id // empty')
if [ -z "$PROJECT_ID" ]; then
  PROJECT_ID=$(api POST /api/v1/projects "$(jq -n --arg n "$SEED_PROJECT" \
    '{name:$n, description:"Customer-facing webhooks for the Northwind storefront"}')" | jq -r .id)
  log "created project $SEED_PROJECT ($PROJECT_ID)"
else
  log "reusing project $SEED_PROJECT ($PROJECT_ID)"
fi
P="/api/v1/projects/$PROJECT_ID"

for old in $(api GET "$P/api-keys?size=100" \
    | jq -r '.content[] | select(.name == "Checkout service" and .revokedAt == null) | .id'); do
  api DELETE "$P/api-keys/$old" >/dev/null
done
API_KEY=$(api POST "$P/api-keys" '{"name":"Checkout service","scope":"READ_WRITE"}' | jq -r .key)
[ -n "$API_KEY" ] && [ "$API_KEY" != "null" ] || die "API key creation returned no key"
log "created API key Checkout service"

ENDPOINTS_JSON=$(api GET "$P/endpoints?size=100")
SUBSCRIPTIONS_JSON=$(api GET "$P/subscriptions")

# ensure_endpoint DESCRIPTION URL RATE_LIMIT -> prints endpoint id
ensure_endpoint() {
  local description=$1 url=$2 id
  id=$(jq -r --arg d "$description" '[.content[] | select(.description == $d)][0].id // empty' <<<"$ENDPOINTS_JSON")
  if [ -z "$id" ]; then
    id=$(api POST "$P/endpoints" "$(jq -n --arg u "$url" --arg d "$description" \
      '{url:$u, description:$d, enabled:true, signatureScheme:"STANDARD"}')" | jq -r .id)
    log "created endpoint $description -> $url"
  fi
  echo "$id"
}

# ensure_subscription ENDPOINT_ID EVENT_TYPE [MAX_ATTEMPTS RETRY_DELAYS]
ensure_subscription() {
  local endpoint_id=$1 event_type=$2 max_attempts=${3:-} delays=${4:-}
  if jq -e --arg e "$endpoint_id" --arg t "$event_type" \
      'any(.[]; .endpointId == $e and .eventType == $t)' <<<"$SUBSCRIPTIONS_JSON" >/dev/null; then
    return
  fi
  api POST "$P/subscriptions" "$(jq -n --arg e "$endpoint_id" --arg t "$event_type" \
    --arg m "$max_attempts" --arg d "$delays" \
    '{endpointId:$e, eventType:$t, enabled:true}
     + (if $m != "" then {maxAttempts:($m|tonumber)} else {} end)
     + (if $d != "" then {retryDelays:$d} else {} end)')" >/dev/null
  log "  subscribed to $event_type"
}

BILLING=$(ensure_endpoint "Billing service" "http://billing.northwind.internal/webhooks/railhook")
for t in invoice.created invoice.paid payment.failed; do ensure_subscription "$BILLING" "$t"; done

ANALYTICS=$(ensure_endpoint "Analytics pipeline" "http://analytics.northwind.internal/ingest")
for t in "order.*" user.signup; do ensure_subscription "$ANALYTICS" "$t"; done

SLACK=$(ensure_endpoint "Slack notifier" "http://notify.northwind.internal/slack/railhook")
for t in payment.failed user.signup order.shipped; do ensure_subscription "$SLACK" "$t"; done

WAREHOUSE=$(ensure_endpoint "Legacy warehouse" "http://warehouse.northwind.internal/warehouse/v1/orders")
# A short ladder, so a delivery the warehouse keeps refusing reaches Failed Messages
# inside the seed run instead of a day later.
for t in order.created order.shipped; do ensure_subscription "$WAREHOUSE" "$t" 3 "15,30"; done

SOURCES_JSON=$(api GET "$P/incoming-sources?size=100")

# ensure_source NAME SLUG PROVIDER DESTINATION_URL -> prints ingress token
ensure_source() {
  local name=$1 slug=$2 provider=$3 dest_url=$4 source token
  source=$(jq -c --arg s "$slug" '[.content[] | select(.slug == $s)][0] // empty' <<<"$SOURCES_JSON")
  if [ -z "$source" ]; then
    source=$(api POST "$P/incoming-sources" "$(jq -n --arg n "$name" --arg s "$slug" --arg p "$provider" \
      '{name:$n, slug:$s, providerType:$p, status:"ACTIVE", verificationMode:"NONE"}')")
    log "created incoming source $name"
  fi
  local source_id
  source_id=$(jq -r .id <<<"$source")
  token=$(jq -r .ingressPathToken <<<"$source")
  if ! api GET "$P/incoming-sources/$source_id/destinations?size=100" \
      | jq -e --arg u "$dest_url" 'any(.content[]; .url == $u)' >/dev/null; then
    api POST "$P/incoming-sources/$source_id/destinations" "$(jq -n --arg u "$dest_url" \
      '{url:$u, authType:"NONE", enabled:true}')" >/dev/null
    log "  destination $dest_url"
  fi
  echo "$token"
}

STRIPE_TOKEN=$(ensure_source "Stripe" "stripe-live" STRIPE "http://billing.northwind.internal/stripe/events")
GITHUB_TOKEN=$(ensure_source "GitHub" "github-northwind" GITHUB "http://hooks.northwind.internal/deploys/github")

ingress() {
  local token=$1 body=$2; shift 2
  curl -sS -o /dev/null -X POST "$RAILHOOK_URL/ingress/$token" \
    -H 'Content-Type: application/json' "$@" --data "$body" || true
}

log "posting incoming events"
for i in 1 2 3 4 5 6; do
  amount=$(( (RANDOM % 900 + 20) * 100 ))
  type=$([ $((i % 3)) -eq 0 ] && echo invoice.payment_succeeded || echo charge.succeeded)
  ingress "$STRIPE_TOKEN" "$(jq -nc --arg t "$type" --argjson a "$amount" --arg id "evt_3Q${RANDOM}${RANDOM}" \
    '{id:$id, object:"event", type:$t, livemode:true, created:(now|floor),
      data:{object:{id:("ch_" + ($id|ltrimstr("evt_"))), amount:$a, currency:"usd", status:"succeeded"}}}')" \
    -H 'Stripe-Signature: t=0,v1=demo'
done
for i in 1 2 3 4; do
  ingress "$GITHUB_TOKEN" "$(jq -nc --arg sha "$(printf '%08x%08x' $RANDOM$RANDOM $RANDOM$RANDOM)" \
    '{ref:"refs/heads/main", after:$sha, repository:{full_name:"northwind/storefront"},
      pusher:{name:"release-bot"}, commits:[{id:$sha, message:"Deploy storefront"}]}')" \
    -H 'X-GitHub-Event: push' -H "X-GitHub-Delivery: demo-$RANDOM-$i"
done

[ "$SEED_EVENTS" -gt 0 ] || { log "SEED_EVENTS=0, setup done"; exit 0; }

CUSTOMERS=(acme-corp globex initech umbrella hooli stark-industries wayne-enterprises soylent tyrell vandelay)
PLANS=(starter growth business enterprise)

event_body() {
  local type=$1 n=$2 customer=${CUSTOMERS[$((RANDOM % ${#CUSTOMERS[@]}))]}
  local order="ord_$((48100 + n))" amount=$(( (RANDOM % 4800 + 120) * 100 / 100 ))
  case $type in
    order.created)
      jq -nc --arg t "$type" --arg o "$order" --arg c "$customer" --argjson a "$amount" --argjson q $((RANDOM % 5 + 1)) \
        '{type:$t, data:{order_id:$o, customer:$c, items:$q, total:$a, currency:"USD"}}' ;;
    order.shipped)
      jq -nc --arg t "$type" --arg o "$order" --arg c "$customer" --arg tr "1Z999AA1$RANDOM$RANDOM" \
        '{type:$t, data:{order_id:$o, customer:$c, carrier:"UPS", tracking_number:$tr}}' ;;
    invoice.created | invoice.paid)
      jq -nc --arg t "$type" --arg i "in_$((20250 + n))" --arg c "$customer" --argjson a "$amount" \
        '{type:$t, data:{invoice_id:$i, customer:$c, amount_due:$a, currency:"USD"}}' ;;
    payment.failed)
      jq -nc --arg t "$type" --arg c "$customer" --argjson a "$amount" \
        '{type:$t, data:{customer:$c, amount:$a, currency:"USD", decline_code:"insufficient_funds", attempt:1}}' ;;
    user.signup)
      jq -nc --arg t "$type" --arg u "usr_$RANDOM$n" --arg c "$customer" --arg p "${PLANS[$((RANDOM % 4))]}" \
        '{type:$t, data:{user_id:$u, organization:$c, plan:$p, source:"web"}}' ;;
  esac
}

pick_type() {
  local r=$((RANDOM % 100))
  if   [ $r -lt 30 ]; then echo order.created
  elif [ $r -lt 48 ]; then echo invoice.paid
  elif [ $r -lt 62 ]; then echo order.shipped
  elif [ $r -lt 76 ]; then echo user.signup
  elif [ $r -lt 90 ]; then echo invoice.created
  else echo payment.failed
  fi
}

log "ingesting $SEED_EVENTS events over ~${SEED_DURATION_SECONDS}s"
base_ms=$(( SEED_DURATION_SECONDS * 1000 / SEED_EVENTS ))
statuses=$(mktemp)
trap 'rm -f "$statuses"' EXIT
started=$SECONDS
for n in $(seq 1 "$SEED_EVENTS"); do
  # Posted in the background so a slow response does not stretch the pacing; each
  # status lands on its own line and is counted after the last one returns.
  body=$(event_body "$(pick_type)" "$n")
  { curl -sS -o /dev/null -w '%{http_code}\n' -X POST "$RAILHOOK_URL/api/v1/events" \
      -H "X-API-Key: $API_KEY" -H 'Content-Type: application/json' \
      -H "Idempotency-Key: seed-$started-$n-$RANDOM" --data "$body" || echo 000; } >>"$statuses" &
  # Uneven pacing: every fourth block of ten events is a quick burst, the rest drift.
  if [ $(( (n / 10) % 4 )) -eq 3 ]; then
    delay_ms=$(( base_ms / 4 ))
  else
    delay_ms=$(( base_ms * (70 + RANDOM % 90) / 100 ))
  fi
  sleep "$(printf '%d.%03d' $((delay_ms / 1000)) $((delay_ms % 1000)))"
  if [ $((n % 50)) -eq 0 ]; then log "  $n/$SEED_EVENTS sent"; fi
done

wait
failed=$(grep -cv '^201$' "$statuses" || true)
log "done in $((SECONDS - started))s: $SEED_EVENTS events, $failed rejected, project $SEED_PROJECT ($PROJECT_ID)"
[ "$failed" -eq 0 ] || die "$failed events were not accepted"
