#!/bin/sh
# Write the settings that belong to this container rather than to the image.
#
# Vite inlines VITE_* when the bundle is compiled, and the published image is compiled
# once for every deployment of it: the hosted cloud and each self-hosted install run the
# same bytes. A value that must differ between them — the domain behind the sales@ and
# support@ addresses — therefore cannot live in the bundle. It is written here, as
# window.__RAILHOOK__, and nginx serves the file as /config.js ahead of the app.
#
# /tmp for the same reason as the resolver file: the chart runs this pod with a
# read-only root filesystem and mounts /tmp writable.
#
# The file is always written, with an empty domain when none is set, so /config.js never
# 404s and the page never has to tell a missing config from an empty one.
set -eu

OUT="${RAILHOOK_RUNTIME_CONFIG_OUT:-/tmp/railhook-config.js}"

domain=$(printf '%s' "${RAILHOOK_CONTACT_DOMAIN:-}" | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//')

# A hostname, and nothing that could close the string it is written into. A bad value
# is dropped with a warning rather than failing the container: a typo in a contact
# address is not a reason to take the dashboard down.
case "$domain" in
    *[!A-Za-z0-9.-]*)
        echo "20-runtime-config: RAILHOOK_CONTACT_DOMAIN is not a hostname (letters, digits, dots and hyphens only); offering no contact addresses" >&2
        domain=""
        ;;
esac

# JSON string escaping. The check above already excludes both characters; this keeps the
# output valid if that check is ever widened.
domain_json=$(printf '%s' "$domain" | sed -e 's/\\/\\\\/g' -e 's/"/\\"/g')

cat > "$OUT" <<CONF
window.__RAILHOOK__ = {"contactDomain": "${domain_json}"};
CONF

if [ -n "$domain" ]; then
    echo "20-runtime-config: contact addresses on ${domain}"
else
    echo "20-runtime-config: no contact domain, contact addresses hidden"
fi
