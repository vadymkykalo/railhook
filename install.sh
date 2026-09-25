#!/usr/bin/env bash
#   curl -fsSL https://railhook.io/install.sh | bash -s -- [options]
set -euo pipefail

REPO="vadymkykalo/railhook"
RAW="https://raw.githubusercontent.com/${REPO}"
INSTALL_DIR="${HOME}/railhook"
VERSION="" PORT="" DOMAIN="" ACME_EMAIL="" ADMIN_EMAILS=""
BEHIND_PROXY=0 START=1 ASSUME_YES=0 ACTION=install

say()  { printf '%s\n' "$*"; }
warn() { printf 'warning: %s\n' "$*" >&2; }
die()  { printf 'error: %s\n' "$*" >&2; exit 1; }

usage() {
    cat <<'USAGE'
Usage: install.sh [options]
  --dir <path>             Install directory (default: ~/railhook)
  --version <tag>          Release, v2.12.0 or newer (default: the latest)
  --port <port>            Published port (default: 80; 8080 with --behind-proxy)
  --domain <host>          HTTPS on this domain via a built-in Caddy; production mode
  --behind-proxy           With --domain: your proxy terminates TLS, the dashboard
                           listens on 127.0.0.1:<port>
  --email <address>        Let's Encrypt contact address
  --admin-email <address>  Platform admins, comma-separated (default: nobody).
                           Also works with --refresh.
  --no-start               Write the files, start nothing
  --yes                    Install into a non-empty directory
  --check                  Check the machine and an existing installation
  --refresh                Rewrite the helper and Caddyfile of an existing installation
  --uninstall | --purge    Remove the containers; --purge also deletes the data volumes
USAGE
}

while [ $# -gt 0 ]; do
    case "$1" in
        --dir)          INSTALL_DIR="${2:?--dir needs a path}"; shift 2 ;;
        --version)      VERSION="${2:?--version needs a tag}"; shift 2 ;;
        --port)         PORT="${2:?--port needs a port}"; shift 2 ;;
        --domain)       DOMAIN="${2:?--domain needs a hostname}"; shift 2 ;;
        --email)        ACME_EMAIL="${2:?--email needs an address}"; shift 2 ;;
        --admin-email)  ADMIN_EMAILS="${2:?--admin-email needs an address}"; shift 2 ;;
        --behind-proxy) BEHIND_PROXY=1; shift ;;
        --no-start)     START=0; shift ;;
        --yes|-y)       ASSUME_YES=1; shift ;;
        --check)        ACTION=check; shift ;;
        --refresh)      ACTION=refresh; shift ;;
        --uninstall)    ACTION=uninstall; shift ;;
        --purge)        ACTION=purge; shift ;;
        -h|--help)      usage; exit 0 ;;
        *)              die "unknown option: $1 (try --help)" ;;
    esac
done

