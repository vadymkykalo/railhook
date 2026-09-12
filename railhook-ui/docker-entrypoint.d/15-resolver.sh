#!/bin/sh
# Point nginx at whatever DNS this container actually has.
#
# The resolver is what lets nginx notice that the API moved to a new address, which is
# what makes a rolling upgrade possible at all. It cannot be written into the config,
# because the answer differs by where the image runs: Compose gives every container
# 127.0.0.11, Kubernetes gives it the cluster DNS service at an address that differs per
# cluster. Hardcoding Docker's broke the Helm install outright.
#
# Written to /tmp and included, rather than patched into the config in place: the chart
# runs this pod with a read-only root filesystem, so editing /etc/nginx/conf.d fails with
# "Permission denied" and nginx then starts on a file it cannot parse. /tmp is writable
# under both, because the chart mounts it.
#
# /etc/resolv.conf is the one place that is right everywhere.
set -eu

OUT=/tmp/railhook-resolver.conf
RESOLVER=$(awk '/^nameserver[[:space:]]/ { print $2; exit }' /etc/resolv.conf 2>/dev/null || true)

if [ -z "${RESOLVER:-}" ]; then
    # No nameserver at all is not something nginx can proxy by name in, but refusing to
    # start would take the dashboard down with it. Docker's is the better guess of the
    # two, and the error it then produces names the real problem.
    RESOLVER=127.0.0.11
    echo "15-resolver: no nameserver in /etc/resolv.conf, falling back to ${RESOLVER}"
fi

# An IPv6 nameserver has to be bracketed in an nginx resolver directive.
case "$RESOLVER" in
    *:*) RESOLVER="[${RESOLVER}]" ;;
esac

# valid=5s rather than nginx's default of the record's own TTL: Docker publishes 600s,
# which would put the cached-forever behaviour back with extra steps.
cat > "$OUT" <<CONF
resolver ${RESOLVER} valid=5s ipv6=off;
resolver_timeout 3s;
CONF

echo "15-resolver: nginx will resolve through ${RESOLVER}"

# The upstream name, which is the other half of the same problem.
#
# nginx's resolver speaks DNS itself, and a raw query carries no search list. A literal
# proxy_pass did not care: it resolves once, through libc, which does apply `search`. So
# `api` worked under Kubernetes right up until the upstream became a variable, and then
# every proxied path served 502 while nginx itself was healthy — the chart supplies a
# Service called `api`, but it is only reachable as api.<namespace>.svc.cluster.local.
#
# Resolved here, once, through libc — the same thing the literal used to do — and handed
# to nginx as a name its own resolver can answer. Under Compose nothing changes: Docker's
# embedded DNS answers the bare name, so the first probe succeeds and that is what is
# written.
# The qualified candidate is tried first, and that order is the whole point. Probing the
# bare name and only falling back to a suffix looks right and does nothing: libc applies
# the search list itself, so `getent hosts api` succeeds inside the pod and the bare name
# — the one nginx cannot resolve — is what gets written. Under Compose no suffix matches
# and the bare name is kept, which is what Docker's embedded DNS answers.
API_HOST="${RAILHOOK_API_HOST:-api}"
case "$API_HOST" in
    *.*) ;;  # already qualified
    *)
        for suffix in $(awk '/^search[[:space:]]/ { $1 = ""; print; exit }' /etc/resolv.conf 2>/dev/null); do
            if getent hosts "${API_HOST}.${suffix}" >/dev/null 2>&1; then
                API_HOST="${API_HOST}.${suffix}"
                break
            fi
        done
        ;;
esac

# Overrides the defaults in nginx.conf, which is why the include sits after them. If the
# name never resolved, this writes it back unchanged and nginx behaves as it did before.
cat >> "$OUT" <<CONF
set \$api_backend  "${API_HOST}:8080";
set \$api_actuator "${API_HOST}:8082";
CONF

echo "15-resolver: API upstream is ${API_HOST}"
