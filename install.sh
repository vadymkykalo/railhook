#!/usr/bin/env bash
#
# Railhook installer.
#
#   curl -fsSL https://raw.githubusercontent.com/vadymkykalo/railhook/main/install.sh | bash
#
# Creates a directory, writes a Compose file pinned to a release and a .env with
# freshly generated secrets, then starts the stack. What it leaves behind is an
# ordinary Compose deployment — no wrapper runtime, nothing bespoke — so
# `docker compose` works on it exactly as you would expect.
#
# Deliberately not what this does: download .env.dist. That file is a
# documented catalogue of every knob, and the values in it are public. Secrets
# that ship in a public repository are not secrets, so this generates real ones
# instead of handing back the repository's.
set -euo pipefail

REPO="vadymkykalo/railhook"
RAW="https://raw.githubusercontent.com/${REPO}"
DEFAULT_DIR="${HOME}/railhook"

INSTALL_DIR=""
VERSION=""
PORT="80"
DOMAIN=""
ACME_EMAIL=""
START=1
ASSUME_YES=0
ACTION="install"

# --- output -----------------------------------------------------------------
if [ -t 1 ] && [ -z "${NO_COLOR:-}" ]; then
    B=$'\033[1m'; DIM=$'\033[2m'; RED=$'\033[31m'; GRN=$'\033[32m'; YEL=$'\033[33m'; N=$'\033[0m'
else
    B=""; DIM=""; RED=""; GRN=""; YEL=""; N=""
fi
say()  { printf '%s\n' "$*"; }
step() { printf '%s==>%s %s\n' "$B" "$N" "$*"; }
ok()   { printf '  %s✓%s %s\n' "$GRN" "$N" "$*"; }
warn() { printf '  %s!%s %s\n' "$YEL" "$N" "$*"; }
die()  { printf '\n  %sx%s %s\n\n' "$RED" "$N" "$*" >&2; exit 1; }

usage() {
    cat <<USAGE
${B}Railhook installer${N}

  curl -fsSL ${RAW}/main/install.sh | bash

${B}Options${N}
  --dir <path>       Where to install          (default: ${DEFAULT_DIR})
  --version <tag>    Release to pin            (default: the latest release)
  --port <port>      The one published port    (default: ${PORT})
  --domain <host>    Serve on this domain over HTTPS. Turns on a TLS
                     terminator that obtains and renews the certificate
                     itself, and switches the platform to production mode.
  --email <address>  Where Let's Encrypt should send expiry warnings
  --no-start         Write the files, do not start anything
  --yes              Do not ask before reusing a non-empty directory
  --check            Run the system and configuration checks only, change nothing
  --uninstall        Stop the stack and remove the containers (keeps your data)
  --purge            Uninstall, and delete the data volumes as well
  -h, --help         This text

${B}Passing options through a pipe${N}
  curl -fsSL ${RAW}/main/install.sh | bash -s -- --dir /opt/railhook
USAGE
}

while [ $# -gt 0 ]; do
    case "$1" in
        --dir)       INSTALL_DIR="${2:?--dir needs a path}"; shift 2 ;;
        --version)   VERSION="${2:?--version needs a tag}"; shift 2 ;;
        --port)      PORT="${2:?--port needs a port}"; shift 2 ;;
        --domain)    DOMAIN="${2:?--domain needs a hostname}"; shift 2 ;;
        --email)     ACME_EMAIL="${2:?--email needs an address}"; shift 2 ;;
        --no-start)  START=0; shift ;;
        --yes|-y)    ASSUME_YES=1; shift ;;
        --check)     ACTION="check"; shift ;;
        --uninstall) ACTION="uninstall"; shift ;;
        --purge)     ACTION="purge"; shift ;;
        -h|--help)   usage; exit 0 ;;
        *)           die "unknown option: $1  (try --help)" ;;
    esac
done
INSTALL_DIR="${INSTALL_DIR:-$DEFAULT_DIR}"

# With a domain, Caddy owns 80 and 443 and the dashboard's nginx moves to
# loopback behind it. Without one, nginx is the only thing listening.
# embedded-db is what runs Postgres in the stack. Leaving it out is how you
# point the platform at a managed database instead.
if [ -n "$DOMAIN" ]; then
    BASE_URL="https://${DOMAIN}"
    BIND="127.0.0.1"
    PORT="8080"
    APP_ENV="production"
    PROFILES="embedded-db,tls"
    CHECK_PORTS="80 443"
