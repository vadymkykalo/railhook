# Hookflow CLI

Production-grade CLI for the Hookflow webhook platform. Provides local webhook tunneling, event replay, and diagnostics.

## Quick Start

### Build

```bash
# From the project root
mvn clean package -pl webhook-platform-cli -am -DskipTests

# The fat JAR is at:
# webhook-platform-cli/target/webhook-platform-cli-1.0.0-SNAPSHOT.jar
```

### Install (optional alias)

```bash
alias hookflow='java -jar /path/to/webhook-platform-cli-1.0.0-SNAPSHOT.jar'
```

### Login

**Device code flow** (recommended — no password in terminal):

```bash
hookflow login
# Opens a URL + shows a code. Approve in the browser.
```

**Direct login** (non-interactive, e.g. CI):

```bash
hookflow login --email user@example.com --password
# Password will be prompted securely
```

**Custom backend URL:**

```bash
hookflow login --server https://api.example.com
```

### Local Webhook Tunnel

```bash
hookflow listen 3000
```

This will:
1. Authenticate with the backend using stored credentials
2. Create a tunnel session and receive a public URL
3. Connect a WebSocket to the backend
4. Forward incoming HTTP requests to `http://localhost:3000`
5. Return local responses back through the tunnel

Output:
```
╔══════════════════════════════════════════════════════╗
║  Hookflow tunnel is active                          ║
╚══════════════════════════════════════════════════════╝

  Public URL:  http://localhost:8080/tunnel/tun-abc123xyz
  Forwarding:  → http://localhost:3000
  Tunnel ID:   a1b2c3d4-...

  Press Ctrl+C to stop

  Requests:
  ─────────────────────────────────────────────────────
  POST /webhook → 200 (42ms)
  POST /webhook → 200 (15ms)
```

Options:
```bash
hookflow listen 3000 --project <projectId>
```

### Status

```bash
hookflow status
```

Shows: backend URL, auth state, health, active tunnels.

### Event Replay

```bash
# Estimate replay (dry run)
hookflow replay <projectId> --dry-run

# Replay events from the last 24h
hookflow replay <projectId>

# Replay with filters
hookflow replay <projectId> --event-type order.created --from 2024-01-01T00:00:00Z --to 2024-01-02T00:00:00Z
```

### Tunnel Management

```bash
# List active tunnels
hookflow tunnels list

# Close a tunnel
hookflow tunnels close <sessionId>
```

### Event Tail

```bash
# Show recent events
hookflow events <projectId>

# Follow mode (poll for new events)
hookflow events <projectId> --follow

# Filter by type
hookflow events <projectId> --type order.created --count 50
```

### Configuration

```bash
# Show config
hookflow config show

# Set backend URL
hookflow config set backend-url https://api.example.com

# Set default project
hookflow config set project-id <uuid>

# Clear all config (logout)
hookflow config clear
```

## Configuration File

Stored at `~/.config/hookflow/config.json` (respects `XDG_CONFIG_HOME` and `HOOKFLOW_CONFIG` env vars).

File permissions are set to `600` (owner-only) to protect tokens.

```json
{
  "backendUrl": "http://localhost:8080",
  "accessToken": "eyJ...",
  "refreshToken": "eyJ...",
  "userId": "...",
  "organizationId": "...",
  "activeProjectId": "..."
}
```

## Environment Variables

| Variable | Description |
|---|---|
| `HOOKFLOW_CONFIG` | Override config file path |
| `XDG_CONFIG_HOME` | XDG config directory (default: `~/.config`) |
| `HOOKFLOW_DEBUG` | Enable debug stack traces on errors |
| `HOOKFLOW_LOG_LEVEL` | Log level: `DEBUG`, `INFO`, `WARN`, `ERROR` |

## Security

- Tokens are stored in `~/.config/hookflow/config.json` with `600` permissions
- Access tokens are automatically refreshed on 401 responses
- Tunnel authentication uses a per-session token (never reused)
- WebSocket connections are authenticated via tunnel token
- Tunnel sessions expire after heartbeat timeout (default: 120s)
- Device code flow avoids exposing passwords in CLI history
- Message sizes are validated (max 1MB over WebSocket, 512KB for tunnel ingress)

## Architecture

See [docs/CLI_ARCHITECTURE.md](../docs/CLI_ARCHITECTURE.md) for detailed architecture documentation.
