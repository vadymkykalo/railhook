#!/usr/bin/env bash
# Shared Postgres backup logic for Railhook.
#
# One script, two Compose callers:
#   - `make backup-db`                          (Makefile, embedded or external DB)
#   - the `db-backup` sidecar in docker-compose.yml (scheduled, embedded DB only)
#
# The Kubernetes path (deploy/helm/railhook/templates/db-backup-cronjob.yaml)
# cannot `source` this file directly: Helm only packages files that live inside
# the chart directory, and this script intentionally lives at the repo root so
# the Makefile/Compose paths (which are NOT packaged/shipped) can use it without
# duplicating it into the chart. Keep the pg_dump flags identical
# (`-Fc --no-owner --no-privileges`) in both places if either one changes —
# BackupFlagParityTest (`make ratchets`) fails the build if they diverge.
#
# Produces a custom-format (`-Fc`) dump, restorable with pg_restore / db-restore.sh.
#
# Modes (set DB_MODE):
#   embedded  - pg_dump runs via `docker exec` against a local Postgres container
#               (default container name: webhook-postgres). Used by `make backup-db`
#               from the host.
#   external  - pg_dump runs via a throwaway `postgres:16-alpine` container that
#               connects out to DB_HOST:DB_PORT. No local pg_dump binary required.
#               Used by `make backup-db DB_MODE=external` from the host.
#   direct    - pg_dump runs in-process against DB_HOST:DB_PORT using whatever
#               pg_dump binary is already on PATH. No docker socket, no docker
#               CLI. Used by the `db-backup` Compose sidecar (its image is
#               postgres:16-alpine, so pg_dump ships with the container — it
#               reaches Postgres directly over the webhook-network, it does not
#               need to control sibling containers).
#
# Env vars:
#   DB_MODE                required: embedded | external | direct
#   BACKUP_DIR              default: ./backups
#   BACKUP_RETENTION_DAYS   default: 30 (0 disables pruning)
#   POSTGRES_CONTAINER      embedded mode only, default: webhook-postgres
#   POSTGRES_USER           embedded mode only, default: webhook_user
#   POSTGRES_DB             embedded mode only, default: webhook_platform
#   DB_HOST, DB_PORT, DB_NAME, DB_USER, DB_PASSWORD   external/direct modes (required)
#   DOCKER_NETWORK          external mode, optional: attach the throwaway
#                           dumper container to this docker network (needed if
#                           DB_HOST is only resolvable on a compose network)
set -euo pipefail

DB_MODE="${DB_MODE:-}"
BACKUP_DIR="${BACKUP_DIR:-./backups}"
BACKUP_RETENTION_DAYS="${BACKUP_RETENTION_DAYS:-30}"
TIMESTAMP="$(date +%Y%m%d_%H%M%S)"

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