else
    # Port 80 is implicit in a URL, and a URL with ":80" in it looks wrong to
    # everyone who reads it.
    if [ "$PORT" = "80" ]; then BASE_URL="http://localhost"; else BASE_URL="http://localhost:${PORT}"; fi
    BIND="0.0.0.0"
    APP_ENV="development"
    PROFILES="embedded-db"
    CHECK_PORTS="$PORT"
fi

# --- compose, however it happens to be installed ----------------------------
# Both spellings are still in the wild: `docker compose` is the v2 CLI plugin,
# `docker-compose` the standalone binary that most distributions package and
# that plenty of servers still run. Resolve it once, then never think about it
# again — including in the helper script written into the install directory.
COMPOSE_CMD=""
resolve_compose() {
    [ -n "$COMPOSE_CMD" ] && return 0
    if docker compose version >/dev/null 2>&1; then
        COMPOSE_CMD="docker compose"
    elif command -v docker-compose >/dev/null 2>&1 && docker-compose version >/dev/null 2>&1; then
        COMPOSE_CMD="docker-compose"
    else
        return 1
    fi
}
compose() {
    resolve_compose || die "Docker Compose v2 is not available (tried 'docker compose' and 'docker-compose')."
    # Unquoted on purpose: COMPOSE_CMD is either one word or two, and this is
    # the one place that has to expand to both.
    # shellcheck disable=SC2086
    $COMPOSE_CMD "$@"
}

# ---------------------------------------------------------------------------
# System checks. Every one of these has a remedy printed with it — a check that
# only says "no" makes the install someone else's problem.
# ---------------------------------------------------------------------------
check_system() {
    step "Checking this machine"
    local fail=0

    if ! command -v docker >/dev/null 2>&1; then
        say "  ${RED}x${N} Docker is not installed"
        say "      Install it: ${DIM}https://docs.docker.com/engine/install/${N}"
        fail=1
    elif ! docker info >/dev/null 2>&1; then
        say "  ${RED}x${N} Docker is installed but the daemon is not reachable"
        say "      Start it, or add yourself to the docker group:"
        say "      ${DIM}sudo usermod -aG docker \$USER && newgrp docker${N}"
        fail=1
    else
        ok "Docker $(docker version --format '{{.Server.Version}}' 2>/dev/null || echo '(version unknown)')"
    fi

    if [ "$fail" = "0" ]; then
        if resolve_compose; then
            ok "Compose $(compose version --short 2>/dev/null || echo '(version unknown)') via '${COMPOSE_CMD}'"
        else
            say "  ${RED}x${N} Docker Compose v2 is not available"
            say "      Neither 'docker compose' nor 'docker-compose' works here."
            say "      Install it: ${DIM}https://docs.docker.com/compose/install/${N}"
            fail=1
        fi
    fi

    # Memory. Two JVMs, a broker, a database and a cache — 4 GiB is where this
    # stops swapping, and below 2 GiB the JVMs will not both start.
    local mem_kb mem_gb
    mem_kb=$(awk '/MemTotal/ {print $2}' /proc/meminfo 2>/dev/null || echo 0)
    if [ "$mem_kb" -gt 0 ]; then
        mem_gb=$(( mem_kb / 1024 / 1024 ))
        if [ "$mem_gb" -lt 2 ]; then
            say "  ${RED}x${N} ${mem_gb} GiB of RAM — the stack needs about 4 GiB and will not start in this"
            fail=1
        elif [ "$mem_gb" -lt 4 ]; then
            warn "${mem_gb} GiB of RAM — tight. About 4 GiB is comfortable; expect swapping."
        else
            ok "${mem_gb} GiB of RAM"
        fi
    fi

    # Disk. Postgres plus the Kafka log plus five images.
    local target avail_gb
    target="$INSTALL_DIR"
    while [ ! -d "$target" ] && [ "$target" != "/" ]; do target=$(dirname "$target"); done
    avail_gb=$(df -BG --output=avail "$target" 2>/dev/null | tail -1 | tr -dc '0-9' || echo "")
    if [ -n "$avail_gb" ]; then
        if [ "$avail_gb" -lt 5 ]; then
            say "  ${RED}x${N} ${avail_gb} GiB free on $target — images alone need about 5 GiB"
            fail=1
        else
            ok "${avail_gb} GiB free on $target"
        fi
    fi

    # Only meaningful before installing. Run against an installation that is
    # already up — which is what `railhook doctor` does — the ports are in use
    # by the very stack being checked, and calling that a failure would mean
    # doctor can never pass on a healthy install.
    if [ -f "${INSTALL_DIR}/docker-compose.yml" ]; then
        say "  ${DIM}Ports not checked — ${INSTALL_DIR} is an existing installation${N}"
    elif [ "$START" = "0" ]; then
        say "  ${DIM}Ports not checked — nothing is being started (--no-start)${N}"
    else
        local p
        for p in $CHECK_PORTS; do
            if port_in_use "$p"; then
                say "  ${RED}x${N} Port ${p} is already in use"
                if [ -n "$DOMAIN" ]; then
                    say "      HTTPS needs 80 and 443. Stop whatever holds it, or put"
                    say "      Railhook behind your existing proxy without --domain."
                else
                    say "      Pick another: ${DIM}--port 8080${N}"
                fi
                fail=1
            fi
        done
        [ "$fail" = "0" ] && ok "Port(s) ${CHECK_PORTS} free"
    fi

    [ "$fail" = "0" ] || die "The checks above have to pass before anything is installed."
}

