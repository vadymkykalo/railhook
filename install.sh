#!/usr/bin/env bash
#
# Railhook installer.
#
#   curl -fsSL https://railhook.io/install.sh | bash
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
PORT=""
DOMAIN=""
ACME_EMAIL=""
BEHIND_PROXY=0
# The oldest release this installer can install. Earlier ones are unsupported
# (SECURITY.md) and were published as Hookflow, under image and variable names
# this installer no longer writes.
MIN_MAJOR=2
MIN_MINOR=12
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

  curl -fsSL https://railhook.io/install.sh | bash

${B}Options${N}
  --dir <path>       Where to install          (default: ${DEFAULT_DIR})
  --version <tag>    Release to pin, v${MIN_MAJOR}.${MIN_MINOR}.0 or newer (default: the latest release)
  --port <port>      The one published port    (default: 80; 8080 with --behind-proxy)
  --domain <host>    Serve on this domain over HTTPS. Turns on a TLS
                     terminator that obtains and renews the certificate
                     itself, and switches the platform to production mode.
  --behind-proxy     With --domain: your own reverse proxy terminates TLS.
                     Same production settings, no built-in TLS terminator;
                     the dashboard listens on 127.0.0.1:<port> for your proxy.
  --email <address>  Where Let's Encrypt should send expiry warnings
  --no-start         Write the files, do not start anything
  --yes              Do not ask before reusing a non-empty directory
  --check            Run the system and configuration checks only, change nothing
  --uninstall        Stop the stack and remove the containers (keeps your data)
  --purge            Uninstall, and delete the data volumes as well
  -h, --help         This text

${B}Passing options through a pipe${N}
  curl -fsSL https://railhook.io/install.sh | bash -s -- --domain hooks.example.com --behind-proxy
USAGE
}

while [ $# -gt 0 ]; do
    case "$1" in
        --dir)       INSTALL_DIR="${2:?--dir needs a path}"; shift 2 ;;
        --version)   VERSION="${2:?--version needs a tag}"; shift 2 ;;
        --port)      PORT="${2:?--port needs a port}"; shift 2 ;;
        --domain)    DOMAIN="${2:?--domain needs a hostname}"; shift 2 ;;
        --email)     ACME_EMAIL="${2:?--email needs an address}"; shift 2 ;;
        --behind-proxy) BEHIND_PROXY=1; shift ;;
        --no-start)  START=0; shift ;;
        --yes|-y)    ASSUME_YES=1; shift ;;
        --check)     ACTION="check"; shift ;;
        # Rewrites only the helper script, for an installation that already exists.
        # `railhook upgrade` calls this on itself so that a release which changes the
        # helper reaches an existing host on the deploy that ships it, rather than the
        # one after.
        --refresh)   ACTION="refresh"; shift ;;
        --uninstall) ACTION="uninstall"; shift ;;
        --purge)     ACTION="purge"; shift ;;
        -h|--help)   usage; exit 0 ;;
        *)           die "unknown option: $1  (try --help)" ;;
    esac
done
INSTALL_DIR="${INSTALL_DIR:-$DEFAULT_DIR}"

# Refused here, before anything is checked, written or fetched.
require_supported_version() {
    local v="${VERSION#v}"
    [[ "$v" =~ ^([0-9]+)\.([0-9]+)\.[0-9]+$ ]] \
        || die "--version ${VERSION} is not a release tag. Pass one like v${MIN_MAJOR}.${MIN_MINOR}.0, or leave it out for the latest."
    local major="${BASH_REMATCH[1]}" minor="${BASH_REMATCH[2]}"
    if [ "$major" -lt "$MIN_MAJOR" ] || { [ "$major" -eq "$MIN_MAJOR" ] && [ "$minor" -lt "$MIN_MINOR" ]; }; then
        die "${VERSION} is older than v${MIN_MAJOR}.${MIN_MINOR}.0, the oldest release this installer supports. Pass v${MIN_MAJOR}.${MIN_MINOR}.0 or newer, or leave --version out for the latest."
    fi
}
# RAILHOOK_COMPOSE_SRC installs a working tree's Compose file rather than a
# release's, so --version there only names the image tag being tested.
if [ -n "$VERSION" ] && [ -z "${RAILHOOK_COMPOSE_SRC:-}" ]; then
    require_supported_version
fi

if [ "$BEHIND_PROXY" = "1" ]; then
    [ -n "$DOMAIN" ] || die "--behind-proxy needs --domain: the public hostname your proxy serves. Every link the platform builds uses it."
    [ -z "$ACME_EMAIL" ] || die "--email is for the built-in certificate. Behind your own proxy, the proxy holds the certificate — drop --email."
