.PHONY: help up up-external-db up-prod up-prod-external up-pull down down-pull stop clean build rebuild logs logs-api logs-worker logs-ui shell-db backup-db restore-db doctor nuke create-topics health wait-healthy rebuild-api rebuild-worker rebuild-ui restart-api restart-worker restart-ui dev-api dev-worker dev-ui init rebuild-external-db verify-link reset-link invite-link scale-worker scale-api test-ui monitoring-up monitoring-down monitoring-logs monitoring-check-queries ratchets types-check docs-dev docs-build docs-check seo-check prerender version-check version-set

.DEFAULT_GOAL := help

ifneq (,$(wildcard ./.env))
    include .env
    export
endif

DOCKER_COMPOSE := $(shell docker compose version > /dev/null 2>&1 && echo "docker compose" || echo "docker-compose")

GREEN  := \033[0;32m
YELLOW := \033[1;33m
RED    := \033[0;31m
NC     := \033[0m

##@ Help
help: ## Display this help
	@echo "Webhook Platform - Makefile"
	@echo ""
	@awk 'BEGIN {FS = ":.*##"; printf "\nUsage:\n  make \033[36m<target>\033[0m\n"} /^[a-zA-Z_-]+:.*?##/ { printf "  \033[36m%-20s\033[0m %s\n", $$1, $$2 } /^##@/ { printf "\n\033[1m%s\033[0m\n", substr($$0, 5) } ' $(MAKEFILE_LIST)

##@ Lifecycle
init: ## Initialize .env from .env.dist (if not exists)
	@if [ ! -f .env ]; then \
		echo "$(GREEN)Creating .env from .env.dist...$(NC)"; \
		cp .env.dist .env; \
		echo "$(YELLOW)  Using development defaults. CHANGE SECRETS FOR PRODUCTION!$(NC)"; \
	else \
		echo "$(GREEN).env already exists, skipping...$(NC)"; \
	fi

up: init ## Start services (embedded DB, dev mode)
	@echo "$(GREEN)Starting services in embedded DB mode...$(NC)"
	@$(MAKE) doctor
	@$(DOCKER_COMPOSE_BUILD) --profile embedded-db --profile backup up -d --build
	@$(MAKE) wait-healthy
	@$(MAKE) create-topics
	@$(MAKE) health
	@echo ""
	@echo "$(GREEN)Ready — http://localhost:$${RAILHOOK_PORT:-8080}$(NC)"
	@echo "  Dashboard, API, docs and ingress all go through that one port."

up-external-db: init ## Start services (external DB, dev mode)
	@echo "$(GREEN)Starting services in external DB mode...$(NC)"
	@$(MAKE) doctor
	@if [ -z "$(DB_HOST)" ] || [ "$(DB_HOST)" = "CHANGE_ME_DB_HOST" ]; then \
		echo "$(RED)ERROR: DB_HOST must be set for external DB mode$(NC)"; \
		exit 1; \
	fi
	@$(DOCKER_COMPOSE_BUILD) up -d --build
	@$(MAKE) wait-healthy
	@$(MAKE) create-topics
	@echo "$(GREEN)Services started successfully$(NC)"
	@$(MAKE) health

DOCKER_COMPOSE_PROD := $(DOCKER_COMPOSE)

up-prod: init ## Start services (embedded DB, production mode)
	@echo "$(GREEN)Starting services in PRODUCTION mode (embedded DB)...$(NC)"
	@$(MAKE) doctor
	@$(DOCKER_COMPOSE_PROD) --profile embedded-db up -d --no-build
	@$(MAKE) wait-healthy
	@$(MAKE) create-topics
	@echo "$(GREEN)Production services started$(NC)"
	@$(MAKE) health

up-prod-external: init ## Start services (external DB, production mode)
	@echo "$(GREEN)Starting services in PRODUCTION mode (external DB)...$(NC)"
	@$(MAKE) doctor
	@if [ -z "$(DB_HOST)" ] || [ "$(DB_HOST)" = "CHANGE_ME_DB_HOST" ]; then \
		echo "$(RED)ERROR: DB_HOST must be set for external DB mode$(NC)"; \
		exit 1; \
	fi
	@$(DOCKER_COMPOSE_PROD) up -d --no-build
	@$(MAKE) wait-healthy
	@$(MAKE) create-topics
	@echo "$(GREEN)Production services started$(NC)"
	@$(MAKE) health

DOCKER_COMPOSE_PULL := $(DOCKER_COMPOSE)
DOCKER_COMPOSE_BUILD := $(DOCKER_COMPOSE) -f docker-compose.yml -f docker-compose.build.yml

# For testing published images from a clone only: the .env.dist it falls back to holds this
# repository's public secrets. To install, use ./install.sh.
up-pull: ## Start pre-built GHCR images from this clone (to install, use ./install.sh)
	@if [ ! -f .env ]; then \
		echo "$(GREEN)Creating .env from .env.dist...$(NC)"; \
		cp .env.dist .env; \
		echo "$(YELLOW)  Development defaults — the secrets in .env.dist are public.$(NC)"; \
		echo "$(YELLOW)  For a real deployment run ./install.sh instead.$(NC)"; \
	fi
	@echo "$(GREEN)Pulling pre-built images...$(NC)"
	@$(DOCKER_COMPOSE_PULL) pull
	@echo "$(GREEN)Starting services (pull-based, embedded DB/Kafka/Redis)...$(NC)"
	@$(DOCKER_COMPOSE_PULL) up -d
	@echo "$(GREEN)Waiting for the platform to answer...$(NC)"
	@port=$${RAILHOOK_PORT:-80}; elapsed=0; \
	while [ $$elapsed -lt 300 ]; do \
		if curl -sf -o /dev/null http://localhost:$$port/actuator/health/liveness 2>/dev/null \
		   && curl -sf -o /dev/null http://localhost:$$port/ 2>/dev/null; then break; fi; \
		sleep 5; elapsed=$$((elapsed + 5)); \
	done; \
	if [ $$elapsed -ge 300 ]; then \
		echo "$(RED)Did not come up in time — $(DOCKER_COMPOSE_PULL) logs$(NC)"; exit 1; \
	fi; \
	echo "$(GREEN)Started — http://localhost:$$port$(NC)"

down-pull: ## Stop pull-based services (keeps data)
	@echo "$(YELLOW)Stopping pull-based services...$(NC)"
	@$(DOCKER_COMPOSE_PULL) down
	@echo "$(GREEN)Services stopped$(NC)"

down: ## Stop services (keeps data)
	@echo "$(YELLOW)Stopping services...$(NC)"
	@$(DOCKER_COMPOSE) --profile embedded-db down 2>/dev/null || true
	@echo "$(GREEN)Services stopped$(NC)"

stop: ## Stop services (alias for down)
	@$(MAKE) down

clean: ## Stop services and remove containers (keeps volumes)
	@echo "$(YELLOW)Cleaning up containers...$(NC)"
	@$(DOCKER_COMPOSE) --profile embedded-db down --remove-orphans 2>/dev/null || true
	@echo "$(GREEN)Cleanup complete (volumes preserved)$(NC)"

##@ Build
build: ## Build all Docker images
	@echo "$(GREEN)Building Docker images...$(NC)"
	@$(DOCKER_COMPOSE_BUILD) build --no-cache

rebuild: ## Rebuild and restart services (embedded DB)
	@echo "$(GREEN)Rebuilding services...$(NC)"
	@$(DOCKER_COMPOSE) --profile embedded-db down
	@$(DOCKER_COMPOSE_BUILD) build --no-cache
	@$(DOCKER_COMPOSE_BUILD) --profile embedded-db up -d
	@$(MAKE) wait-healthy
	@$(MAKE) create-topics
	@echo "$(GREEN)Rebuild complete$(NC)"

rebuild-external-db: ## Rebuild and restart services (external DB)
	@echo "$(GREEN)Rebuilding services (external DB mode)...$(NC)"
	@$(DOCKER_COMPOSE) down
	@$(DOCKER_COMPOSE_BUILD) build --no-cache
	@$(DOCKER_COMPOSE_BUILD) up -d
	@$(MAKE) wait-healthy
	@$(MAKE) create-topics
	@echo "$(GREEN)Rebuild complete$(NC)"

##@ Development (Fast Rebuilds)
# Build and start through the overlay, which renames the images: via the base file `up -d` served
# the stale published image. scale-* stays on the base file: prod has no locally built image.
rebuild-api: ## Rebuild only API service (fast)
	@echo "$(GREEN)Rebuilding API...$(NC)"
	@$(DOCKER_COMPOSE_BUILD) build --no-cache api
	@$(DOCKER_COMPOSE_BUILD) up -d api
	@echo "$(GREEN) API rebuilt and restarted$(NC)"

rebuild-worker: ## Rebuild only Worker service (fast)
	@echo "$(GREEN)Rebuilding Worker...$(NC)"
	@$(DOCKER_COMPOSE_BUILD) build --no-cache worker
	@$(DOCKER_COMPOSE_BUILD) up -d worker
	@echo "$(GREEN) Worker rebuilt and restarted$(NC)"

rebuild-ui: ## Rebuild only UI service (fast)
	@echo "$(GREEN)Rebuilding UI...$(NC)"
	@$(DOCKER_COMPOSE_BUILD) build --no-cache ui
	@$(DOCKER_COMPOSE_BUILD) up -d ui
	@echo "$(GREEN) UI rebuilt and restarted$(NC)"

restart-api: ## Restart API service (no rebuild)
	@echo "$(GREEN)Restarting API...$(NC)"
	@$(DOCKER_COMPOSE) restart api
	@echo "$(GREEN)API restarted$(NC)"

restart-worker: ## Restart Worker service (no rebuild)
	@echo "$(GREEN)Restarting Worker...$(NC)"
	@$(DOCKER_COMPOSE) restart worker
	@echo "$(GREEN)Worker restarted$(NC)"

restart-ui: ## Restart UI service (no rebuild)
	@echo "$(GREEN)Restarting UI...$(NC)"
	@$(DOCKER_COMPOSE) restart ui
	@echo "$(GREEN)UI restarted$(NC)"

dev-api: ## Quick dev: rebuild API with cache + restart
	@echo "$(GREEN)Quick rebuild API (with cache)...$(NC)"
	@$(DOCKER_COMPOSE_BUILD) build api
	@$(DOCKER_COMPOSE_BUILD) up -d api
	@echo "$(GREEN) API ready$(NC)"
	@$(MAKE) logs-api

dev-worker: ## Quick dev: rebuild Worker with cache + restart
	@echo "$(GREEN)Quick rebuild Worker (with cache)...$(NC)"
	@$(DOCKER_COMPOSE_BUILD) build worker
	@$(DOCKER_COMPOSE_BUILD) up -d worker
	@echo "$(GREEN) Worker ready$(NC)"
	@$(MAKE) logs-worker

dev-ui: ## Quick dev: rebuild UI with cache + restart
	@echo "$(GREEN)Quick rebuild UI (with cache)...$(NC)"
	@$(DOCKER_COMPOSE_BUILD) build ui
	@$(DOCKER_COMPOSE_BUILD) up -d ui
	@echo "$(GREEN) UI ready$(NC)"
	@$(MAKE) logs-ui

test-ui: ## Run frontend unit tests (Vitest)
	@echo "$(GREEN)Running frontend tests...$(NC)"
	@cd railhook-ui && npm run test:ci
	@echo "$(GREEN)Frontend tests passed$(NC)"

ratchets: ## Run every @Tag("ratchet") guard test (needs Docker)
	@echo "$(GREEN)Running ratchet guards...$(NC)"
	@mvn test -B -Dgroups=ratchet

types-check: ## Fail if the UI's generated API types are stale vs openapi.yaml (same check CI runs)
	@scripts/check-types-drift.sh

docs-dev: ## Run the docs site (railhook-docs, Starlight) with live reload on http://localhost:4321/docs/
	@cd railhook-docs && { [ -d node_modules ] || npm ci; } && npm run dev

docs-build: ## Build the docs site into railhook-docs/dist (runs the internal links validator)
	@cd railhook-docs && npm ci && npm run build

docs-check: ## Fail if the docs' env reference is stale, their tests fail, or their build breaks (same check CI runs)
	@cd railhook-docs && npm ci && node scripts/env-reference.mjs --check && node --test "scripts/*.test.mjs" && npm run build

seo-check: ## Fail if public/sitemap.xml is stale vs the public route list (same check CI runs)
	@cd railhook-ui && npm run seo:sitemap:check

prerender: ## Render the public pages to static HTML over an existing dist/ (needs a Chromium)
	@cd railhook-ui && npm run build && npm run prerender

##@ Scaling
scale-worker: ## Scale worker instances (usage: make scale-worker N=3)
	@if [ -z "$(N)" ]; then \
		echo "$(RED)ERROR: Please specify N=<number>, e.g. make scale-worker N=3$(NC)"; \
		exit 1; \
	fi
	@echo "$(GREEN)Scaling worker to $(N) instances...$(NC)"
	@$(DOCKER_COMPOSE) up -d --scale worker=$(N) --no-recreate
	@echo "$(GREEN)Worker scaled to $(N) instances$(NC)"

# Compose DNS round-robins nginx's `api:8080` across every replica.
scale-api: ## Scale API instances (usage: make scale-api N=3)
	@if [ -z "$(N)" ]; then \
		echo "$(RED)ERROR: Please specify N=<number>, e.g. make scale-api N=3$(NC)"; \
		exit 1; \
	fi
	@echo "$(GREEN)Scaling api to $(N) instances (nginx load-balances across them)...$(NC)"
	@$(DOCKER_COMPOSE) up -d --scale api=$(N) --no-recreate
	@echo "$(GREEN)API scaled to $(N) instances — 'docker compose ps api' lists the replicas$(NC)"

##@ Release
version-check: ## Fail if pom/Chart/UI/SDK versions disagree (same check CI runs)
	@scripts/check-version-drift.sh

version-set: ## Set the version everywhere (usage: make version-set VERSION=2.3.0)
	@if [ -z "$(VERSION)" ]; then echo "$(RED)Usage: make version-set VERSION=2.3.0$(NC)"; exit 1; fi
	@scripts/set-version.sh $(VERSION)

##@ Kafka
KAFKA_PARTITIONS ?= 12
create-topics: ## Create Kafka topics (idempotent)
	@echo "$(GREEN)Creating Kafka topics with $(KAFKA_PARTITIONS) partitions...$(NC)"
	@docker exec webhook-kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --create --if-not-exists --topic deliveries.dispatch --partitions $(KAFKA_PARTITIONS) --replication-factor 1 2>/dev/null || true
	@docker exec webhook-kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --create --if-not-exists --topic deliveries.retry.1m --partitions $(KAFKA_PARTITIONS) --replication-factor 1 2>/dev/null || true
	@docker exec webhook-kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --create --if-not-exists --topic deliveries.retry.5m --partitions $(KAFKA_PARTITIONS) --replication-factor 1 2>/dev/null || true
	@docker exec webhook-kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --create --if-not-exists --topic deliveries.retry.15m --partitions $(KAFKA_PARTITIONS) --replication-factor 1 2>/dev/null || true
	@docker exec webhook-kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --create --if-not-exists --topic deliveries.retry.1h --partitions $(KAFKA_PARTITIONS) --replication-factor 1 2>/dev/null || true
	@docker exec webhook-kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --create --if-not-exists --topic deliveries.retry.6h --partitions $(KAFKA_PARTITIONS) --replication-factor 1 2>/dev/null || true
	@docker exec webhook-kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --create --if-not-exists --topic deliveries.retry.24h --partitions $(KAFKA_PARTITIONS) --replication-factor 1 2>/dev/null || true
	@docker exec webhook-kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --create --if-not-exists --topic deliveries.dlq --partitions $(KAFKA_PARTITIONS) --replication-factor 1 2>/dev/null || true
	@docker exec webhook-kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --create --if-not-exists --topic incoming.forward.dispatch --partitions $(KAFKA_PARTITIONS) --replication-factor 1 2>/dev/null || true
	@docker exec webhook-kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --create --if-not-exists --topic incoming.forward.retry --partitions $(KAFKA_PARTITIONS) --replication-factor 1 2>/dev/null || true
	@docker exec webhook-kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --create --if-not-exists --topic incoming.forward.dlq --partitions $(KAFKA_PARTITIONS) --replication-factor 1 2>/dev/null || true
	@echo "$(GREEN) Kafka topics created$(NC)"

##@ Monitoring
logs: ## Follow logs for all services
	@$(DOCKER_COMPOSE) logs -f

logs-api: ## Follow logs for API service
	@$(DOCKER_COMPOSE) logs -f api

logs-worker: ## Follow logs for Worker service
	@$(DOCKER_COMPOSE) logs -f worker

logs-ui: ## Follow logs for UI service
	@$(DOCKER_COMPOSE) logs -f ui

verify-link: ## Show last verification link from API logs (only needed with EMAIL_ENABLED=true)
	@$(DOCKER_COMPOSE) logs api 2>&1 | grep "Verify URL:" | tail -1 | sed 's/.*Verify URL: //'

reset-link: ## Show last password reset link from API logs
	@$(DOCKER_COMPOSE) logs api 2>&1 | grep "Reset URL:" | tail -1 | sed 's/.*Reset URL: //'

invite-link: ## Show last member invite link from API logs
	@$(DOCKER_COMPOSE) logs api 2>&1 | grep "Invite URL:" | tail -1 | sed 's/.*Invite URL: //'

WAIT_TIMEOUT ?= 120
wait-healthy: ## Wait until API and Worker are healthy (max WAIT_TIMEOUT seconds)
	@echo "$(GREEN)Waiting for services to become healthy (timeout: $(WAIT_TIMEOUT)s)...$(NC)"
	@elapsed=0; \
	while [ $$elapsed -lt $(WAIT_TIMEOUT) ]; do \
		api_ok=$$($(DOCKER_COMPOSE) exec -T api wget -q --spider http://localhost:8082/actuator/health/liveness 2>/dev/null && echo 1 || echo 0); \
		worker_ok=$$($(DOCKER_COMPOSE) exec -T worker wget -q --spider http://localhost:8081/actuator/health/liveness 2>/dev/null && echo 1 || echo 0); \
		if [ "$$api_ok" = "1" ] && [ "$$worker_ok" = "1" ]; then \
			echo ""; \
			echo "$(GREEN)All services healthy after $${elapsed}s$(NC)"; \
			exit 0; \
		fi; \
		sleep 5; \
		elapsed=$$((elapsed + 5)); \
		printf "\r  Waiting... %ds / $(WAIT_TIMEOUT)s (API=$$api_ok Worker=$$worker_ok)" $$elapsed; \
	done; \
	echo ""; \
	echo "$(RED)ERROR: Services did not become healthy within $(WAIT_TIMEOUT)s$(NC)"; \
	exit 1

health: ## Check health of all services
	@echo "$(GREEN)Checking service health...$(NC)"
	@echo "Postgres: $$(docker exec webhook-postgres pg_isready -U webhook_user 2>/dev/null && echo 'UP' || echo 'DOWN')"
	@echo "Kafka:    $$(docker exec webhook-kafka nc -z localhost 9092 2>/dev/null && echo 'UP' || echo 'DOWN')"
	@echo "Redis:    $$(docker exec webhook-redis redis-cli -a $${REDIS_PASSWORD:-webhook_redis_pass} ping 2>/dev/null | grep -q PONG && echo 'UP' || echo 'DOWN')"
	@echo "API:      $$($(DOCKER_COMPOSE) exec -T api wget -q -O - http://localhost:8082/actuator/health/liveness 2>/dev/null | jq -r .status 2>/dev/null || echo 'DOWN')"
	@echo "Worker:   $$($(DOCKER_COMPOSE) exec -T worker wget -q -O - http://localhost:8081/actuator/health/liveness 2>/dev/null | jq -r .status 2>/dev/null || echo 'DOWN')"
	@echo "Web:      $$(curl -sf -o /dev/null -w '%{http_code}' http://localhost:$${RAILHOOK_PORT:-8080}/ 2>/dev/null || echo 'DOWN') (dashboard + API, http://localhost:$${RAILHOOK_PORT:-8080})"

##@ Database
POSTGRES_USER         ?= webhook_user
POSTGRES_DB           ?= webhook_platform
BACKUP_DIR            ?= ./backups
BACKUP_RETENTION_DAYS ?= 30
DB_MODE               ?= embedded

shell-db: ## Open psql shell in embedded database
	@if [ "$(DB_MODE)" != "embedded" ]; then \
		echo "$(RED)ERROR: This command only works in embedded DB mode$(NC)"; \
		exit 1; \
	fi
	@docker exec -it webhook-postgres psql -U $(POSTGRES_USER) -d $(POSTGRES_DB)

# The same script the db-backup sidecar runs, so every path passes the same flags.
backup-db: ## Backup database to ./backups/ (embedded or external — see DB_MODE)
	@echo "$(GREEN)Creating database backup (DB_MODE=$(DB_MODE))...$(NC)"
	@DB_MODE="$(DB_MODE)" BACKUP_DIR="$(BACKUP_DIR)" BACKUP_RETENTION_DAYS="$(BACKUP_RETENTION_DAYS)" \
		POSTGRES_USER="$(POSTGRES_USER)" POSTGRES_DB="$(POSTGRES_DB)" \
		DB_HOST="$(DB_HOST)" DB_PORT="$(DB_PORT)" DB_NAME="$(DB_NAME)" DB_USER="$(DB_USER)" DB_PASSWORD="$(DB_PASSWORD)" \
		./deploy/scripts/db-backup.sh

restore-db: ## Restore database from backup (usage: make restore-db FILE=backups/webhook_platform_20241217_120000.dump [CONFIRM=YES])
	@if [ -z "$(FILE)" ]; then \
		echo "$(RED)ERROR: Please specify FILE=path/to/backup.dump$(NC)"; \
		exit 1; \
	fi
	@if [ ! -f "$(FILE)" ]; then \
		echo "$(RED)ERROR: File $(FILE) not found$(NC)"; \
		exit 1; \
	fi
	@if [ "$(CONFIRM)" != "YES" ]; then \
		echo "$(YELLOW)  WARNING: This will DROP and recreate data in the target database$(NC)"; \
		echo "$(YELLOW)Press Ctrl+C to cancel, or Enter to continue (pass CONFIRM=YES to skip this prompt, e.g. in CI)...$(NC)"; \
		read confirm; \
	fi
	@echo "$(GREEN)Restoring database from $(FILE) (DB_MODE=$(DB_MODE))...$(NC)"
	@DB_MODE="$(DB_MODE)" FILE="$(FILE)" POSTGRES_USER="$(POSTGRES_USER)" POSTGRES_DB="$(POSTGRES_DB)" \
		DB_HOST="$(DB_HOST)" DB_PORT="$(DB_PORT)" DB_NAME="$(DB_NAME)" DB_USER="$(DB_USER)" DB_PASSWORD="$(DB_PASSWORD)" \
		./deploy/scripts/db-restore.sh
	@echo "$(GREEN)Database restored$(NC)"

##@ Diagnostics
doctor: ## Run pre-flight checks
	@echo "$(GREEN)Running diagnostics...$(NC)"
	@which docker > /dev/null || (echo "$(RED)ERROR: docker not found$(NC)" && exit 1)
	@$(DOCKER_COMPOSE) version > /dev/null || (echo "$(RED)ERROR: docker compose not found$(NC)" && exit 1)
	@[ -f .env ] || (echo "$(YELLOW)WARNING: .env file not found. Copy .env.dist to .env$(NC)" && exit 1)
	@if [ "$(APP_ENV)" = "production" ] || [ "$(APP_ENV)" = "prod" ]; then \
		echo "$(GREEN)Production mode detected — running strict checks...$(NC)"; \
		fail=0; \
		if echo "$(WEBHOOK_ENCRYPTION_KEY)" | grep -qi 'change_me\|dev_'; then \
			echo "$(RED)ERROR: WEBHOOK_ENCRYPTION_KEY contains dev/placeholder value$(NC)"; fail=1; \
		fi; \
		if echo "$(JWT_SECRET)" | grep -qi 'change_me\|dev_'; then \
			echo "$(RED)ERROR: JWT_SECRET contains dev/placeholder value$(NC)"; fail=1; \
		fi; \
		if echo "$(REDIS_PASSWORD)" | grep -qi 'webhook_redis_pass'; then \
			echo "$(RED)ERROR: REDIS_PASSWORD is using the default dev value$(NC)"; fail=1; \
		fi; \
		if echo "$(POSTGRES_PASSWORD)" | grep -qi 'webhook_dev_pass\|webhook_pass'; then \
			echo "$(RED)ERROR: POSTGRES_PASSWORD is using the default dev value$(NC)"; fail=1; \
		fi; \
		if [ "$(WEBHOOK_ALLOW_PRIVATE_IPS)" = "true" ]; then \
			echo "$(YELLOW)WARNING: WEBHOOK_ALLOW_PRIVATE_IPS=true in production (SSRF risk)$(NC)"; \
		fi; \
		if [ "$(SWAGGER_ENABLED)" = "true" ]; then \
			echo "$(YELLOW)WARNING: SWAGGER_ENABLED=true in production$(NC)"; \
		fi; \
		if [ "$(DB_SSL_MODE)" = "disable" ]; then \
			echo "$(YELLOW)WARNING: DB_SSL_MODE=disable in production$(NC)"; \
		fi; \
		if [ $$fail -ne 0 ]; then exit 1; fi; \
	fi
	@if [ "$(DB_MODE)" = "external" ]; then \
		if [ -z "$(DB_HOST)" ] || [ "$(DB_HOST)" = "CHANGE_ME_DB_HOST" ] || [ "$(DB_HOST)" = "postgres" ]; then \
			echo "$(RED)ERROR: DB_HOST must be set to a real host for external DB mode$(NC)"; \
			exit 1; \
		fi; \
		if [ -z "$(DB_PASSWORD)" ] || [ "$(DB_PASSWORD)" = "CHANGE_ME_DB_PASSWORD" ]; then \
			echo "$(RED)ERROR: DB_PASSWORD must be set for external DB mode$(NC)"; \
			exit 1; \
		fi; \
	fi
	@echo "$(GREEN)All checks passed$(NC)"

##@ Monitoring (Prometheus, Grafana, Loki, host exporters)
# A fixed project name, because the shared .env sets COMPOSE_PROJECT_NAME for the platform. The
# network is found by label, so the checkout's directory name does not matter.
MONITORING_NETWORK = $(or $(RAILHOOK_NETWORK),$(shell docker network ls --filter label=com.docker.compose.network=webhook-network --format '{{.Name}}' 2>/dev/null | head -1),railhook_webhook-network)
MONITORING_COMPOSE = RAILHOOK_NETWORK=$(MONITORING_NETWORK) MONITORING_NODENAME=$(or $(MONITORING_NODENAME),$(shell hostname)) $(DOCKER_COMPOSE) -p railhook-monitoring --env-file .env -f monitoring/docker-compose.yml

monitoring-up: ## Start the monitoring stack (set GRAFANA_ADMIN_PASSWORD in .env first)
	@pw="$$GRAFANA_ADMIN_PASSWORD"; \
	if [ -z "$$pw" ] || [ "$${#pw}" -lt 16 ]; then \
		echo "$(RED)Set GRAFANA_ADMIN_PASSWORD in .env (16+ characters): openssl rand -base64 24$(NC)"; \
		exit 1; \
	fi
	@echo "$(GREEN)Starting monitoring stack on $(MONITORING_NETWORK)...$(NC)"
	@$(MONITORING_COMPOSE) up -d
	@echo ""
	@echo "$(GREEN)Monitoring started:$(NC)"
	@echo "  Grafana: http://localhost:$${GRAFANA_PORT:-3001}  (user $${GRAFANA_ADMIN_USER:-admin}, password GRAFANA_ADMIN_PASSWORD)"
	@echo "  Prometheus, Alertmanager and Loki are not published; use Grafana's Explore and Alerting."
	@echo ""

monitoring-down: ## Stop monitoring stack
	@echo "$(YELLOW)Stopping monitoring stack...$(NC)"
	@$(MONITORING_COMPOSE) down
	@echo "$(GREEN)Monitoring stopped$(NC)"

monitoring-logs: ## Follow monitoring stack logs
	@$(MONITORING_COMPOSE) logs -f

monitoring-check-queries: ## Run every dashboard and alert query against the running monitoring stack; fails on queries with no data
	@docker run --rm --network $${MONITORING_CHECK_NETWORK:-railhook-monitoring_monitoring} -v "$(CURDIR)":/repo:ro python:3.12-alpine \
		python /repo/scripts/check-monitoring-queries.py $(MONITORING_CHECK_ARGS)

##@ Danger Zone
nuke: ## DESTROY EVERYTHING including volumes (requires CONFIRM=YES)
	@if [ "$(CONFIRM)" != "YES" ]; then \
		echo "$(RED)"; \
		echo "╔═══════════════════════════════════════════════════════════════╗"; \
		echo "║                           WARNING                             ║"; \
		echo "║                                                               ║"; \
		echo "║  This will PERMANENTLY DELETE:                                ║"; \
		echo "║    • All containers                                           ║"; \
		echo "║    • All volumes (database data will be LOST)                 ║"; \
		echo "║    • All images                                               ║"; \
		echo "║    • All networks                                             ║"; \
		echo "║                                                               ║"; \
		echo "║  THIS CANNOT BE UNDONE!                                       ║"; \
		echo "║                                                               ║"; \
		echo "║  To proceed, run:                                             ║"; \
		echo "║    make nuke CONFIRM=YES                                      ║"; \
		echo "╚═══════════════════════════════════════════════════════════════╝"; \
		echo "$(NC)"; \
		exit 1; \
	fi
	@echo "$(RED)Destroying everything...$(NC)"
	@echo "$(RED)Stopping monitoring stack...$(NC)"
	@$(MONITORING_COMPOSE) down -v --remove-orphans 2>/dev/null || true
	@echo "$(RED)Stopping main platform...$(NC)"
	@$(DOCKER_COMPOSE) --profile embedded-db down -v --remove-orphans --rmi local 2>/dev/null || true
	@docker volume rm webhook_pgdata kafka_data redis_data 2>/dev/null || true
	@docker network rm railhook_webhook-network 2>/dev/null || true
	@echo "$(GREEN)Nuclear option complete — platform + monitoring destroyed$(NC)"