port_in_use() {
    local p=$1
    if command -v ss >/dev/null 2>&1; then
        ss -lnt 2>/dev/null | awk '{print $4}' | grep -qE "[:.]${p}$"
    elif command -v lsof >/dev/null 2>&1; then
        lsof -iTCP:"$p" -sTCP:LISTEN >/dev/null 2>&1
    else
        return 1   # cannot tell; Compose will say so when it binds
    fi
}

# ---------------------------------------------------------------------------
# Configuration checks. These run against the files on disk, so they catch a
# hand-edit as well as a bad install — which is the case that actually happens.
# ---------------------------------------------------------------------------
check_config() {
    step "Checking the configuration"
    local env_file="${INSTALL_DIR}/.env"
    local compose_file="${INSTALL_DIR}/docker-compose.yml"
    local fail=0

    [ -f "$compose_file" ] || die "No docker-compose.yml in ${INSTALL_DIR} — nothing installed there yet."
    [ -f "$env_file" ]     || die "No .env in ${INSTALL_DIR} — nothing installed there yet."

    # Compose's own validation: catches a truncated download, a bad edit, and
    # any required variable that ended up unset.
    if (cd "$INSTALL_DIR" && compose config -q >/dev/null 2>&1); then
        ok "docker-compose.yml parses and every required variable is set"
    else
        say "  ${RED}x${N} Compose rejects the configuration:"
        (cd "$INSTALL_DIR" && compose config -q 2>&1 | sed 's/^/      /')
        fail=1
    fi

    local v
    for v in WEBHOOK_ENCRYPTION_KEY WEBHOOK_ENCRYPTION_SALT JWT_SECRET DB_PASSWORD REDIS_PASSWORD; do
        local value
        value=$(grep -E "^${v}=" "$env_file" | head -1 | cut -d= -f2-)
        if [ -z "$value" ]; then
            say "  ${RED}x${N} ${v} is empty"
            fail=1
        elif printf '%s' "$value" | grep -qiE 'change_?me|dev_|webhook_pass|webhook_redis_pass|placeholder'; then
            say "  ${RED}x${N} ${v} still holds a placeholder or a shipped default"
            fail=1
        fi
    done
    [ "$fail" = "0" ] && ok "Secrets are set and none is a shipped default"

    # The one that bites: the database container is created with
    # POSTGRES_PASSWORD, and the API connects with DB_PASSWORD. Disagree, and
    # you get an authentication failure long after the install looked fine.
    local pg db
    pg=$(grep -E '^POSTGRES_PASSWORD=' "$env_file" | head -1 | cut -d= -f2-)
    db=$(grep -E '^DB_PASSWORD=' "$env_file" | head -1 | cut -d= -f2-)
    if [ "$pg" = "$db" ]; then
        ok "POSTGRES_PASSWORD and DB_PASSWORD agree"
    else
        say "  ${RED}x${N} POSTGRES_PASSWORD and DB_PASSWORD differ — the API will not be able to log in"
        fail=1
    fi

    # The database lives behind a Compose profile so that pointing at a managed
    # one is a matter of not enabling it. The failure mode if this line goes
    # missing is a stack that starts and then cannot reach a database it never
    # launched, which reads like a networking problem and is not one.
    if grep -q '^COMPOSE_PROFILES=.*embedded-db' "$env_file"; then
        ok "The embedded database profile is on"
    elif grep -qE '^DB_HOST=..' "$env_file"; then
        say "  ${DIM}embedded-db is off and DB_HOST is set — using an external database${N}"
    else
        say "  ${RED}x${N} Neither COMPOSE_PROFILES=embedded-db nor DB_HOST is set,"
        say "      so nothing will run a database and nothing points at one."
        fail=1
    fi

    # Production hardening, checked only when the operator has said this is
    # production. ProductionSafetyValidator refuses to start on most of these;
    # catching them here means finding out now rather than from a crash loop.
    if grep -qE '^APP_ENV=(production|prod)$' "$env_file"; then
        say "  ${DIM}APP_ENV is production — checking the extra rules${N}"
        grep -qE '^WEBHOOK_ALLOW_PRIVATE_IPS=true$' "$env_file" && {
            say "  ${RED}x${N} WEBHOOK_ALLOW_PRIVATE_IPS=true in production (SSRF risk); the API refuses to start"; fail=1; }
        grep -qE '^SWAGGER_ENABLED=true$' "$env_file" && {
            say "  ${RED}x${N} SWAGGER_ENABLED=true in production; the API refuses to start"; fail=1; }
        grep -qE '^CORS_ALLOWED_ORIGINS=.*localhost' "$env_file" && {
            say "  ${RED}x${N} CORS_ALLOWED_ORIGINS still contains localhost; the API refuses to start"; fail=1; }
        grep -qE '^APP_BASE_URL=http://localhost' "$env_file" && {
            warn "APP_BASE_URL still points at localhost — verification and invite links will be unreachable"; }
        grep -qE '^EMAIL_ENABLED=true$' "$env_file" || {
            warn "EMAIL_ENABLED is off — accounts are created already-verified, and no invites can be sent"; }
    fi

    [ "$fail" = "0" ] || die "Fix the configuration above, then re-run with --check."
    ok "Configuration looks right"
}

