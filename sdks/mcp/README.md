# @railhook/mcp

Railhook's [MCP](https://modelcontextprotocol.io) server for clients that start local processes,
such as Claude Desktop.

Railhook serves its MCP server remotely, at `https://railhook.io/mcp` (or `<your origin>/mcp` when
self-hosted). A client that supports remote servers — Claude Code, Cursor — should connect to that
URL directly. This package is a thin stdio bridge to the same server for the clients that cannot:
it passes `tools/list` and `tools/call` through and holds no tools of its own, so it never needs
updating when the tools change.

Full guide: https://railhook.io/docs/tools/mcp/

## Use

```json
{
  "mcpServers": {
    "railhook": {
      "command": "npx",
      "args": ["-y", "@railhook/mcp"],
      "env": {
        "RAILHOOK_API_KEY": "your-project-api-key"
      }
    }
  }
}
```

Requires Node.js 18 or later.

| Variable | Required | Meaning |
|---|---|---|
| `RAILHOOK_API_KEY` | yes | A project API key. Start with a `READ_ONLY` key: the read tools work and every write is refused. |
| `RAILHOOK_BASE_URL` | no | Default `https://railhook.io`. Your own origin when self-hosted; the bridge connects to `<RAILHOOK_BASE_URL>/mcp`. |

The key is sent to the remote server as `Authorization: Bearer <key>` and is never logged. A key the
server rejects, or a server that is not there, comes back as a tool error that says which of the two
to fix.

## Tools

`send_event`, `list_endpoints`, `create_endpoint`, `list_subscriptions`, `create_subscription`,
`list_deliveries`, `get_delivery`, `replay_delivery` — served by the Railhook API and described in
the guide above. Each acts on the project the API key belongs to.

## Develop

```bash
npm ci
npm test
npm run build
```

## License

MIT