fi

# Three shapes, one per flag combination:
#   no domain                  nginx is the only thing listening, on every interface.
#   --domain                   Caddy owns 80 and 443; nginx moves to loopback behind it.
#   --domain --behind-proxy    the operator's proxy terminates TLS; nginx on loopback
#                              for it, and no Caddy.
# embedded-db is what runs Postgres in the stack. Leaving it out is how you
# point the platform at a managed database instead.
TLS=0
if [ -n "$DOMAIN" ]; then
    BASE_URL="https://${DOMAIN}"
    BIND="127.0.0.1"
    APP_ENV="production"
    if [ "$BEHIND_PROXY" = "1" ]; then
        PORT="${PORT:-8080}"
        PROFILES="embedded-db"
        CHECK_PORTS="$PORT"
    else
        TLS=1
        PORT="8080"
        PROFILES="embedded-db,tls"
        CHECK_PORTS="80 443"
    fi
else
    PORT="${PORT:-80}"
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
                if [ "$TLS" = "1" ]; then
                    say "      HTTPS needs 80 and 443. Stop whatever holds it, or, if that is"
                    say "      your own reverse proxy, add ${DIM}--behind-proxy${N} and point it at 127.0.0.1:8080."
                elif [ "$BEHIND_PROXY" = "1" ]; then
                    say "      Pick another loopback port for your proxy: ${DIM}--port 8081${N}"
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
        # The image check catches a 200 that is not a Compose file at all.
        if ! curl -fsSL "${RAW}/${VERSION}/docker-compose.yml" -o "${INSTALL_DIR}/docker-compose.yml" \
           || ! grep -q 'ghcr\.io/vadymkykalo/railhook' "${INSTALL_DIR}/docker-compose.yml"; then
            die "Could not download the Compose file for ${VERSION}."
        fi
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

    if [ "$TLS" = "1" ]; then
        write_caddyfile
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
# The Docker bridge range, so X-Forwarded-For from the proxy in front — the
# built-in Caddy, or your own — is trusted and client IPs in the audit log are real.
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
# entry point for everything. With a domain, TLS is terminated in front — by
# the built-in Caddy on 80/443, or by your own proxy with --behind-proxy — and
# this is loopback.
RAILHOOK_BIND=${BIND}
RAILHOOK_PORT=${PORT}

# For the built-in TLS terminator only, so empty without --domain and with
# --behind-proxy. Setting it alone does nothing; the terminator only runs under
# the \`tls\` profile, which COMPOSE_PROFILES below turns on.
RAILHOOK_DOMAIN=$([ "$TLS" = "1" ] && printf '%s' "$DOMAIN")
ACME_EMAIL=${ACME_EMAIL}
COMPOSE_PROFILES=${PROFILES}${BACKUP_PROFILE}

# The URL people will actually type. Verification, invite and reset links are
# built from it, so it has to be reachable from their browser. --domain sets it
# to https://<domain>.
APP_BASE_URL=${BASE_URL}
CORS_ALLOWED_ORIGINS=${BASE_URL}

# production turns on ProductionSafetyValidator, which refuses to start on
# unsafe configuration rather than running with it: shipped-default secrets,
# SSRF protection disabled, Swagger exposed, localhost left in CORS. Installing
# with --domain sets it, with or without --behind-proxy, because at that point
# this is reachable from the internet.
APP_ENV=${APP_ENV}

# With email off, accounts are created already verified — there would be no way
# to deliver a verification link. Turn it on and set the SMTP_* variables to
# send verification, invite and alert mail.
EMAIL_ENABLED=false
${PROD_SETTINGS}
ENVFILE
    ok ".env with newly generated secrets"
}