resolve_version() {
    if [ -n "$VERSION" ]; then
        say "  Pinning to ${B}${VERSION}${N} (as asked)"
        return
    fi
    VERSION=$(curl -fsSL "https://api.github.com/repos/${REPO}/releases/latest" 2>/dev/null \
        | sed -n 's/.*"tag_name": *"\([^"]*\)".*/\1/p' | head -1 || true)
    if [ -z "$VERSION" ]; then
        die "Could not reach the GitHub API to find the latest release. Pass one: --version v2.5.0"
    fi
    say "  Latest release is ${B}${VERSION}${N}"
}

secret() { openssl rand -base64 "$1" | tr -d '\n'; }
password() { openssl rand -base64 24 | tr -d '\n/+='; }

write_files() {
    step "Writing ${INSTALL_DIR}"
    mkdir -p "$INSTALL_DIR"

    if [ -n "${RAILHOOK_COMPOSE_SRC:-}" ]; then
        # CI, and anyone testing a change: install the Compose file from the
        # working tree instead of the published release, so a PR is tested
        # against its own file rather than the last one that shipped.
        cp "$RAILHOOK_COMPOSE_SRC" "${INSTALL_DIR}/docker-compose.yml" \
            || die "Could not copy ${RAILHOOK_COMPOSE_SRC}."
        ok "docker-compose.yml (from ${RAILHOOK_COMPOSE_SRC})"
    else
        # Pinned to the release tag, not to main. An install that silently
        # changes under you between two `docker compose pull`s is not an install.
        #
        # Two names: releases up to 2.5.0 called the deployment file
        # docker-compose.pull.yml, alongside a build-from-source
        # docker-compose.yml. Those merged into one canonical docker-compose.yml,
        # so try that first and fall back — this installer has to be able to
        # install a release older than the change.
        #
        # The image prefix is matched against both spellings for the same
        # reason. The product was Hookflow until 2.12.0, so every release before
        # it pins ghcr.io/vadymkykalo/hookflow-*; matching only the current name
        # would make this installer unable to install any of them, and the
        # failure would be the confusing one — a fallback to a
        # docker-compose.pull.yml that modern releases do not have, then "could
        # not download the Compose file". The old prefix stays here as long as
        # those releases are installable at all.
        curl -fsSL "${RAW}/${VERSION}/docker-compose.yml" -o "${INSTALL_DIR}/docker-compose.yml" 2>/dev/null \
            && grep -qE 'ghcr\.io/vadymkykalo/(railhook|hookflow)' "${INSTALL_DIR}/docker-compose.yml" \
            || curl -fsSL "${RAW}/${VERSION}/docker-compose.pull.yml" -o "${INSTALL_DIR}/docker-compose.yml" \
            || die "Could not download the Compose file for ${VERSION}."
        ok "docker-compose.yml (pinned to ${VERSION})"
    fi

    # The scheduled-backup sidecar bind-mounts these two. Fetching them is what
    # lets an install have nightly dumps rather than only the on-demand
    # `./railhook backup`.
    mkdir -p "${INSTALL_DIR}/deploy/scripts"
    if curl -fsSL "${RAW}/${VERSION}/deploy/scripts/db-backup.sh" \
            -o "${INSTALL_DIR}/deploy/scripts/db-backup.sh" 2>/dev/null \
       && curl -fsSL "${RAW}/${VERSION}/deploy/scripts/db-backup-loop.sh" \
            -o "${INSTALL_DIR}/deploy/scripts/db-backup-loop.sh" 2>/dev/null; then
        chmod +x "${INSTALL_DIR}/deploy/scripts/"*.sh
        BACKUP_PROFILE=",backup"
        ok "Scheduled backups"
    else
        # Not fatal: `./railhook backup` still works, it is just not automatic.
        BACKUP_PROFILE=""
        warn "Could not fetch the backup scripts — scheduled backups are off"
    fi

    if [ -n "$DOMAIN" ]; then
        cat > "${INSTALL_DIR}/Caddyfile" <<'CADDY'
# Caddy obtains and renews the certificate on its own — there is no cron entry
# to add and no renewal hook to forget. It terminates TLS and hands everything
# to the dashboard's nginx, which still does all the routing; adding HTTPS did
# not move the decision about what is public.
{
	email {$ACME_EMAIL}
}

{$RAILHOOK_DOMAIN} {
	encode gzip zstd

	# One upstream. nginx already separates the dashboard, the API paths, the
	# tunnel WebSocket and the two allow-listed actuator paths.
	reverse_proxy ui:5173 {
		header_up X-Forwarded-Proto https
		# The CLI tunnel holds a WebSocket open for the length of a developer's
		# session, so it must not be cut off at the default idle timeout.
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
        ok "Caddyfile for ${DOMAIN}"
    fi

    if [ -f "${INSTALL_DIR}/.env" ]; then
        warn ".env already exists — keeping it, and the secrets already in it"
        return
    fi

    local db_pass redis_pass
    db_pass=$(password)
    redis_pass=$(password)
    local image_tag="${VERSION#v}"

    # Only when this is going on a domain, i.e. facing the internet.
    PROD_SETTINGS=""
    if [ -n "$DOMAIN" ]; then
        PROD_SETTINGS=$(cat <<'PRODENV'

# Set because this install faces the internet.
# Require TLS to the database — it is loopback-only here, but the moment you
# move to a managed one this is the setting people forget.
DB_SSL_MODE=require
# INFO on a public endpoint is a lot of disk and a lot of payload metadata.
LOG_LEVEL=WARN
# The Docker bridge range, so X-Forwarded-For from the TLS terminator in front
# is trusted and client IPs in the audit log are real.
WEBHOOK_TRUSTED_PROXIES=172.16.0.0/12
PRODENV
)
    fi

    umask 077
    cat > "${INSTALL_DIR}/.env" <<ENVFILE
# Railhook — generated by install.sh on $(date -u +%Y-%m-%dT%H:%M:%SZ).
#
# These five secrets were generated for this installation. Back this file up:
# WEBHOOK_ENCRYPTION_KEY is what every endpoint secret in the database is
# encrypted with, so a database backup without this file restores rows nothing
# can read.
#
# Everything not listed here keeps the default baked into docker-compose.yml.
# The full catalogue of options is documented at
# https://github.com/${REPO}/blob/${VERSION}/.env.dist

WEBHOOK_ENCRYPTION_KEY=$(secret 32)
WEBHOOK_ENCRYPTION_SALT=$(secret 16)
JWT_SECRET=$(secret 48)

# POSTGRES_PASSWORD creates the database user; DB_PASSWORD is what the API and
# worker connect with. They must be the same value.
POSTGRES_PASSWORD=${db_pass}
DB_PASSWORD=${db_pass}
REDIS_PASSWORD=${redis_pass}

# Pinned so that \`docker compose pull\` fetches this release and not a moving
# \`latest\`. Change these together when you upgrade.
API_IMAGE_TAG=${image_tag}
WORKER_IMAGE_TAG=${image_tag}
UI_IMAGE_TAG=${image_tag}

# The port and interface the dashboard's nginx binds to. It serves the
# dashboard and proxies every API path to the api service, so this is the single
# entry point for everything. With a domain configured, Caddy holds 80/443 in
# front and this moves to loopback.
RAILHOOK_BIND=${BIND}
RAILHOOK_PORT=${PORT}

# Empty unless you installed with --domain. Setting it alone does nothing; the
# TLS terminator only runs under the \`tls\` profile, which COMPOSE_PROFILES
# below turns on.
RAILHOOK_DOMAIN=${DOMAIN}
ACME_EMAIL=${ACME_EMAIL}
COMPOSE_PROFILES=${PROFILES}${BACKUP_PROFILE}

# The URL people will actually type. Verification, invite and reset links are
# built from it, so it has to be reachable from their browser — change it to
# https://your.domain before this leaves your machine.
APP_BASE_URL=${BASE_URL}
CORS_ALLOWED_ORIGINS=${BASE_URL}

# production turns on ProductionSafetyValidator, which refuses to start on
# unsafe configuration rather than running with it: shipped-default secrets,
# SSRF protection disabled, Swagger exposed, localhost left in CORS. Installing
# with --domain sets it, because at that point this is reachable from the
# internet.
APP_ENV=${APP_ENV}

# With email off, accounts are created already verified — there would be no way
# to deliver a verification link. Turn it on and set the SMTP_* variables to
# send verification, invite and alert mail.
EMAIL_ENABLED=false
${PROD_SETTINGS}
ENVFILE
    ok ".env with newly generated secrets"
}

write_helper() {
    cat > "${INSTALL_DIR}/railhook" <<'HELPER'
#!/usr/bin/env bash
# Thin wrapper over docker compose, so the everyday commands do not need to be
# looked up. Anything not listed here is passed straight through.
set -euo pipefail
cd "$(dirname "$(readlink -f "$0")")"
# Both spellings, same as install.sh: the v2 plugin and the standalone binary.
if docker compose version >/dev/null 2>&1; then COMPOSE_CMD="docker compose"
elif docker-compose version >/dev/null 2>&1; then COMPOSE_CMD="docker-compose"
else echo "Docker Compose is not available (tried 'docker compose' and 'docker-compose')." >&2; exit 1; fi
# shellcheck disable=SC2086
compose() { $COMPOSE_CMD "$@"; }
case "${1:-help}" in
    start)   compose up -d ;;
    stop)    compose stop ;;
    restart) compose restart ;;
    status)  compose ps ;;
    logs)    shift; compose logs -f "$@" ;;
    upgrade)
        echo "Edit API_IMAGE_TAG / WORKER_IMAGE_TAG / UI_IMAGE_TAG in .env first, then:"
        compose pull && compose up -d ;;
    backup)
        f="backup-$(date -u +%Y%m%dT%H%M%SZ).dump"
        compose exec -T postgres pg_dump -U "${POSTGRES_USER:-webhook_user}" -Fc webhook_platform > "$f"
        echo "wrote $f — keep .env with it, or the encrypted columns are unreadable" ;;
    doctor)  curl -fsSL https://raw.githubusercontent.com/vadymkykalo/railhook/main/install.sh \
                 | bash -s -- --check --dir "$(pwd)" ;;
    help|-h|--help)
        echo "railhook start|stop|restart|status|logs [service]|upgrade|backup|doctor" ;;
    *)       compose "$@" ;;
