#!/bin/sh
# Prometheus does not expand environment variables in its config, so targets are rendered here
# (busybox image: POSIX sh only).
set -eu

SD="${PROMETHEUS_SD_DIR:-/prometheus/sd}"
mkdir -p "$SD"

urls=$(printf '%s' "${MONITORING_PROBE_URLS:-}" | tr ',' ' ')
if [ -z "$(printf '%s' "$urls" | tr -d ' ')" ] && [ -n "${RAILHOOK_DOMAIN:-}" ]; then
  urls="https://${RAILHOOK_DOMAIN}/ https://${RAILHOOK_DOMAIN}/docs/ https://${RAILHOOK_DOMAIN}/actuator/health"
fi

# Written to a temp file and moved, so Prometheus never reads half of one.
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
