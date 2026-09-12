# Security Policy

## Supported Versions

| Version | Supported          |
| ------- | ------------------ |
| 2.12.x  | :white_check_mark: |
| < 2.12  | :x:                |

Fixes land on the latest minor line. Older releases are not backported — upgrade
first, and see [`UPGRADING.md`](./UPGRADING.md) if the jump crosses a major.

## Reporting a Vulnerability

If you discover a security vulnerability, please report it responsibly:

1. **Do NOT** open a public GitHub issue
2. Preferred: use [GitHub's private vulnerability reporting](https://github.com/vadymkykalo/railhook/security/advisories/new)
   for this repository (Security tab → Report a vulnerability).
3. Alternatively, email **vadymkykalo@gmail.com** with:
   - Description of the vulnerability
   - Steps to reproduce
   - Potential impact
   - Suggested fix (if any)

We will respond within 48 hours and work on a fix promptly.

## Security Best Practices

When deploying Webhook Platform:

### Secrets Management
- Use environment variables or secrets management (Vault, AWS Secrets Manager)
- Never commit secrets to the repository
- Rotate API keys and JWT secrets regularly

### Network Security
- Deploy behind a reverse proxy (nginx, Traefik)
- Use TLS/HTTPS for all external traffic
- Restrict database access to internal networks only

### Authentication
- Use strong JWT secrets (256+ bits)
- Configure appropriate token expiration times
- Enable rate limiting in production

### Database
- Use strong PostgreSQL passwords
- Enable SSL for database connections
- Regular backups with encryption

## Security Features

- **HMAC Signatures**: All webhook deliveries are signed with HMAC-SHA256
- **Rate Limiting**: Redis-based distributed rate limiting
- **API Key Authentication**: Secure API key generation and validation
- **JWT Tokens**: Short-lived access tokens with refresh token rotation