# Written by both the install and the refresh paths. The refresh exists because this
# file, like the helper, was written once at install time and never replaced — so a
# release that changed it reached only new installations. The `lb_try_duration` that
# stops a UI restart answering 502 shipped in 2.16.3 and was still absent from the
# production Caddyfile after deploying it.
write_caddyfile() {
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
		# The UI container is the only upstream, and it is replaced whenever its
		# image changes — a few seconds during which a dial is refused and every
		# path, /hook and /ingress included, answered 502. Retrying turns that
		# into a slow request instead. Safe for any method: a refused dial means
		# nothing was written, so there is nothing to send twice. 30s rather than
		# 20s: a slow image swap outlasting the window is exactly a 502.
		lb_try_duration 30s
		lb_try_interval 250ms
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

    # Grafana from the optional monitoring stack (`./railhook monitoring up`), when .env names
    # a host for it. Read from .env on every write, so a refresh keeps the block and an
    # installation that never set MONITORING_DOMAIN never gets one.
    local monitoring_domain=""
    if [ -f "${INSTALL_DIR}/.env" ]; then
        monitoring_domain=$(grep '^MONITORING_DOMAIN=' "${INSTALL_DIR}/.env" | tail -1 | cut -d= -f2- | tr -d "\"'" || true)
    fi
    [ -n "$monitoring_domain" ] || return 0
    # It is written into the config verbatim, so anything but a hostname is refused.
    if [[ ! "$monitoring_domain" =~ ^[A-Za-z0-9]([A-Za-z0-9.-]*[A-Za-z0-9])?$ ]]; then
        warn "MONITORING_DOMAIN is not a hostname — Grafana is not added to the Caddyfile"
        return 0
    fi
    # A certificate the operator provides, for a host name Let's Encrypt cannot reach through
    # the proxy in front of it. Both files or neither; paths are inside the Caddy container.
    local tls_cert tls_key tls_line=""
    tls_cert=$(grep '^MONITORING_TLS_CERT=' "${INSTALL_DIR}/.env" | tail -1 | cut -d= -f2- | tr -d "\"'" || true)
    tls_key=$(grep '^MONITORING_TLS_KEY=' "${INSTALL_DIR}/.env" | tail -1 | cut -d= -f2- | tr -d "\"'" || true)
    if [ -n "$tls_cert" ] && [ -n "$tls_key" ]; then
        if [[ "$tls_cert" =~ ^/[A-Za-z0-9._/-]+$ && "$tls_key" =~ ^/[A-Za-z0-9._/-]+$ ]]; then
            tls_line=$'\ttls '"${tls_cert} ${tls_key}"$'\n'
        else
            warn "MONITORING_TLS_CERT or MONITORING_TLS_KEY is not a plain absolute path — Caddy obtains Grafana's certificate itself"
        fi
    fi
    cat >> "${INSTALL_DIR}/Caddyfile" <<CADDY

# Grafana, from the monitoring stack. Its own login is the second lock: put an identity-aware
# proxy in front of this host name (Cloudflare Access, for one) as the first.
${monitoring_domain} {
${tls_line}	encode gzip zstd
	# Resolved per request, so Caddy starts and serves the platform with the stack stopped;
	# this host name alone answers 502 until it is up.
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

# A rewritten Caddyfile is not a Caddyfile Caddy is using.
#
# It is a bind mount, so `compose up -d` sees no change in the service and does not
# recreate the container: Caddy goes on serving whatever it parsed at startup, and the
# new file sits on disk until something unrelated restarts it. That is how the retry
# added in 2.16.3 reached production and did nothing.
#
# Validated first — a reload that fails has already torn nothing down, but a validate
# that fails says so before anything is attempted. Neither failing aborts the upgrade:
# the config Caddy already has is still serving, and the images are what the operator
# asked for.
reload_caddy() {
    resolve_compose || return 0
    # No domain, no Caddy. Nothing to reload, and not an error.
    [ -n "$(cd "$INSTALL_DIR" && compose ps -q caddy 2>/dev/null)" ] || return 0

    if ! (cd "$INSTALL_DIR" && compose exec -T caddy \
            caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile) >/dev/null 2>&1; then
        warn "the new Caddyfile does not validate — leaving Caddy on the config it has"
        return 0
    fi
    if (cd "$INSTALL_DIR" && compose exec -T caddy \
            caddy reload --config /etc/caddy/Caddyfile --adapter caddyfile) >/dev/null 2>&1; then
        ok "Caddy reloaded"
    else
        warn "Caddy would not reload — it is still serving the config it had"
    fi
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
RAW="https://raw.githubusercontent.com/vadymkykalo/railhook"
# shellcheck disable=SC2086
compose() { $COMPOSE_CMD "$@"; }

# Replaces the API containers one at a time, so nothing is asked for work while it
# is still starting.
#
# With one replica there is nothing to roll behind and this is an ordinary restart —
# about twenty seconds of 502, which is the JVM booting and not something a proxy can
# paper over. Set API_REPLICAS=2 in .env and it becomes seamless.
#
# The part that is not obvious: Docker's embedded DNS publishes a container's address
# the moment the container exists and does not withhold it while the healthcheck is
# still failing. So simply scaling up hands nginx a share of live traffic for a cold
# JVM. The replacement is therefore created stopped, attached under a throwaway alias,
# started, waited for, and only then given the name nginx resolves.
roll_api() {
    local ids target net new before after
    ids=$(compose ps -q api || true)
    target=$(printf '%s\n' "$ids" | grep -c . || true)

    # Only an API that is not running has nothing to roll. One replica is the default and
    # the common case, and it is rolled the same way as any other count: the loop below
    # borrows a second container for the length of the swap and removes the old one after,
    # so the host is back to one when it finishes. Returning early here instead — which is
    # what `-le 1` did — sent exactly the default installation down the restart-in-place
    # path, which is the downtime this function exists to remove.
    if [ "${target:-0}" -lt 1 ]; then
        compose up -d --no-deps api
        return
    fi

    net=$(docker network ls --format '{{.Name}}' | grep -E 'webhook-network$' | head -1)
    if [ -z "$net" ]; then
        echo "Could not find the webhook network; restarting the API instead of rolling it."
        compose up -d --no-deps api
        return
    fi

    for old in $ids; do
        before=$(compose ps -aq api | sort)
        compose create --no-recreate --scale api=$((target + 1)) api >/dev/null 2>&1
        after=$(compose ps -aq api | sort)
        new=$(comm -13 <(echo "$before") <(echo "$after") | head -1)
        [ -n "$new" ] || { echo "Compose created no replacement; leaving the API alone."; return 1; }

        docker network disconnect "$net" "$new" >/dev/null 2>&1 || true
        docker network connect --alias api-warming "$net" "$new"
        docker start "$new" >/dev/null
        echo "  warming ${new:0:12} — out of rotation until it answers"

        local waited=0
        while [ "$waited" -lt 180 ]; do
            case "$(docker inspect -f '{{.State.Health.Status}}' "$new" 2>/dev/null || echo gone)" in
                healthy) break ;;
                unhealthy) echo "  ${new:0:12} came up unhealthy — stopping here, the old one is still serving"; return 1 ;;
            esac
            sleep 3; waited=$((waited + 3))
        done
        [ "$waited" -lt 180 ] || { echo "  ${new:0:12} never became healthy — the old one is still serving"; return 1; }

        # An alias is fixed at connect time, so this is a reconnect. Safe precisely
        # because nothing is routed to it yet.
        docker network disconnect "$net" "$new"
        docker network connect --alias api "$net" "$new"
        echo "  ${new:0:12} is serving"

        # Long enough for nginx to have re-resolved before the old one stops answering.
        sleep 8

        # SIGTERM: server.shutdown=graceful drains what is in flight, and nginx has
        # already stopped sending it anything new.
        docker stop "$old" >/dev/null 2>&1 || true
        docker rm -f "$old" >/dev/null 2>&1 || true
        echo "  drained and removed ${old:0:12}"
    done
}

# Applies KEY=VALUE lines from stdin to .env: how a deploy hands this host its settings, so
# nobody edits .env over a root shell. All or nothing — one refused line and nothing is written.
#
# Values are never printed and never pass through sed, so a password with | & / or $ in it
# is written exactly as sent.
#
# Never from a deploy: the encryption key and salt, because a new one leaves every encrypted
# column unreadable; the JWT and database passwords, which this host generated and Postgres
# and Redis already hold; and the image tags, which belong to the version being upgraded to.
apply_settings() {
    local line key lineno=0 current status tmp
    local -A wanted=()
    local -a order=()
    while IFS= read -r line || [ -n "$line" ]; do
        lineno=$((lineno + 1))
        line="${line%$'\r'}"
        case "$line" in ''|'#'*) continue ;; esac
        key="${line%%=*}"
        if [ "$key" = "$line" ] || [[ ! "$key" =~ ^[A-Z][A-Z0-9_]*$ ]]; then
            echo "settings: line ${lineno} is not NAME=value — nothing applied" >&2
            return 1
        fi
        case "$key" in
            WEBHOOK_ENCRYPTION_KEY|WEBHOOK_ENCRYPTION_SALT|JWT_SECRET|POSTGRES_PASSWORD|DB_PASSWORD|REDIS_PASSWORD|API_IMAGE_TAG|WORKER_IMAGE_TAG|UI_IMAGE_TAG)
                echo "settings: ${key} cannot be set by a deploy — nothing applied" >&2
                return 1 ;;
        esac
        [ -n "${wanted[$key]+set}" ] || order+=("$key")
        wanted[$key]="${line#*=}"
    done
    [ "${#order[@]}" -gt 0 ] || { echo "settings: none sent"; return 0; }

    tmp=$(mktemp ./.env.settings.XXXXXX)
    local -A seen=()
    if [ -f .env ]; then
        while IFS= read -r line || [ -n "$line" ]; do
            key="${line%%=*}"
            if [ "$key" != "$line" ] && [ -n "${wanted[$key]+set}" ] && [ -z "${seen[$key]+set}" ]; then
                seen[$key]="${line#*=}"
                printf '%s=%s\n' "$key" "${wanted[$key]}"
            else
                printf '%s\n' "$line"
            fi
        done < .env > "$tmp"
        chmod --reference=.env "$tmp"
    else
        chmod 600 "$tmp"
    fi
    for key in "${order[@]}"; do
        if [ -z "${seen[$key]+set}" ]; then
            printf '%s=%s\n' "$key" "${wanted[$key]}" >> "$tmp"
            status="added"
        else
            current="${seen[$key]}"
            if [ "$current" = "${wanted[$key]}" ]; then status="unchanged"; else status="changed"; fi
        fi
        echo "settings: ${key} ${status}"
    done
    mv -f "$tmp" .env
}