if [ -n "$ADMIN_EMAILS" ]; then
    [ "${ADMIN_EMAILS: -1}" != "," ] || die "--admin-email ends with a comma. Separate addresses with commas: ops@example.com,oncall@example.com"
    IFS=',' read -r -a addresses <<< "$ADMIN_EMAILS"
    for address in "${addresses[@]}"; do
        [[ "$address" =~ ^[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(\.[A-Za-z0-9-]+)+$ ]] \
            || die "--admin-email: '${address}' is not an email address"
    done
fi

# Releases before 2.12.0 were published under another name. With RAILHOOK_COMPOSE_SRC (CI, a
# working tree's Compose file) --version only names an image tag.
if [ -n "$VERSION" ] && [ -z "${RAILHOOK_COMPOSE_SRC:-}" ]; then
    [[ "${VERSION#v}" =~ ^([0-9]+)\.([0-9]+)\.[0-9]+$ ]] || die "--version ${VERSION} is not a release tag, e.g. v2.30.0"
    (( BASH_REMATCH[1] > 2 || (BASH_REMATCH[1] == 2 && BASH_REMATCH[2] >= 12) )) \
        || die "${VERSION} is older than v2.12.0, the oldest release this installer supports"
fi

if [ "$BEHIND_PROXY" = 1 ]; then
    [ -n "$DOMAIN" ] || die "--behind-proxy needs --domain: the public hostname your proxy serves"
    [ -z "$ACME_EMAIL" ] || die "--email is for the built-in certificate; behind your own proxy, drop it"
fi

# No domain: nginx on every interface. --domain: Caddy on 80/443 in front of nginx on loopback.
# --domain --behind-proxy: nginx on loopback for your proxy, no Caddy.
TLS=0 PROFILES=embedded-db BIND=127.0.0.1 APP_ENV=production BASE_URL="https://${DOMAIN}"
if [ -z "$DOMAIN" ]; then
    PORT="${PORT:-80}" BIND=0.0.0.0 APP_ENV=development BASE_URL="http://localhost"
    [ "$PORT" = 80 ] || BASE_URL="http://localhost:${PORT}"
elif [ "$BEHIND_PROXY" = 1 ]; then
    PORT="${PORT:-8080}"
else
    TLS=1 PORT=8080 PROFILES=embedded-db,tls
fi
CHECK_PORTS="$PORT"
[ "$TLS" = 0 ] || CHECK_PORTS="80 443"

COMPOSE_CMD=""
resolve_compose() {
    [ -n "$COMPOSE_CMD" ] && return 0
    if docker compose version >/dev/null 2>&1; then COMPOSE_CMD="docker compose"
    elif docker-compose version >/dev/null 2>&1; then COMPOSE_CMD="docker-compose"
    else return 1
    fi
}
compose() {
    resolve_compose || die "Docker Compose v2 is not available (tried 'docker compose' and 'docker-compose')"
    # shellcheck disable=SC2086 # one word or two
    $COMPOSE_CMD "$@"
}

port_in_use() {
    if command -v ss >/dev/null 2>&1; then ss -lnt 2>/dev/null | awk '{print $4}' | grep -qE "[:.]$1\$"
    elif command -v lsof >/dev/null 2>&1; then lsof -iTCP:"$1" -sTCP:LISTEN >/dev/null 2>&1
    else return 1
    fi
}

check_system() {
    command -v docker >/dev/null 2>&1 || die "Docker is not installed: https://docs.docker.com/engine/install/"
    docker info >/dev/null 2>&1 \
        || die "The Docker daemon is not reachable. Start it, or join the docker group: sudo usermod -aG docker \$USER && newgrp docker"
    resolve_compose || die "Docker Compose v2 is not available: https://docs.docker.com/compose/install/"
    say "Docker $(docker version --format '{{.Server.Version}}' 2>/dev/null || echo '?'), using '${COMPOSE_CMD}'"

    # Two JVMs, Kafka, Postgres and Redis: below 2 GiB they do not all start.
    local mem_gb disk_gb target="$INSTALL_DIR" p
    mem_gb=$(awk '/MemTotal/ {print int($2 / 1048576)}' /proc/meminfo 2>/dev/null || true)
    while [ ! -d "$target" ]; do target=$(dirname "$target"); done
    disk_gb=$(df -Pk "$target" 2>/dev/null | awk 'NR == 2 {print int($4 / 1048576)}' || true)
    [ -z "$mem_gb" ] || [ "$mem_gb" -ge 2 ] || die "${mem_gb} GiB of RAM; the stack needs about 4 GiB"
    if [ "${mem_gb:-4}" -lt 4 ] || [ "${disk_gb:-5}" -lt 5 ]; then
        warn "${mem_gb:-?} GiB RAM and ${disk_gb:-?} GiB free disk; about 4 GiB RAM and 5 GiB disk are recommended"
    fi

    # An existing installation holds its own ports.
    [ "$ACTION" = install ] && [ "$START" = 1 ] && [ ! -f "${INSTALL_DIR}/docker-compose.yml" ] || return 0
    for p in $CHECK_PORTS; do
        port_in_use "$p" || continue
        [ "$TLS" = 0 ] || die "Port ${p} is in use. HTTPS needs 80 and 443; if your own proxy holds them, add --behind-proxy and point it at 127.0.0.1:8080"
        die "Port ${p} is in use. Pick another with --port"
    done
}

# The last NAME= line of .env, quotes stripped.
env_get() { { grep -E "^$1=" "${INSTALL_DIR}/.env" 2>/dev/null || true; } | tail -1 | cut -d= -f2- | tr -d "\"'"; }

# Reads the files on disk, so it catches a hand edit as well as a bad install.
check_config() {
    local fail=0 v
    [ -f "${INSTALL_DIR}/docker-compose.yml" ] && [ -f "${INSTALL_DIR}/.env" ] || die "No installation in ${INSTALL_DIR}"
    (cd "$INSTALL_DIR" && compose config -q) || { say "Compose rejects the configuration (above)"; fail=1; }
    for v in WEBHOOK_ENCRYPTION_KEY WEBHOOK_ENCRYPTION_SALT JWT_SECRET DB_PASSWORD REDIS_PASSWORD; do
        if [ -z "$(env_get "$v")" ]; then say "${v} is empty"; fail=1
        elif env_get "$v" | grep -qiE 'change_?me|dev_|webhook_pass|webhook_redis_pass|placeholder'; then
            say "${v} still holds a placeholder or shipped default"; fail=1
        fi
    done
    # Postgres creates its user with POSTGRES_PASSWORD; the services log in with DB_PASSWORD.
    [ "$(env_get POSTGRES_PASSWORD)" = "$(env_get DB_PASSWORD)" ] || { say "POSTGRES_PASSWORD and DB_PASSWORD differ"; fail=1; }
    if [[ "$(env_get COMPOSE_PROFILES)" != *embedded-db* && -z "$(env_get DB_HOST)" ]]; then
        say "Neither COMPOSE_PROFILES=embedded-db nor DB_HOST is set, so there is no database"; fail=1
    fi
    # What ProductionSafetyValidator refuses at startup, found now rather than in a crash loop.
    if [[ "$(env_get APP_ENV)" =~ ^prod(uction)?$ ]]; then
        for v in WEBHOOK_ALLOW_PRIVATE_IPS SWAGGER_ENABLED; do
            [ "$(env_get "$v")" != true ] || { say "${v}=true is refused in production"; fail=1; }
        done
        [[ "$(env_get CORS_ALLOWED_ORIGINS)" != *localhost* ]] || { say "CORS_ALLOWED_ORIGINS contains localhost"; fail=1; }
        [[ "$(env_get APP_BASE_URL)" != http://localhost* ]] || warn "APP_BASE_URL points at localhost; links in mail will not open"
        [ "$(env_get EMAIL_ENABLED)" = true ] || warn "EMAIL_ENABLED is off: accounts are created verified, no invites are sent"
    fi
    [ "$fail" = 0 ] || die "Fix the configuration above, then re-run with --check"
    say "Configuration OK"
}

resolve_version() {
    [ -z "$VERSION" ] || return 0
    VERSION=$(curl -fsSL "https://api.github.com/repos/${REPO}/releases/latest" 2>/dev/null \
        | sed -n 's/.*"tag_name": *"\([^"]*\)".*/\1/p' | head -1 || true)
    [ -n "$VERSION" ] || die "Could not find the latest release on GitHub; pass one with --version"
}

secret()   { openssl rand -base64 "$1" | tr -d '\n'; }
password() { openssl rand -base64 24 | tr -d '\n/+='; }

# PLATFORM_ADMIN_EMAILS in an existing .env: that line replaced or added, the rest untouched.
set_admin_emails() {
    local env="${INSTALL_DIR}/.env" tmp
    [ -f "$env" ] || return 0
    tmp=$(mktemp "${env}.XXXXXX")
    awk -v line="PLATFORM_ADMIN_EMAILS=${ADMIN_EMAILS}" '
        /^PLATFORM_ADMIN_EMAILS=/ { if (!done) { print line; done = 1 } next }
        { print }
        END { if (!done) print line }' "$env" > "$tmp"
    chmod 600 "$tmp"
    mv "$tmp" "$env"
    say "Platform admins: ${ADMIN_EMAILS}"
}

fetch() { curl -fsSL "${RAW}/${VERSION}/$1" -o "${INSTALL_DIR}/$1" 2>/dev/null; }

write_files() {
    mkdir -p "${INSTALL_DIR}/deploy/scripts"
    if [ -n "${RAILHOOK_COMPOSE_SRC:-}" ]; then
        cp "$RAILHOOK_COMPOSE_SRC" "${INSTALL_DIR}/docker-compose.yml" || die "Could not copy ${RAILHOOK_COMPOSE_SRC}"
    elif ! fetch docker-compose.yml || ! grep -q 'ghcr\.io/vadymkykalo/railhook' "${INSTALL_DIR}/docker-compose.yml"; then
        die "Could not download docker-compose.yml for ${VERSION}"   # the grep catches a 200 that is not one
    fi
    # The nightly backup sidecar mounts these; without them only `./railhook backup` works.
    local backup=",backup"
    if fetch deploy/scripts/db-backup.sh && fetch deploy/scripts/db-backup-loop.sh; then
        chmod +x "${INSTALL_DIR}"/deploy/scripts/*.sh
    else
        backup=""
        warn "Could not fetch the backup scripts; scheduled backups are off"
    fi
    [ "$TLS" = 0 ] || write_caddyfile

    if [ -f "${INSTALL_DIR}/.env" ]; then
        say "Keeping the existing .env and its secrets"
        [ -z "$ADMIN_EMAILS" ] || set_admin_emails
        return 0
    fi
    local db_pass redis_pass caddy_domain="" prod=""
    db_pass=$(password)
    redis_pass=$(password)
    [ "$TLS" = 0 ] || caddy_domain="$DOMAIN"
    # 172.16.0.0/12 is the Docker bridge range, so X-Forwarded-For from the proxy in front is trusted.
    [ -z "$DOMAIN" ] || prod=$'\n# Internet-facing defaults, set by --domain.\nDB_SSL_MODE=require\nLOG_LEVEL=WARN\nWEBHOOK_TRUSTED_PROXIES=172.16.0.0/12'
    umask 077
    cat > "${INSTALL_DIR}/.env" <<ENVFILE
# Railhook, generated by install.sh on $(date -u +%Y-%m-%dT%H:%M:%SZ).
# Every option: https://github.com/${REPO}/blob/${VERSION}/.env.dist
# Back this file up with the database: a dump's encrypted columns need WEBHOOK_ENCRYPTION_KEY.

WEBHOOK_ENCRYPTION_KEY=$(secret 32)
WEBHOOK_ENCRYPTION_SALT=$(secret 16)
JWT_SECRET=$(secret 48)
# POSTGRES_PASSWORD creates the database user, DB_PASSWORD logs in with it: keep them equal.
POSTGRES_PASSWORD=${db_pass}
DB_PASSWORD=${db_pass}
REDIS_PASSWORD=${redis_pass}

API_IMAGE_TAG=${VERSION#v}
WORKER_IMAGE_TAG=${VERSION#v}
UI_IMAGE_TAG=${VERSION#v}

# The one published port: nginx serves the dashboard and proxies the API.
RAILHOOK_BIND=${BIND}
RAILHOOK_PORT=${PORT}
# Read by the built-in Caddy, which runs only under the tls profile.
RAILHOOK_DOMAIN=${caddy_domain}
ACME_EMAIL=${ACME_EMAIL}
COMPOSE_PROFILES=${PROFILES}${backup}

# Verification, invite and reset links are built from this.
APP_BASE_URL=${BASE_URL}
CORS_ALLOWED_ORIGINS=${BASE_URL}
# production refuses to start on unsafe settings.
APP_ENV=${APP_ENV}
# Off: accounts are created verified. Turn on and set SMTP_* to send mail.
EMAIL_ENABLED=false
# Who may open /admin/platform, comma-separated. Empty means nobody.
PLATFORM_ADMIN_EMAILS=${ADMIN_EMAILS}
${prod}
ENVFILE
    say "Wrote ${INSTALL_DIR}/.env with new secrets"
}

write_caddyfile() {
    cat > "${INSTALL_DIR}/Caddyfile" <<'CADDY'
{
	email {$ACME_EMAIL}
	# Pass X-Forwarded-For on intact: the API picks the client hop against WEBHOOK_TRUSTED_PROXIES.
	# Replacing it makes every visitor behind one CDN edge share one rate limit.
	servers {
		trusted_proxies static 0.0.0.0/0 ::/0
	}
}

{$RAILHOOK_DOMAIN} {
	encode gzip zstd
	# nginx in the ui container does the routing; Caddy only terminates TLS.
	reverse_proxy ui:5173 {
		header_up X-Forwarded-Proto https
		# Retry refused dials while the ui container is replaced, instead of answering 502.
		lb_try_duration 30s
		lb_try_interval 250ms
		# The CLI tunnel is a WebSocket held open for a whole session.
		transport http {
			read_timeout 3600s
			write_timeout 3600s
		}
	}
	header {
		Strict-Transport-Security "max-age=31536000; includeSubDomains"
		-Server
	}
}
CADDY
    # Grafana from the optional monitoring stack, when .env sets MONITORING_DOMAIN. Both values
    # are written into the config verbatim, so only a hostname and plain paths get in.
    local host cert key tls=""
    host=$(env_get MONITORING_DOMAIN) cert=$(env_get MONITORING_TLS_CERT) key=$(env_get MONITORING_TLS_KEY)
    [ -n "$host" ] || return 0
    [[ "$host" =~ ^[A-Za-z0-9]([A-Za-z0-9.-]*[A-Za-z0-9])?$ ]] \
        || { warn "MONITORING_DOMAIN is not a hostname; Grafana is not added to the Caddyfile"; return 0; }
    if [[ "$cert" =~ ^/[A-Za-z0-9._/-]+$ && "$key" =~ ^/[A-Za-z0-9._/-]+$ ]]; then
        tls=$'\ttls '"${cert} ${key}"$'\n'
    elif [ -n "$cert" ] && [ -n "$key" ]; then
        warn "MONITORING_TLS_CERT or MONITORING_TLS_KEY is not a plain absolute path; Caddy gets the certificate itself"
    fi
    cat >> "${INSTALL_DIR}/Caddyfile" <<CADDY

${host} {
${tls}	encode gzip zstd
	reverse_proxy railhook-grafana:3000 {
		lb_try_duration 5s
	}
	header {
		Strict-Transport-Security "max-age=31536000; includeSubDomains"
		-Server
	}
}
CADDY
}

# The Caddyfile is a bind mount: `up -d` does not restart Caddy, so a rewritten file needs a
# reload. A failure leaves Caddy on the config it already serves.
reload_caddy() {
    resolve_compose || return 0
    (
        cd "$INSTALL_DIR"
        [ -n "$(compose ps -q caddy 2>/dev/null)" ] || exit 0
        if compose exec -T caddy caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile >/dev/null 2>&1 \
           && compose exec -T caddy caddy reload --config /etc/caddy/Caddyfile --adapter caddyfile >/dev/null 2>&1; then
            say "Caddy reloaded"
        else
            warn "The new Caddyfile did not validate or reload; Caddy keeps its previous config"
        fi
    )
}

write_helper() {
    cat > "${INSTALL_DIR}/railhook" <<'HELPER'
#!/usr/bin/env bash
# Shortcuts over docker compose. Anything not listed below is passed to it unchanged.
set -euo pipefail
cd "$(dirname "$(readlink -f "$0")")"
RAW="https://raw.githubusercontent.com/vadymkykalo/railhook"
if docker compose version >/dev/null 2>&1; then COMPOSE_CMD="docker compose"
elif docker-compose version >/dev/null 2>&1; then COMPOSE_CMD="docker-compose"
else echo "Docker Compose is not available (tried 'docker compose' and 'docker-compose')." >&2; exit 1; fi
# shellcheck disable=SC2086
compose() { $COMPOSE_CMD "$@"; }

# Docker DNS publishes a container before it is healthy, so the replacement api warms up under a
# throwaway alias and takes the name nginx resolves only once it answers.
roll_api() {
    local ids target net old new before waited
    ids=$(compose ps -q api || true)
    target=$(printf '%s\n' "$ids" | grep -c . || true)
    if [ "${target:-0}" -lt 1 ]; then compose up -d --no-deps api; return; fi
    net=$(docker network ls --format '{{.Name}}' | grep -E 'webhook-network$' | head -1 || true)
    [ -n "$net" ] || { compose up -d --no-deps api; return; }
    for old in $ids; do
        before=$(compose ps -aq api | sort)
        compose create --no-recreate --scale api=$((target + 1)) api >/dev/null 2>&1
        new=$(comm -13 <(echo "$before") <(compose ps -aq api | sort) | head -1)
        [ -n "$new" ] || { echo "Compose created no replacement API container." >&2; return 1; }
        docker network disconnect "$net" "$new" >/dev/null 2>&1 || true
        docker network connect --alias api-warming "$net" "$new"
        docker start "$new" >/dev/null
        for waited in $(seq 0 3 180); do
            case "$(docker inspect -f '{{.State.Health.Status}}' "$new" 2>/dev/null || echo gone)" in
                healthy) break ;;
                unhealthy) echo "The new API container is unhealthy; the old one keeps serving." >&2; return 1 ;;
            esac
            [ "$waited" -lt 180 ] || { echo "The new API container never became healthy; the old one keeps serving." >&2; return 1; }
            sleep 3
        done
        docker network disconnect "$net" "$new"
        docker network connect --alias api "$net" "$new"
        sleep 8   # nginx re-resolves before the old one stops answering
        docker stop "$old" >/dev/null 2>&1 || true   # SIGTERM: graceful shutdown drains in-flight requests
        docker rm -f "$old" >/dev/null 2>&1 || true
        echo "API container ${old:0:12} replaced by ${new:0:12}"
    done
}

# Generated secrets and image tags are refused: a new encryption key alone makes every encrypted
# column unreadable.
apply_settings() {
    local input bad tmp
    input=$(tr -d '\r' | grep -vE '^(#|$)' || true)
    [ -n "$input" ] || return 0
    if printf '%s\n' "$input" | grep -qvE '^[A-Z][A-Z0-9_]*='; then
        echo "settings: a line is not NAME=value; nothing applied" >&2; return 1
    fi
    bad=$(printf '%s\n' "$input" | grep -oE '^(WEBHOOK_ENCRYPTION_(KEY|SALT)|JWT_SECRET|POSTGRES_PASSWORD|DB_PASSWORD|REDIS_PASSWORD|(API|WORKER|UI)_IMAGE_TAG)=' | head -1 || true)
    [ -z "$bad" ] || { echo "settings: ${bad%=} cannot be set this way; nothing applied" >&2; return 1; }
    touch .env
    tmp=$(mktemp ./.env.XXXXXX)
    # The first line of each name is replaced in place, new names are appended, the rest is kept.
    printf '%s\n' "$input" | awk '
        function name(l) { return index(l, "=") ? substr(l, 1, index(l, "=") - 1) : "" }
        NR == FNR { k = name($0); if (!(k in v)) order[++n] = k; v[k] = substr($0, length(k) + 2); next }
        { k = name($0) }
        k != "" && (k in v) && !(k in seen) { seen[k] = 1; print k "=" v[k]; next }
        { print }
        END { for (i = 1; i <= n; i++) if (!(order[i] in seen)) print order[i] "=" v[order[i]] }' - .env > "$tmp"
    chmod 600 "$tmp"
    mv -f "$tmp" .env
    printf '%s\n' "$input" | cut -d= -f1 | sort -u | sed 's/.*/settings: & set/'
}

case "${1:-help}" in
    start)   compose up -d ;;
    stop)    compose stop ;;
    restart) compose restart ;;
    status)  compose ps ;;
    logs)    shift; compose logs -f "$@" ;;
    upgrade)
        from=$(grep '^API_IMAGE_TAG=' .env | cut -d= -f2- || echo unknown)
        want="${2:-}"
        # Switch to the target release's helper first, so its upgrade steps are the ones that run.
        # The refresh also rewrites and reloads the Caddyfile; its output stays visible.
        if [ -z "${RAILHOOK_HELPER_REFRESHED:-}" ] && [ -n "$want" ]; then
            curl -fsSL "${RAW}/v${want#v}/install.sh" | bash -s -- --refresh --dir "$(pwd)" \
                || echo "Could not fetch the helper for ${want}; continuing with this one." >&2
            RAILHOOK_HELPER_REFRESHED=1 export RAILHOOK_HELPER_REFRESHED
            exec "$0" upgrade "$want"
        fi
        if [ ! -t 0 ]; then apply_settings || { echo "Settings refused; not upgrading, nothing changed." >&2; exit 1; }; fi
        if [ -n "$want" ]; then
            for v in API_IMAGE_TAG WORKER_IMAGE_TAG UI_IMAGE_TAG; do   # git tag v2.16.0 = image 2.16.0
                if grep -q "^${v}=" .env; then sed -i.bak "s|^${v}=.*|${v}=${want#v}|" .env
                else echo "${v}=${want#v}" >> .env; fi
            done
            rm -f .env.bak
            echo "Pinned API/WORKER/UI image tags to ${want#v}."
        fi
        # Migrations do not roll back with the images, so a failed backup stops the upgrade.
        echo "Backing up before anything changes..."
        "$0" backup || { echo "Backup failed; not upgrading." >&2; exit 1; }
        tag="${want:-$from}"
        if curl -fsSL "${RAW}/v${tag#v}/docker-compose.yml" -o docker-compose.yml.new 2>/dev/null \
           && ! cmp -s docker-compose.yml docker-compose.yml.new; then
            cp docker-compose.yml docker-compose.yml.previous
            mv docker-compose.yml.new docker-compose.yml
            echo "docker-compose.yml updated for v${tag#v} (previous kept as docker-compose.yml.previous)"
        fi
        rm -f docker-compose.yml.new
        compose pull
        # One service per call keeps the UI gap to seconds. Naming a service enables its profile,
        # so only services the active profiles already include are touched.
        active=$(compose config --services)
        up_one() {
            printf '%s\n' "$active" | grep -qx "$1" || return 0
            compose up -d --no-deps "$1" || { echo "Could not start $1; see ./railhook logs $1" >&2; exit 1; }
        }
        for svc in postgres kafka redis ui caddy; do up_one "$svc"; done
        # Only the API runs migrations, and a new worker validates the schema when it starts.
        roll_api || { echo "The API did not come up; the worker was left as it was." >&2; exit 1; }
        up_one worker
        echo "Upgraded from ${from}. To roll the images back, set the *_IMAGE_TAG lines in .env to ${from}"
        echo "and run ./railhook start. The schema does not roll back; restore the backup for that." ;;
    backup)
        set -a
        # shellcheck disable=SC1091
        [ ! -f .env ] || . ./.env
        set +a
        umask 077   # a dump holds every row
        f="backup-$(date -u +%Y%m%dT%H%M%SZ).dump"
        # Same flags as deploy/scripts/db-backup.sh: restorable with pg_restore into other roles.
        compose exec -T postgres pg_dump -U "${POSTGRES_USER:-webhook_user}" -d "${POSTGRES_DB:-webhook_platform}" \
            -Fc --no-owner --no-privileges > "$f" || { rm -f "$f"; echo "pg_dump failed." >&2; exit 1; }
        echo "Wrote $f. Keep .env with it: the encrypted columns need WEBHOOK_ENCRYPTION_KEY." ;;
    settings) apply_settings ;;
    doctor)  curl -fsSL "${RAW}/main/install.sh" | bash -s -- --check --dir "$(pwd)" ;;
    help|-h|--help)
        echo "railhook start|stop|restart|status|logs [service]|upgrade [version]|backup|doctor"
        echo "Anything else is passed to docker compose. After editing .env, run ./railhook start." ;;
    *)       compose "$@" ;;
