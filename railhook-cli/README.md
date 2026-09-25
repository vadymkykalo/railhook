# Railhook CLI

Forwards webhooks to `localhost` while you develop, tails events and replays them.

```bash
curl -fsSL https://railhook.io/install-cli.sh | bash
# or build from the repo root:
mvn clean package -pl railhook-cli -am -DskipTests   # fat jar in railhook-cli/target/
```

## Commands

```bash
railhook login                                   # device code flow, approve in the browser
railhook login --server https://hooks.example.com
railhook login --email you@example.com --password # non-interactive, prompts for the password

railhook listen 3000 [--project <projectId>]     # public URL forwarding to http://localhost:3000
railhook status                                  # backend, auth, health, active tunnels
railhook tunnels list | close <sessionId>

railhook events <projectId> [--follow] [--type order.created] [--count 50]
railhook replay <projectId> --dry-run            # estimate first
railhook replay <projectId> --event-type order.created --from 2026-01-01T00:00:00Z --to 2026-01-02T00:00:00Z

railhook config show | clear
railhook config set backend-url https://hooks.example.com
railhook config set project-id <uuid>
```

## Configuration

Tokens and settings live in `~/.config/railhook/config.json` (mode `600`). Access tokens refresh
on 401.

| Variable | Description |
|---|---|
| `RAILHOOK_CONFIG` | Config file path |
| `XDG_CONFIG_HOME` | Config directory (default `~/.config`) |
| `RAILHOOK_DEBUG` | Print stack traces on errors |
| `RAILHOOK_LOG_LEVEL` | `DEBUG`, `INFO`, `WARN` or `ERROR` |

Full docs: https://railhook.io/docs/tools/cli/