# ── The optional monitoring stack ─────────────────────────────────────────────────────────
#
# Prometheus, Alertmanager, Grafana, Loki and the host exporters, from monitoring/ in the
# repository, as a second Compose project beside this one. Off until `monitoring up`; nothing
# about the platform depends on it.
#
# Its files are fetched for the release this host runs, not written by install.sh: there are
# dashboards of several hundred kilobytes among them. The list is held equal to the directory
# by a test, so a file added there cannot be forgotten here.
MONITORING_FILES="
alertmanager/render-config.sh
backup-age/backup-age.sh
blackbox/blackbox.yml
docker-compose.yml
grafana/NOTICE
grafana/dashboards/jvm-micrometer.json
grafana/dashboards/kafka-consumer.json
grafana/dashboards/node-exporter-full.json
grafana/dashboards/railhook-alerts.json
grafana/dashboards/railhook-containers.json
grafana/dashboards/railhook-errors.json
grafana/dashboards/railhook-logs.json
grafana/dashboards/railhook-overview.json
grafana/dashboards/railhook-uptime.json
grafana/dashboards/railhook-worker.json
grafana/entrypoint.sh
grafana/provisioning/dashboards/dashboards.yml
grafana/provisioning/datasources/datasource.yml
loki/loki-config.yml
loki/rules/railhook.yml
prometheus/alerts.yml
prometheus/host-alerts.yml
prometheus/prometheus.yml
prometheus/render-targets.sh
promtail/promtail-config.yml
"

