#!/bin/sh
# busybox image: POSIX sh, no envsubst. The rendered file holds the SMTP password and Telegram
# token, so it is never printed.
set -eu

OUT="${ALERTMANAGER_CONFIG_OUT:-/etc/alertmanager/alertmanager.yml}"
TEMPLATE_DIR="${ALERTMANAGER_TEMPLATE_DIR:-/etc/alertmanager/templates}"
LINKS="$(dirname "$OUT")/railhook-links.tmpl"

# A value inside single quotes in YAML: a quote is written twice.
q() { printf "'%s'" "$(printf '%s' "$1" | sed "s/'/''/g")"; }

smtp_host="${ALERTMANAGER_SMTP_HOST:-localhost}"
smtp_port="${ALERTMANAGER_SMTP_PORT:-1025}"
# TLS unless told otherwise, except for a local capture server (mailpit, mailhog), which
# has none. Port 465 is implicit TLS and Alertmanager handles it on its own.
require_tls="${ALERTMANAGER_SMTP_REQUIRE_TLS:-}"
if [ -z "$require_tls" ]; then
  case "$smtp_host" in
    localhost|127.0.0.1|mailpit|mailhog) require_tls=false ;;
    *) require_tls=true ;;
  esac
fi

telegram_chat="${ALERTMANAGER_TELEGRAM_CHAT_ID:-}"
if [ -n "$telegram_chat" ] && ! printf '%s' "$telegram_chat" | grep -Eq '^-?[0-9]+$'; then
  echo "[alertmanager-render] ALERTMANAGER_TELEGRAM_CHAT_ID must be a number — Telegram is off" >&2
  telegram_chat=""
fi

# Host names only: they are written into a template verbatim.
hostname_or_empty() {
  if printf '%s' "$1" | grep -Eq '^[A-Za-z0-9]([A-Za-z0-9.-]*[A-Za-z0-9])?$'; then printf '%s' "$1"; fi
}
grafana_domain="$(hostname_or_empty "${MONITORING_DOMAIN:-}")"
site_domain="$(hostname_or_empty "${RAILHOOK_DOMAIN:-}")"

# Prometheus and Alertmanager are not published, so their own links in a mail lead nowhere;
# Grafana on MONITORING_DOMAIN is the one address that works.
{
  if [ -n "$site_domain" ]; then
    printf '{{ define "railhook.domain.suffix" }} — %s{{ end }}\n' "$site_domain"
  else
    printf '{{ define "railhook.domain.suffix" }}{{ end }}\n'
  fi
  if [ -n "$grafana_domain" ]; then
    cat <<LINKS_ON
{{ define "railhook.links.html" }}<p style="margin:8px 0 0;font-size:14px"><a href="https://${grafana_domain}/d/railhook-alerts/alerts" style="display:inline-block;padding:8px 14px;background:#1d4bff;color:#ffffff;border-radius:6px;text-decoration:none">Open the Alerts dashboard</a> &nbsp; <a href="https://${grafana_domain}/alerting/list?search={{ .CommonLabels.alertname }}" style="color:#1d4bff">See the rule in Grafana</a></p>{{ end }}
{{ define "railhook.links.text" }}Alerts dashboard: https://${grafana_domain}/d/railhook-alerts/alerts
The rule in Grafana: https://${grafana_domain}/alerting/list?search={{ .CommonLabels.alertname }}{{ end }}
LINKS_ON
  else
    cat <<'LINKS_OFF'
{{ define "railhook.links.html" }}<p style="margin:8px 0 0;font-size:13px;color:#5b6475">Grafana is not on a domain. Open it through a tunnel: <code>ssh -L 3001:127.0.0.1:3001 &lt;your server&gt;</code>, then http://localhost:3001/d/railhook-alerts/alerts</p>{{ end }}
{{ define "railhook.links.text" }}Grafana is not on a domain. Open it through a tunnel: ssh -L 3001:127.0.0.1:3001 <your server>, then http://localhost:3001/d/railhook-alerts/alerts{{ end }}
LINKS_OFF
  fi
} > "$LINKS"

sinks=""

