#!/bin/sh
# Point nginx at whatever DNS this container actually has.
#
# The resolver is what lets nginx notice that the API moved to a new address, which is
# what makes a rolling upgrade possible at all. It cannot be written into the config,
# because the answer differs by where the image runs: Docker Compose gives every
# container 127.0.0.11, and Kubernetes gives it the cluster DNS service, which has a
# different address in every cluster. Hardcoding Docker's broke the Helm install
# outright — nginx failed every lookup with "connection refused" and served 502 for
# everything, which is how this script came to exist.
#
# /etc/resolv.conf is the one place that is right in both.
set -eu

CONF=/etc/nginx/conf.d/default.conf
[ -f "$CONF" ] || exit 0
grep -q '__NGINX_RESOLVER__' "$CONF" || exit 0

RESOLVER=$(awk '/^nameserver[[:space:]]/ { print $2; exit }' /etc/resolv.conf 2>/dev/null || true)

if [ -z "${RESOLVER:-}" ]; then
    # No nameserver at all is not a situation nginx can proxy by name in, but failing
    # to start would take the dashboard down with it. Docker's is the better guess of
    # the two, and the error it produces names the real problem.
    RESOLVER=127.0.0.11
    echo "15-resolver: no nameserver in /etc/resolv.conf, falling back to ${RESOLVER}"
fi

# An IPv6 nameserver has to be bracketed in an nginx resolver directive.
case "$RESOLVER" in
    *:*) RESOLVER="[${RESOLVER}]" ;;
esac

sed -i "s|__NGINX_RESOLVER__|${RESOLVER}|g" "$CONF"
echo "15-resolver: nginx will resolve through ${RESOLVER}"