esac
HELPER
    chmod +x "${INSTALL_DIR}/railhook"
}

start_stack() {
    (cd "$INSTALL_DIR" && compose pull -q && compose up -d) || die "Compose could not start the stack"
    say "Waiting for the platform; the first start runs the migrations..."
    local i
    for i in $(seq 1 60); do
        curl -fsS -o /dev/null "http://127.0.0.1:${PORT}/actuator/health/liveness" 2>/dev/null && return 0
        [ "$i" = 60 ] || sleep 10
    done
    die "It did not come up within 10 minutes. See: cd ${INSTALL_DIR} && ./railhook logs"
}

proxy_hint() {
    say "Point your proxy for ${DOMAIN} at http://127.0.0.1:${PORT}. /ws/tunnel is a WebSocket:"
    say "pass the Upgrade headers and use a long read timeout."
}

case "$ACTION" in
    check)
        check_system
        check_config
        exit 0 ;;
    uninstall|purge)
        [ -f "${INSTALL_DIR}/docker-compose.yml" ] || die "Nothing installed at ${INSTALL_DIR}"
        if [ "$ACTION" = purge ]; then
            (cd "$INSTALL_DIR" && compose down -v)
            say "Containers and data volumes removed. ${INSTALL_DIR} and its .env are left for you to delete."
        else
            (cd "$INSTALL_DIR" && compose down)
            say "Containers removed, data volumes kept. Start again: cd ${INSTALL_DIR} && ./railhook start"
        fi
        exit 0 ;;
    refresh)
        [ -d "$INSTALL_DIR" ] || die "${INSTALL_DIR} does not exist"
        [ -z "$ADMIN_EMAILS" ] || set_admin_emails
        write_helper
        # Only an installation that already has a Caddyfile gets a new one.
        if [ -f "${INSTALL_DIR}/Caddyfile" ]; then
            write_caddyfile
            reload_caddy
        fi
        say "Refreshed ${INSTALL_DIR}"
        exit 0 ;;
