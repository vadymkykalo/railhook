#!/usr/bin/env bash
# Shared Postgres restore logic for Railhook. Counterpart to db-backup.sh
# — see that file's header for the sharing rationale (Helm CronJob duplicates the
# pg_dump/pg_restore flags rather than sourcing this, because Helm charts can't
# reach outside their own chart directory).
#
# Restores a backup produced by db-backup.sh (custom-format `.dump`, restored
# with pg_restore --clean --if-exists) or a legacy `.sql.gz` plain-SQL dump
# (restored with gunzip | psql) produced by the previous plain-SQL `make backup-db`.
#
# Modes (set DB_MODE):
#   embedded  - restores via `docker exec` into a local Postgres container
#   external  - restores via a throwaway postgres:16-alpine container against
#               DB_HOST:DB_PORT
#
# Env vars: same as db-backup.sh, plus:
#   FILE   required: path to the backup file to restore
set -euo pipefail

DB_MODE="${DB_MODE:-}"
FILE="${FILE:-}"

if [ -z "$FILE" ]; then
  echo "[db-restore] ERROR: FILE is required (path to a .dump or .sql.gz backup)" >&2
  exit 1
fi
if [ ! -f "$FILE" ]; then
  echo "[db-restore] ERROR: file not found: $FILE" >&2
  exit 1
fi

is_legacy_sql_gz=0
case "$FILE" in
  *.sql.gz) is_legacy_sql_gz=1 ;;
  *.dump) is_legacy_sql_gz=0 ;;
  *)
    echo "[db-restore] ERROR: unrecognized backup extension for '$FILE' (expected .dump or .sql.gz)" >&2
    exit 1
    ;;
esac

case "$DB_MODE" in
  embedded)
    POSTGRES_CONTAINER="${POSTGRES_CONTAINER:-webhook-postgres}"
    POSTGRES_USER="${POSTGRES_USER:-webhook_user}"
    POSTGRES_DB="${POSTGRES_DB:-webhook_platform}"

    if [ "$is_legacy_sql_gz" = "1" ]; then
      echo "[db-restore] embedded mode: legacy .sql.gz restore into ${POSTGRES_DB}"
      gunzip -c "$FILE" | docker exec -i "$POSTGRES_CONTAINER" psql -U "$POSTGRES_USER" "$POSTGRES_DB"
    else
      echo "[db-restore] embedded mode: pg_restore (custom format) into ${POSTGRES_DB}"
      docker cp "$FILE" "${POSTGRES_CONTAINER}:/tmp/db-restore.dump"
      docker exec "$POSTGRES_CONTAINER" pg_restore -U "$POSTGRES_USER" -d "$POSTGRES_DB" \
        --clean --if-exists --no-owner --no-privileges /tmp/db-restore.dump
      docker exec "$POSTGRES_CONTAINER" rm -f /tmp/db-restore.dump
    fi
    ;;

  external)
    : "${DB_HOST:?DB_HOST is required in external mode}"
    : "${DB_NAME:?DB_NAME is required in external mode}"
    : "${DB_USER:?DB_USER is required in external mode}"
    : "${DB_PASSWORD:?DB_PASSWORD is required in external mode}"
    DB_PORT="${DB_PORT:-5432}"
    NETWORK_ARGS=()
    if [ -n "${DOCKER_NETWORK:-}" ]; then
      NETWORK_ARGS=(--network "$DOCKER_NETWORK")
    fi
    ABS_DIR="$(cd "$(dirname "$FILE")" && pwd)"
    BASENAME="$(basename "$FILE")"

    if [ "$is_legacy_sql_gz" = "1" ]; then
      echo "[db-restore] external mode: legacy .sql.gz restore into ${DB_HOST}:${DB_PORT}/${DB_NAME}"
      gunzip -c "$FILE" | docker run --rm -i "${NETWORK_ARGS[@]}" \
        -e PGPASSWORD="$DB_PASSWORD" \
        postgres:16-alpine \
        psql -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME"
    else
      echo "[db-restore] external mode: pg_restore (custom format) into ${DB_HOST}:${DB_PORT}/${DB_NAME}"
      docker run --rm "${NETWORK_ARGS[@]}" \
        -e PGPASSWORD="$DB_PASSWORD" \
        -v "${ABS_DIR}:/backup:ro" \
        postgres:16-alpine \
        pg_restore -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" \
          --clean --if-exists --no-owner --no-privileges "/backup/${BASENAME}"
    fi
    ;;

  direct)
    : "${DB_HOST:?DB_HOST is required in direct mode}"
    : "${DB_NAME:?DB_NAME is required in direct mode}"
    : "${DB_USER:?DB_USER is required in direct mode}"
    : "${DB_PASSWORD:?DB_PASSWORD is required in direct mode}"
    DB_PORT="${DB_PORT:-5432}"

    if [ "$is_legacy_sql_gz" = "1" ]; then
      echo "[db-restore] direct mode: legacy .sql.gz restore into ${DB_HOST}:${DB_PORT}/${DB_NAME}"
      gunzip -c "$FILE" | PGPASSWORD="$DB_PASSWORD" psql -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME"
    else
      echo "[db-restore] direct mode: pg_restore (custom format) into ${DB_HOST}:${DB_PORT}/${DB_NAME}"
      PGPASSWORD="$DB_PASSWORD" pg_restore -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" \
        --clean --if-exists --no-owner --no-privileges "$FILE"
    fi
    ;;

  *)
    echo "[db-restore] ERROR: DB_MODE must be 'embedded', 'external' or 'direct' (got: '${DB_MODE}')" >&2
    exit 1
    ;;
esac

echo "[db-restore] Restore completed from ${FILE}"