esac
HELPER
    chmod +x "${INSTALL_DIR}/railhook"
    ok "railhook helper script"
}

start_stack() {
    step "Pulling images"
    (cd "$INSTALL_DIR" && compose pull -q) || die "Could not pull the images."
    ok "Images pulled"

    step "Starting"
    (cd "$INSTALL_DIR" && compose up -d) || die "Compose could not start the stack."

    step "Waiting for the platform to come up"
    say "  ${DIM}First boot runs the database migrations and creates the Kafka topics.${N}"
    local i
    for i in $(seq 1 60); do
        if curl -fsS -o /dev/null "http://127.0.0.1:${PORT}/actuator/health/liveness" 2>/dev/null; then
            ok "API is live"
            break
        fi
        if [ "$i" = "60" ]; then
            say ""
            say "  ${RED}x${N} It did not come up within 10 minutes."
            say "      ${DIM}cd ${INSTALL_DIR} && ./railhook logs${N}"
            exit 1
        fi
        sleep 10
    done
    for i in $(seq 1 30); do
        curl -fsS -o /dev/null "http://127.0.0.1:${PORT}" 2>/dev/null && { ok "Dashboard is serving"; break; }
        sleep 5
    done
}

do_uninstall() {
    [ -f "${INSTALL_DIR}/docker-compose.yml" ] || die "Nothing installed at ${INSTALL_DIR}."
    if [ "$ACTION" = "purge" ]; then
        step "Removing the stack and its data"
        (cd "$INSTALL_DIR" && compose down -v)
        ok "Containers and data volumes removed"
        say ""
        say "  ${INSTALL_DIR} is still there, with your .env. Delete it by hand when you are sure."
    else
        step "Stopping and removing the containers"
        (cd "$INSTALL_DIR" && compose down)
        ok "Containers removed — the data volumes are untouched"
        say ""
        say "  Start again with ${B}cd ${INSTALL_DIR} && ./railhook start${N}"
        say "  To delete the data too: ${DIM}--purge${N}"
    fi
}

