#!/usr/bin/env bash
# The chart's CronJob cannot source this file. Keep the pg_dump flags identical
# (`-Fc --no-owner --no-privileges`) in both places. DB_MODE: embedded | external | direct.
set -euo pipefail

DB_MODE="${DB_MODE:-}"
BACKUP_DIR="${BACKUP_DIR:-./backups}"
BACKUP_RETENTION_DAYS="${BACKUP_RETENTION_DAYS:-30}"
TIMESTAMP="$(date +%Y%m%d_%H%M%S)"

# Dumps hold every row of the database: readable by their owner only.
umask 077
mkdir -p "$BACKUP_DIR"

case "$DB_MODE" in
  embedded)
    POSTGRES_CONTAINER="${POSTGRES_CONTAINER:-webhook-postgres}"
    POSTGRES_USER="${POSTGRES_USER:-webhook_user}"
    POSTGRES_DB="${POSTGRES_DB:-webhook_platform}"
    OUT_FILE="${BACKUP_DIR}/webhook_platform_${TIMESTAMP}.dump"

    echo "[db-backup] embedded mode: pg_dump via 'docker exec ${POSTGRES_CONTAINER}' -> ${OUT_FILE}"
    docker exec "$POSTGRES_CONTAINER" pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" \
      -Fc --no-owner --no-privileges -f /tmp/db-backup.dump
    docker cp "${POSTGRES_CONTAINER}:/tmp/db-backup.dump" "$OUT_FILE"
    docker exec "$POSTGRES_CONTAINER" rm -f /tmp/db-backup.dump
    ;;

  external)
    : "${DB_HOST:?DB_HOST is required in external mode}"
    : "${DB_NAME:?DB_NAME is required in external mode}"
    : "${DB_USER:?DB_USER is required in external mode}"
    : "${DB_PASSWORD:?DB_PASSWORD is required in external mode}"
    DB_PORT="${DB_PORT:-5432}"
    OUT_FILE="${BACKUP_DIR}/webhook_platform_${TIMESTAMP}.dump"
    NETWORK_ARGS=()
    if [ -n "${DOCKER_NETWORK:-}" ]; then
      NETWORK_ARGS=(--network "$DOCKER_NETWORK")
    fi

    echo "[db-backup] external mode: pg_dump via throwaway container -> ${DB_HOST}:${DB_PORT}/${DB_NAME}"
    docker run --rm "${NETWORK_ARGS[@]}" \
      -e PGPASSWORD="$DB_PASSWORD" \
      -v "$(cd "$BACKUP_DIR" && pwd)":/backup \
      postgres:16-alpine \
      pg_dump -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" \
        -Fc --no-owner --no-privileges -f "/backup/webhook_platform_${TIMESTAMP}.dump"
    ;;

  direct)
    : "${DB_HOST:?DB_HOST is required in direct mode}"
    : "${DB_NAME:?DB_NAME is required in direct mode}"
    : "${DB_USER:?DB_USER is required in direct mode}"
    : "${DB_PASSWORD:?DB_PASSWORD is required in direct mode}"
    DB_PORT="${DB_PORT:-5432}"
    OUT_FILE="${BACKUP_DIR}/webhook_platform_${TIMESTAMP}.dump"

    echo "[db-backup] direct mode: pg_dump -> ${DB_HOST}:${DB_PORT}/${DB_NAME}"
    PGPASSWORD="$DB_PASSWORD" pg_dump -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" \
      -Fc --no-owner --no-privileges -f "$OUT_FILE"
    ;;

  *)
    echo "[db-backup] ERROR: DB_MODE must be 'embedded', 'external' or 'direct' (got: '${DB_MODE}')" >&2
    echo "[db-backup] Set DB_MODE=external and DB_HOST/DB_PORT/DB_NAME/DB_USER/DB_PASSWORD to back up a managed/remote Postgres instance." >&2
    exit 1
    ;;
esac

FILESIZE="$(du -h "$OUT_FILE" | cut -f1)"
echo "[db-backup] Backup completed: ${OUT_FILE} (${FILESIZE})"

if [ "${BACKUP_RETENTION_DAYS}" != "0" ]; then
  echo "[db-backup] Pruning backups older than ${BACKUP_RETENTION_DAYS} days in ${BACKUP_DIR}"
  find "$BACKUP_DIR" -maxdepth 1 -name 'webhook_platform_*.dump' -type f -mtime "+${BACKUP_RETENTION_DAYS}" -print -delete
fi

echo "[db-backup] Current backups in ${BACKUP_DIR}:"
ls -lh "$BACKUP_DIR"/webhook_platform_*.dump 2>/dev/null || echo "[db-backup] (none)"
