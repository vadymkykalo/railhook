#!/bin/sh
# /etc/resolv.conf is right under both Compose and Kubernetes; /tmp because the root fs is read-only.
set -eu

OUT=/tmp/railhook-resolver.conf
RESOLVER=$(awk '/^nameserver[[:space:]]/ { print $2; exit }' /etc/resolv.conf 2>/dev/null || true)

if [ -z "${RESOLVER:-}" ]; then
    # Guess Docker's rather than refuse to start and take the dashboard down.
    RESOLVER=127.0.0.11
    echo "15-resolver: no nameserver in /etc/resolv.conf, falling back to ${RESOLVER}"
fi

# An IPv6 nameserver has to be bracketed in an nginx resolver directive.
case "$RESOLVER" in
    *:*) RESOLVER="[${RESOLVER}]" ;;
esac

# valid=5s: Docker publishes a 600s TTL.
cat > "$OUT" <<CONF
resolver ${RESOLVER} valid=5s ipv6=off;
resolver_timeout 3s;
CONF

echo "15-resolver: nginx will resolve through ${RESOLVER}"

# nginx's resolver applies no search list, so the upstream is qualified here through libc.
# Qualified candidate first: getent applies the search list itself, so the bare name would always win.
API_HOST="${RAILHOOK_API_HOST:-api}"
case "$API_HOST" in
    *.*) ;;
    *)
        for suffix in $(awk '/^search[[:space:]]/ { $1 = ""; print; exit }' /etc/resolv.conf 2>/dev/null); do
            if getent hosts "${API_HOST}.${suffix}" >/dev/null 2>&1; then
                API_HOST="${API_HOST}.${suffix}"
                break
            fi
        done
        ;;
esac

# After nginx.conf's defaults so it overrides them.
cat >> "$OUT" <<CONF
set \$api_backend  "${API_HOST}:8080";
set \$api_actuator "${API_HOST}:8082";
CONF

echo "15-resolver: API upstream is ${API_HOST}"