{
  cat <<'STATIC'
global:
  resolve_timeout: 5m

STATIC
  cat <<TEMPLATES
templates:
  - $(q "${TEMPLATE_DIR}/*.tmpl")
  - $(q "$LINKS")

TEMPLATES
  cat <<'STATIC'

route:
  receiver: railhook-default
  group_by: ['alertname', 'component']
  group_wait: 30s
  group_interval: 5m
  repeat_interval: 4h
  routes:
    # The dead man's switch. Never mailed: it goes only to the heartbeat URL, whose silence is the alert.
    - match:
        alertname: Watchdog
      receiver: railhook-heartbeat
      group_wait: 0s
      group_interval: 1m
      repeat_interval: 1m
    - match:
        severity: critical
      receiver: railhook-critical
      group_wait: 10s
      group_interval: 5m
      repeat_interval: 1h
    - match:
        severity: warning
      receiver: railhook-default
    - match:
        severity: info
      receiver: railhook-info
      group_wait: 5m
      repeat_interval: 12h

inhibit_rules:
  # Each tier is its own alertname fixed to one severity, so `equal: [alertname]` can never
  # match across them; each family is listed explicitly instead.
  - source_match:
      alertname: DeliveryPendingBacklogCritical
    target_match_re:
      alertname: 'DeliveryPendingBacklog(High|Growing)'
    equal: ['component']
  - source_match:
      alertname: OldestPendingDeliveryCritical
    target_match:
      alertname: OldestPendingDeliveryStale
    equal: ['component']
  - source_match:
      alertname: HostDiskCritical
    target_match:
      alertname: HostDiskAlmostFull
    equal: ['device']
  - source_match:
      alertname: TlsCertificateExpiryImminent
    target_match:
      alertname: TlsCertificateExpiringSoon
    equal: ['instance']
  # The UI down inside the network explains every public probe failing.
  - source_match:
      alertname: UiDown
    target_match_re:
      alertname: 'PublicEndpoint(Down|Slow)'
  # For a rule that reuses one alertname across severities.
  - source_match:
      severity: critical
    target_match:
      severity: warning
    equal: ['alertname', 'component']

receivers:
STATIC

  for name in railhook-critical railhook-default railhook-info; do
    echo "  - name: ${name}"

    if [ -n "${ALERTMANAGER_SLACK_WEBHOOK_URL:-}" ]; then
      cat <<SLACK
    slack_configs:
      - api_url: $(q "$ALERTMANAGER_SLACK_WEBHOOK_URL")
        channel: $(q "${ALERTMANAGER_SLACK_CHANNEL:-#railhook-alerts}")
        send_resolved: true
        title: '[{{ .Status | toUpper }}] {{ .CommonLabels.alertname }} ({{ .CommonLabels.severity }}/{{ .CommonLabels.component }})'
        text: >-
          {{ range .Alerts }}{{ .Annotations.summary }}
          {{ .Annotations.description }}{{ end }}
SLACK
    fi

    if [ -n "${ALERTMANAGER_WEBHOOK_URL:-}" ]; then
      cat <<WEBHOOK
    webhook_configs:
      - url: $(q "$ALERTMANAGER_WEBHOOK_URL")
        send_resolved: true
WEBHOOK
    fi

    if [ -n "${ALERTMANAGER_EMAIL_TO:-}" ]; then
      cat <<EMAIL
    email_configs:
      - to: $(q "$ALERTMANAGER_EMAIL_TO")
        from: $(q "${ALERTMANAGER_EMAIL_FROM:-alerts@example.com}")
        smarthost: $(q "${smtp_host}:${smtp_port}")
        require_tls: ${require_tls}
        send_resolved: true
        headers:
          Subject: '{{ template "railhook.email.subject" . }}'
        html: '{{ template "railhook.email.html" . }}'
        text: '{{ template "railhook.email.text" . }}'
EMAIL
      if [ -n "${ALERTMANAGER_SMTP_USERNAME:-}" ]; then
        cat <<AUTH
        auth_username: $(q "$ALERTMANAGER_SMTP_USERNAME")
        auth_password: $(q "${ALERTMANAGER_SMTP_PASSWORD:-}")
AUTH
      fi
    fi

    if [ -n "${ALERTMANAGER_TELEGRAM_BOT_TOKEN:-}" ] && [ -n "$telegram_chat" ]; then
      cat <<TELEGRAM
    telegram_configs:
      - bot_token: $(q "$ALERTMANAGER_TELEGRAM_BOT_TOKEN")
        chat_id: ${telegram_chat}
        parse_mode: HTML
        send_resolved: true
TELEGRAM
    fi
  done

  echo "  - name: railhook-heartbeat"
  if [ -n "${ALERTMANAGER_HEARTBEAT_URL:-}" ]; then
    cat <<HEARTBEAT
    webhook_configs:
      - url: $(q "$ALERTMANAGER_HEARTBEAT_URL")
        send_resolved: false
HEARTBEAT
  fi
} > "$OUT"

[ -n "${ALERTMANAGER_SLACK_WEBHOOK_URL:-}" ] && sinks="$sinks slack"
[ -n "${ALERTMANAGER_WEBHOOK_URL:-}" ] && sinks="$sinks webhook"
[ -n "${ALERTMANAGER_EMAIL_TO:-}" ] && sinks="$sinks email(${smtp_host}:${smtp_port}, tls=${require_tls})"
[ -n "${ALERTMANAGER_TELEGRAM_BOT_TOKEN:-}" ] && [ -n "$telegram_chat" ] && sinks="$sinks telegram"
[ -n "${ALERTMANAGER_HEARTBEAT_URL:-}" ] && sinks="$sinks heartbeat"
echo "[alertmanager-render] wrote ${OUT}; receivers:${sinks:- none — alerts stay in Alertmanager and Grafana}; mail links: ${grafana_domain:-SSH tunnel hint}"
