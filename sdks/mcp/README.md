# @railhook/mcp

A stdio bridge to Railhook's [MCP](https://modelcontextprotocol.io) server, for clients that only
start local processes, such as Claude Desktop.

Clients that support remote servers (Claude Code, Cursor) should connect to
`https://railhook.io/mcp`, or `<your origin>/mcp` when self-hosted, and skip this package. The
bridge passes `tools/list` and `tools/call` through and has no tools of its own.

## Use

Requires Node.js 18 or later.

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

| Variable | Required | Meaning |
|---|---|---|
| `RAILHOOK_API_KEY` | yes | A project API key. A `READ_ONLY` key allows the read tools and refuses every write. |
| `RAILHOOK_BASE_URL` | no | Default `https://railhook.io`. Set it to your origin when self-hosted. |

The key is sent as `Authorization: Bearer <key>` and is never logged.

Tools: `send_event`, `list_endpoints`, `create_endpoint`, `list_subscriptions`,
`create_subscription`, `list_deliveries`, `get_delivery`, `replay_delivery`. Each acts on the
project the key belongs to.

Full guide: https://railhook.io/docs/tools/mcp/

## Develop

```bash
npm ci
npm test
npm run build
```

## License

MIT