finish() {
    say ""
    say "  ${GRN}${B}Railhook is running.${N}"
    say ""
    say "    Dashboard   ${B}${BASE_URL}${N}"
    say "    API         ${BASE_URL}/api/v1"
    say "    Docs        ${BASE_URL}/docs"
    say ""
    say "  ${DIM}One port, one URL. nginx serves the dashboard and proxies the API;${N}"
    say "  ${DIM}nothing else is published to the host.${N}"
    say ""
    say "  Register on the dashboard — the first account is active immediately,"
    say "  because no SMTP is configured and there is no verification mail to wait for."
    say ""
    say "  ${B}${INSTALL_DIR}${N}"
    say "    ./railhook status | logs | stop | start | backup | doctor"
    say "    .env holds your secrets. ${B}Back it up.${N}"
    say ""
    say "  Putting this on a server? ${DIM}docs/SELF_HOSTED_GUIDE.md${N} covers TLS,"
    say "  APP_ENV=production and what to change before it faces the internet."
    say ""
}

main() {
    say ""
    say "  ${B}Railhook${N} ${DIM}— self-hosted webhook infrastructure${N}"
    say ""

    case "$ACTION" in
        check)
            check_system
            check_config
            say ""
            exit 0 ;;
        uninstall|purge)
            do_uninstall
            exit 0 ;;
    esac

    check_system

    if [ -e "${INSTALL_DIR}" ] && [ -n "$(ls -A "$INSTALL_DIR" 2>/dev/null)" ] && [ "$ASSUME_YES" = "0" ]; then
        if [ -f "${INSTALL_DIR}/docker-compose.yml" ]; then
            warn "${INSTALL_DIR} already has an installation — it will be updated, and .env kept"
        else
            die "${INSTALL_DIR} exists and is not empty. Use --dir, or --yes to go ahead anyway."
        fi
    fi

    step "Finding the release to install"
    resolve_version
    write_files
    write_helper
    check_config

    if [ "$START" = "0" ]; then
        say ""
        say "  Files written, nothing started (--no-start)."
        say "  ${B}cd ${INSTALL_DIR} && ./railhook start${N}"
        say ""
        exit 0
    fi

    start_stack
    finish
}

main
