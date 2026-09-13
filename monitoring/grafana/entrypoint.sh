#!/bin/sh
# Grafana's entrypoint, in front of the image's own /run.sh.
#
# Grafana reads GF_SECURITY_ADMIN_PASSWORD only when it creates its database. So a changed
# password in .env did nothing on an existing volume, and the default this stack once
# shipped would have stayed valid for as long as the volume did. Here the password must be
# set, must not be a known default, and is re-applied to an existing database on every
# start: .env is the one place it lives.
set -eu

pw="${GF_SECURITY_ADMIN_PASSWORD:-}"
case "$pw" in
  ''|admin|railhook_monitor_2024)
    echo "[grafana] GRAFANA_ADMIN_PASSWORD is empty or a known default. Set it in .env:" >&2
    echo "[grafana]   GRAFANA_ADMIN_PASSWORD=\$(openssl rand -base64 24)" >&2
    exit 78 ;;
esac
if [ "${#pw}" -lt 16 ]; then
  echo "[grafana] GRAFANA_ADMIN_PASSWORD is shorter than 16 characters — refusing to start." >&2
  exit 78
fi

if [ -n "${MONITORING_DOMAIN:-}" ]; then
  # Served through Caddy on HTTPS: links, redirects and OAuth callbacks use this.
  export GF_SERVER_ROOT_URL="https://${MONITORING_DOMAIN}/"
  export GF_SECURITY_COOKIE_SECURE=true
  export GF_SECURITY_STRICT_TRANSPORT_SECURITY=true
else
  export GF_SERVER_ROOT_URL="http://localhost:${GRAFANA_PORT:-3001}/"
fi

if [ -f /var/lib/grafana/grafana.db ]; then
  if ! grafana cli --homepath /usr/share/grafana admin reset-admin-password "$pw" >/dev/null 2>&1; then
    echo "[grafana] could not re-apply the admin password to the existing database" >&2
  fi
fi

exec /run.sh "$@"
