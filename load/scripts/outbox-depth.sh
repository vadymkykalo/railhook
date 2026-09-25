#!/usr/bin/env bash
set -euo pipefail

DOCKER_COMPOSE="docker compose"
if ! docker compose version >/dev/null 2>&1; then
  DOCKER_COMPOSE="docker-compose"
fi

$DOCKER_COMPOSE exec -T postgres psql -U "${POSTGRES_USER:-webhook_user}" -d "${POSTGRES_DB:-webhook_platform}" -c \
  "SELECT status, count(*), min(created_at) AS oldest, now() - min(created_at) AS oldest_age FROM outbox_messages GROUP BY status ORDER BY status;"