# One value from .env, unquoted; empty when it is not there.
env_value() {
    [ -f .env ] || return 0
    { grep "^$1=" .env || true; } | tail -1 | cut -d= -f2- | sed -e 's/^"\(.*\)"$/\1/' -e "s/^'\(.*\)'$/\1/"
}

monitoring_ref() {
    local tag
    tag=$(env_value API_IMAGE_TAG)
    echo "${RAILHOOK_MONITORING_REF:-v${tag#v}}"
}

# The network this installation's Compose project created, which the stack joins as external.
# Asked of Docker by label, because its name follows the directory the project runs from.
monitoring_network() {
    local net project
    net=$(env_value RAILHOOK_NETWORK)
    if [ -z "$net" ]; then
        project=$(compose config 2>/dev/null | sed -n 's/^name: //p' | head -1 || true)
        net=$(docker network ls --filter "label=com.docker.compose.network=webhook-network" \
                ${project:+--filter "label=com.docker.compose.project=${project}"} \
                --format '{{.Name}}' 2>/dev/null | head -1 || true)
    fi
    echo "${net:-railhook_webhook-network}"
}

# -p because .env may set COMPOSE_PROJECT_NAME to this installation's own name, and a `down`
# under that name would stop the platform.
monitoring_compose() {
    RAILHOOK_NETWORK="$(monitoring_network)" \
        $COMPOSE_CMD -p railhook-monitoring --env-file .env -f monitoring/docker-compose.yml "$@"
}

# All files or none: a half-fetched directory is a stack that fails in a way nobody can read.
monitoring_fetch() {
    local ref="$1" tmp f
    tmp=$(mktemp -d ./.monitoring.XXXXXX)
    for f in $MONITORING_FILES; do
        mkdir -p "${tmp}/$(dirname "$f")"
        if ! curl -fsSL "${RAW}/${ref}/monitoring/${f}" -o "${tmp}/${f}"; then
            rm -rf "$tmp"
            echo "Could not fetch monitoring/${f} for ${ref}; monitoring/ is unchanged." >&2
            return 1
        fi
    done
    chmod 755 "$tmp"
    rm -rf monitoring.previous
    [ ! -d monitoring ] || mv monitoring monitoring.previous
    mv "$tmp" monitoring
    echo "monitoring/ fetched for ${ref}"
}

