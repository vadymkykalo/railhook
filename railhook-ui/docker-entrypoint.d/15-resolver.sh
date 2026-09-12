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
