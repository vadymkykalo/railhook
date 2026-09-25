#!/bin/sh
# The `db-backup` Compose sidecar: a plain loop rather than cron, so a failure shows up in
# `docker compose logs db-backup` and the next run still happens.
set -eu

INTERVAL="${DB_BACKUP_INTERVAL_SECONDS:-86400}"

echo "[db-backup-loop] starting, interval=${INTERVAL}s"
while true; do
  if ! bash /scripts/db-backup.sh; then
    echo "[db-backup-loop] backup FAILED at $(date -Iseconds) — will retry in ${INTERVAL}s" >&2
  fi
  sleep "$INTERVAL"
done