monitoring_up() {
    local pw domain ref
    pw=$(env_value GRAFANA_ADMIN_PASSWORD)
    case "$pw" in
        ''|admin|railhook_monitor_2024)
            echo "Set GRAFANA_ADMIN_PASSWORD in .env first — there is no default:" >&2
            echo "  echo \"GRAFANA_ADMIN_PASSWORD=\$(openssl rand -base64 24)\" >> .env" >&2
            return 1 ;;
    esac
    if [ "${#pw}" -lt 16 ]; then
        echo "GRAFANA_ADMIN_PASSWORD is shorter than 16 characters." >&2
        return 1
    fi

    ref=$(monitoring_ref)
    [ -f monitoring/docker-compose.yml ] || monitoring_fetch "$ref" || return 1

    # Grafana on its own host name goes through the built-in Caddy. install.sh is what writes
    # the Caddyfile, so it is asked to rewrite it — and then this helper, which that rewrite
    # replaces, starts again from the top, the way `upgrade` does.
    # A certificate named after the block was written needs the same rewrite.
    domain=$(env_value MONITORING_DOMAIN)
    tls_cert=$(env_value MONITORING_TLS_CERT)
    if [ -n "$domain" ] && [ -f Caddyfile ] \
       && { ! grep -qxF "${domain} {" Caddyfile || { [ -n "$tls_cert" ] && ! grep -qF "tls ${tls_cert} " Caddyfile; }; } \
       && [ -z "${RAILHOOK_HELPER_REFRESHED:-}" ]; then
        echo "Adding ${domain} to the Caddyfile..."
        curl -fsSL "${RAW}/${ref}/install.sh" | bash -s -- --refresh --dir "$(pwd)" \
            || echo "Could not refresh the Caddyfile; Grafana stays on loopback." >&2
        RAILHOOK_HELPER_REFRESHED=1 export RAILHOOK_HELPER_REFRESHED
        exec "$0" monitoring up
    fi

    monitoring_compose up -d --remove-orphans || return 1
    echo
    if [ -n "$domain" ] && [ -f Caddyfile ]; then
        echo "Grafana: https://${domain}/"
    elif [ -n "$domain" ]; then
        echo "Grafana: point your proxy for ${domain} at http://127.0.0.1:$(env_value GRAFANA_PORT | grep . || echo 3001)"
    else
        echo "Grafana: http://127.0.0.1:$(env_value GRAFANA_PORT | grep . || echo 3001) — from elsewhere, ssh -L 3001:127.0.0.1:3001"
    fi
    echo "  login $(env_value GRAFANA_ADMIN_USER | grep . || echo admin), password GRAFANA_ADMIN_PASSWORD in .env"
}

monitoring_status() {
    monitoring_compose ps
    echo
    echo "Prometheus targets (count, job, health):"
    monitoring_compose exec -T prometheus wget -qO- 'http://localhost:9090/api/v1/targets?state=active' 2>/dev/null \
        | grep -oE '"(scrapePool|health)":"[^"]*"' | paste - - \
        | sed -E 's/"scrapePool":"([^"]*)"[[:space:]]+"health":"([^"]*)"/\1 \2/' | sort | uniq -c \
        || echo "  Prometheus is not answering."
}

monitoring() {
    case "${1:-}" in
        up)     monitoring_up ;;
        down)   [ ! -f monitoring/docker-compose.yml ] || monitoring_compose down ;;
        status) monitoring_status ;;
        logs)   shift; monitoring_compose logs -f "$@" ;;
        # What `upgrade` calls: new files for the release, and a restart onto them if running.
        update)
            [ -d monitoring ] || return 0
            monitoring_fetch "${2:-$(monitoring_ref)}" || return 1
            [ -z "$(monitoring_compose ps -q 2>/dev/null)" ] || monitoring_compose up -d --remove-orphans ;;
        *)
            echo "railhook monitoring up|down|status|logs [service]|update [version]" >&2
            echo "  up needs GRAFANA_ADMIN_PASSWORD in .env; MONITORING_DOMAIN serves Grafana through Caddy" >&2
            echo "  down keeps the metrics and logs; data lives in the railhook-monitoring_* volumes" >&2
            return 1 ;;
    esac
}

