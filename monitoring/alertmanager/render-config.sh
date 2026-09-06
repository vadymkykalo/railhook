#!/bin/sh
# Renders /etc/alertmanager/alertmanager.yml from the ALERTMANAGER_* env vars
# (see .env.dist "ALERTING" section) at container start.
#
# Why a shell script instead of a static YAML file: the prom/alertmanager image
# is busybox-based (no apk, no envsubst, no bash) — see monitoring/README.md for
# the investigation — so this uses only POSIX sh + heredocs. Each of the three
# receivers (railhook-critical / railhook-default / railhook-info) fans out to
# whichever sinks (Slack / generic webhook / email) have a non-empty env var, so
# the rendered config is always valid even with zero secrets configured — alerts
# just land only in Alertmanager's own UI/API (http://localhost:9093), which is
# the documented "you haven't wired a receiver yet" state, not a crash.
set -eu

OUT=/etc/alertmanager/alertmanager.yml

{
  cat <<'STATIC'
global:
  resolve_timeout: 5m

route:
  receiver: railhook-default
  group_by: ['alertname', 'component']
  group_wait: 30s
  group_interval: 5m
  repeat_interval: 4h
  routes:
    # Critical: page faster, remind more often.
    - match:
        severity: critical
      receiver: railhook-critical
      group_wait: 10s
      group_interval: 5m
      repeat_interval: 1h
    - match:
        severity: warning
      receiver: railhook-default
    # Info: informational only (e.g. KafkaDlqTopicNotEmpty) — batch, remind rarely.
    - match:
        severity: info
      receiver: railhook-info
      group_wait: 5m
      repeat_interval: 12h

inhibit_rules:
  # These alertname pairs are different rules (each fixed to one severity —
  # see prometheus/alerts.yml), so a plain `equal: [alertname]` rule can never
  # match across them; each pair is listed explicitly by "family" instead. 14
  # rules firing ungrouped at 3am is its own failure mode.
  #
  # DeliveryPendingBacklogCritical firing means the High/Growing warnings for
  # the same component are a known consequence, not new information.
  - source_match:
      alertname: DeliveryPendingBacklogCritical
    target_match_re:
      alertname: 'DeliveryPendingBacklog(High|Growing)'
    equal: ['component']
  # Same relationship for the oldest-pending-age tier.
  - source_match:
      alertname: OldestPendingDeliveryCritical
    target_match:
      alertname: OldestPendingDeliveryStale
    equal: ['component']
  # Generic fallback: any future rule that reuses one alertname across
  # severities (via a templated threshold) gets this for free.
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
      - api_url: '${ALERTMANAGER_SLACK_WEBHOOK_URL}'
        channel: '${ALERTMANAGER_SLACK_CHANNEL:-#railhook-alerts}'
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
      - url: '${ALERTMANAGER_WEBHOOK_URL}'
        send_resolved: true
WEBHOOK
    fi

    if [ -n "${ALERTMANAGER_EMAIL_TO:-}" ]; then
      cat <<EMAIL
    email_configs:
      - to: '${ALERTMANAGER_EMAIL_TO}'
        from: '${ALERTMANAGER_EMAIL_FROM:-alerts@example.com}'
        smarthost: '${ALERTMANAGER_SMTP_HOST:-localhost}:${ALERTMANAGER_SMTP_PORT:-1025}'
        require_tls: false
        send_resolved: true
EMAIL
    fi
  done
} > "$OUT"

echo "[alertmanager-render] wrote ${OUT}:"
cat "$OUT"