esac

check_system
if [ -n "$(ls -A "$INSTALL_DIR" 2>/dev/null)" ] && [ ! -f "${INSTALL_DIR}/docker-compose.yml" ] && [ "$ASSUME_YES" = 0 ]; then
    die "${INSTALL_DIR} exists and is not empty. Use --dir, or --yes to install there anyway."
fi
resolve_version
say "Installing Railhook ${VERSION} into ${INSTALL_DIR}"
write_files
write_helper
check_config
if [ "$START" = 0 ]; then
    say "Files written, nothing started. Start with: cd ${INSTALL_DIR} && ./railhook start"
    [ "$BEHIND_PROXY" = 0 ] || proxy_hint
    exit 0
fi
start_stack
say ""
say "Railhook is running at ${BASE_URL} (API ${BASE_URL}/api/v1, docs ${BASE_URL}/docs)."
say "Register on the dashboard; without SMTP the first account is active immediately."
say "In ${INSTALL_DIR}: ./railhook status | logs | stop | start | upgrade | backup | doctor"
say ".env holds your secrets. Back it up."
if [ "$BEHIND_PROXY" = 1 ]; then
    proxy_hint
elif [ -z "$DOMAIN" ]; then
    say "On a server, install with --domain <host> for HTTPS, or add --behind-proxy for your own proxy."
fi