case "${1:-help}" in
    start)   compose up -d ;;
    stop)    compose stop ;;
    restart) compose restart ;;
    status)  compose ps ;;
    logs)    shift; compose logs -f "$@" ;;
    upgrade)
        # Takes a backup first, because the thing an upgrade does that cannot be undone is run
        # migrations: `compose up -d` with an older tag rolls the images back, and rolls nothing
        # in the database back with them. Flyway has no down-migrations here and never will.
        # Read before anything is rewritten: this is what the rollback hint has to name, and
        # after the sed below it would name the version being upgraded *to*.
        from=$(grep '^API_IMAGE_TAG=' .env | cut -d= -f2- || echo unknown)
        want="${2:-}"

        # This file is written once, at install time, and nothing replaced it. So a
        # release that changed the helper — the rolling upgrade below, for one —
        # reached an existing host only on the deploy *after* the one that shipped it,
        # and the deploy meant to prove the fix restarted the API in place instead.
        #
        # Fetched and re-exec'd rather than edited in place: bash reads a script as it
        # runs it, so rewriting the file underneath itself runs half of one version and
        # half of the other. The guard stops the new copy doing this again.
        #
        # A failed fetch is not a reason to refuse an upgrade — it carries on with the
        # helper it has, and says so.
        if [ -z "${RAILHOOK_HELPER_REFRESHED:-}" ] && [ -n "$want" ]; then
            echo "Updating the helper for ${want} before upgrading..."
            # Not silenced. The refresh writes the helper, rewrites the Caddyfile and
            # reloads Caddy, and all of it used to go to /dev/null — so a deploy log
            # could not answer "did the Caddyfile update" or "did the reload fail", and
            # the only way to find out was to ssh in and read Caddy's own logs. Which is
            # the thing this chain exists to stop anyone having to do.
            if curl -fsSL "${RAW}/v${want#v}/install.sh" \
                 | bash -s -- --refresh --dir "$(pwd)"; then
                echo "Helper updated. Continuing with it."
            else
                echo "Could not fetch the helper for ${want}; continuing with this one." >&2
            fi
            RAILHOOK_HELPER_REFRESHED=1 export RAILHOOK_HELPER_REFRESHED
            exec "$0" upgrade "$want"
        fi

        # Settings sent with the deploy (deploy-prod.yml pipes them into the SSH session),
        # applied by the refreshed helper before anything else changes. The refresh above
        # reads nothing from this stdin: bash -s takes its script, and its children their
        # input, from the curl pipe. An operator at a terminal sends none.
        if [ ! -t 0 ]; then
            apply_settings || { echo "Settings refused — not upgrading, nothing has changed." >&2; exit 1; }
        fi

        if [ -n "$want" ]; then
            # Releases are tagged v2.16.0 in git and the images are published as 2.16.0 —
            # docker/metadata-action writes the version, not the ref. Writing the git tag
            # into *_IMAGE_TAG therefore asked the registry for something that has never
            # existed, and `compose pull` failed with "not found" on a release that was
            # sitting right there. The help text below says `./railhook upgrade v2.13.0`,
            # so the documented usage was the broken one.
            #
            # Accept either spelling and write the one the registry knows.
            image_tag="${want#v}"
            git_ref="v${image_tag}"
            for v in API_IMAGE_TAG WORKER_IMAGE_TAG UI_IMAGE_TAG; do
                if grep -q "^${v}=" .env; then sed -i.bak "s|^${v}=.*|${v}=${image_tag}|" .env
                else echo "${v}=${image_tag}" >> .env; fi
            done
            rm -f .env.bak
            echo "Pinned API/WORKER/UI image tags to ${image_tag}."
        else
            echo "No version given, so whatever the tags in .env already say. Pass one to change them:"
            echo "  ./railhook upgrade v2.13.0"
        fi

        echo "Backing up before anything changes..."
        "$0" backup || { echo "Backup failed — not upgrading. Fix that first." >&2; exit 1; }

        # Refresh the deployment file for the release being installed.
        #
        # Without this an upgrade only ever moved the image tags, so anything a
        # release changed *about* the topology — a new service, a memory limit, the
        # replica count that makes this very rolling path possible — never reached an
        # installation that already existed. It reached new installs only, which is
        # the kind of difference that surfaces as "it works on a fresh box".
        #
        # The previous file is kept beside it. If you have edited yours, diff the two:
        # this replaces it rather than merging, because a merge that got it wrong
        # would be discovered at the worst moment.
        if curl -fsSL "${RAW}/${git_ref:-v${from#v}}/docker-compose.yml" -o docker-compose.yml.new 2>/dev/null; then
            if ! cmp -s docker-compose.yml docker-compose.yml.new; then
                cp docker-compose.yml docker-compose.yml.previous
                mv docker-compose.yml.new docker-compose.yml
                echo "docker-compose.yml updated for ${git_ref:-v${from#v}} (previous kept as docker-compose.yml.previous)"
            else
                rm -f docker-compose.yml.new
            fi
        else
            rm -f docker-compose.yml.new
            echo "Could not fetch docker-compose.yml for ${git_ref:-v${from#v}}; keeping the one on disk."
        fi

        compose pull

        # Everything except the API first, one service per call. All of them in a single
        # `up` left the UI stopped while Compose worked through the rest — about 45 seconds
        # of 502 on the 2.17.0 deploy, longer than Caddy's retry window. On its own, with the
        # image already pulled, the UI swap is a couple of seconds and Caddy's retry covers
        # it. The worker goes last: invisible to a customer while it restarts, since a
        # Delivery is durable in Postgres and Kafka and comes back to the ladder.
        #
        # Only services the active profiles enable. Naming one on the command line switches
        # its profile on, so an install without a domain would start Caddy, and one on an
        # external database would start an empty Postgres.
        active=$(compose config --services)
        up_one() {
            printf '%s\n' "$active" | grep -qx "$1" || return 0
            compose up -d --no-deps "$1" || { echo "Could not start $1 — see ./railhook logs $1" >&2; exit 1; }
        }
        for svc in postgres kafka redis ui caddy worker; do up_one "$svc"; done

        # The API is the one a customer notices, because it is what accepts webhooks.
        roll_api

        # Only where someone turned monitoring on. Its failure is not the upgrade's.
        if [ -f monitoring/docker-compose.yml ] && [ -n "${git_ref:-}" ]; then
            monitoring update "$git_ref" \
                || echo "The monitoring stack was not updated; the platform upgrade is complete." >&2
        fi

        echo
        echo "Upgraded. Watch it come up:  ./railhook status"
        echo "If it does not, the images roll back with:"
        echo "  sed -i 's|^API_IMAGE_TAG=.*|API_IMAGE_TAG=${from}|' .env   # and WORKER_/UI_"
        echo "  ./railhook start"
        echo "The schema does not roll back with them — restore the dump above if a migration"
        echo "is what went wrong." ;;
    backup)
        # Reads .env rather than the invoking shell: POSTGRES_USER and POSTGRES_DB live there,
        # and taking them from the environment meant the defaults below were what actually ran.
        # shellcheck disable=SC1091
        set -a; [ -f .env ] && . ./.env; set +a
        # The dump holds every row of the database: owner-only, not the default 644.
        umask 077
        f="backup-$(date -u +%Y%m%dT%H%M%SZ).dump"
        # Same flags as deploy/scripts/db-backup.sh and the chart's CronJob: -Fc to be
        # restorable with pg_restore at all, --no-owner --no-privileges to restore into a
        # database whose roles differ from this one's, which is every real recovery.
        compose exec -T postgres pg_dump -U "${POSTGRES_USER:-webhook_user}" \
            -d "${POSTGRES_DB:-webhook_platform}" \
            -Fc --no-owner --no-privileges > "$f"
        echo "wrote $f — keep .env with it, or the encrypted columns are unreadable" ;;
    settings) apply_settings ;;
    monitoring) shift; monitoring "$@" ;;
    doctor)  curl -fsSL https://raw.githubusercontent.com/vadymkykalo/railhook/main/install.sh \
                 | bash -s -- --check --dir "$(pwd)" ;;
    help|-h|--help)
        echo "railhook start|stop|restart|status|logs [service]|upgrade [version]|settings < file|backup|doctor|monitoring"
        echo "  monitoring up|down|status runs the optional Prometheus + Grafana stack beside it"
        echo "  upgrade takes a backup first; it does not roll the schema back afterwards"
        echo "  settings applies NAME=value lines to .env; upgrade reads them from stdin too" ;;
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
    if [ "$BEHIND_PROXY" = "1" ]; then
        proxy_hint
    elif [ -z "$DOMAIN" ]; then
        say "  Putting this on a server? Install with ${DIM}--domain <host>${N} for built-in HTTPS,"
        say "  or ${DIM}--domain <host> --behind-proxy${N} behind a reverse proxy you already run."
        say ""
    fi
}

# What is left for the operator after a --behind-proxy install: the proxy itself.
proxy_hint() {
    say "  ${B}Point your proxy at it.${N} Every path for ${DOMAIN} goes to ${B}http://127.0.0.1:${PORT}${N};"
    say "  /ws/tunnel is a WebSocket, so pass the upgrade headers and use a long read timeout."
    say "  Until then the dashboard answers only on http://127.0.0.1:${PORT}."
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
        refresh)
            [ -d "$INSTALL_DIR" ] || die "${INSTALL_DIR} does not exist — nothing to update."
            write_helper
            # Only if one is already there. An installation with no domain never had a
            # Caddyfile and must not acquire one from an upgrade.
            if [ -f "${INSTALL_DIR}/Caddyfile" ]; then
                write_caddyfile
                ok "Caddyfile refreshed"
                reload_caddy
            fi
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
        [ "$BEHIND_PROXY" = "0" ] || proxy_hint
        exit 0
    fi

    start_stack
    finish
}

main
