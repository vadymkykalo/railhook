#!/bin/sh
# Writes the uptime probe targets Prometheus reads through file_sd, at container start.
#
# Prometheus does not expand environment variables in its config, and the URLs differ per
# installation, so they are rendered here (the image is busybox: POSIX sh only).
#
#   MONITORING_PROBE_URLS  comma- or space-separated URLs to probe from outside, e.g.
#                          https://example.com/,https://example.com/docs/
#   RAILHOOK_DOMAIN        when the list is empty, the site root, /docs/ and
#                          /actuator/health on this domain
#
# Inside the network it always probes the UI, and Caddy when there is a domain (Caddy runs
# only under the tls profile, which a domain turns on).
set -eu

SD="${PROMETHEUS_SD_DIR:-/prometheus/sd}"
mkdir -p "$SD"

urls=$(printf '%s' "${MONITORING_PROBE_URLS:-}" | tr ',' ' ')
if [ -z "$(printf '%s' "$urls" | tr -d ' ')" ] && [ -n "${RAILHOOK_DOMAIN:-}" ]; then
  urls="https://${RAILHOOK_DOMAIN}/ https://${RAILHOOK_DOMAIN}/docs/ https://${RAILHOOK_DOMAIN}/actuator/health"
fi

# $1 file, remaining args targets. Written to a temp file and moved, so Prometheus never
# reads half of one.
write() {
  file="$1"; shift
  {
    if [ "$#" -eq 0 ]; then
      echo "[]"
    else
      echo "- targets:"
      for t in "$@"; do
        case "$t" in
          *\'*|*\"*|*' '*) echo "[render-targets] skipping a target with quotes or spaces" >&2; continue ;;
        esac
        echo "    - '$t'"
      done
    fi
  } > "$SD/$file.tmp"
  mv "$SD/$file.tmp" "$SD/$file"
}

public=""
for u in $urls; do
  case "$u" in
    http://*|https://*) public="$public $u" ;;
    *) echo "[render-targets] ignoring '$u': not an http(s) URL" >&2 ;;
  esac
done

# shellcheck disable=SC2086
write public-http.yml $public
write internal-http.yml "http://ui:5173/"
if [ -n "${RAILHOOK_DOMAIN:-}" ]; then
  write internal-tcp.yml "caddy:443"
else
  write internal-tcp.yml
fi

echo "[render-targets] public probes:${public:- none}"
