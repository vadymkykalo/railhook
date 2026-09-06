# Webhook Platform UI

React-based frontend for the Webhook Platform — a production-ready webhook infrastructure with reliable delivery at any scale.

## Tech Stack

- **React 18** + **TypeScript**
- **Vite** — build tooling
- **React Router 6** — client-side routing
- **TanStack React Query** — server state management
- **Axios** — HTTP client with interceptors
- **Tailwind CSS** — utility-first styling
- **Radix UI** — accessible primitives (Dialog, Switch, Label, Toast)
- **Recharts** — analytics charts
- **react-i18next** — internationalization (English, Ukrainian)
- **Lucide React** — icon library
- **Sonner** — toast notifications

## Development

### Local Development (without Docker)

```bash
cd railhook-ui
npm install
npm run dev
```

Access at: http://localhost:5173

### With Docker Compose

From the repo root:

```bash
docker compose up ui
```

Or start all services:

```bash
docker compose up
```

### Available Scripts

- `npm run dev` — start dev server
- `npm run build` — typecheck + production build
- `npm run preview` — preview production build
- `npm run typecheck` — TypeScript type checking
- `npm run lint` — ESLint
- `npm run lint:fix` — ESLint with auto-fix

## Features

- **Authentication** — registration, login, email verification, forgot/reset password
- **JWT with refresh tokens** — persistent sessions via `localStorage`, automatic token refresh on 401
- **RBAC** — role-based access control (OWNER / DEVELOPER / VIEWER) enforced at route level and UI level
- **Projects** — CRUD with per-project navigation context
- **Endpoints** — create, toggle, delete webhook endpoints with secret rotation
- **Events** — list, send test events, view details
- **Deliveries** — track delivery attempts with filtering, replay failed deliveries
- **Subscriptions** — manage event-type-to-endpoint bindings
- **API Keys** — create, revoke, paginated list
- **Analytics** — delivery trends, latency percentiles, event type breakdown, endpoint performance
- **Dead Letter Queue** — view, retry, purge failed deliveries
- **Test Endpoints** — ephemeral endpoints for testing with captured request inspection
- **Members** — invite, change roles, remove (OWNER only)
- **Settings** — organization settings (OWNER only)
- **Audit Log** — paginated organization activity log
- **Dashboard** — overview with stats, recent events, endpoint health, onboarding checklist
- **Command Palette** — `Ctrl+K` quick navigation across pages and projects
- **Dark/Light theme** — toggle with system preference detection
- **i18n** — English and Ukrainian with locale-aware date/number formatting

## Security

- JWT access + refresh tokens stored in `localStorage`
- Automatic token refresh via Axios interceptor on 401 responses
- Tokens cleared on logout and on refresh failure
- Route-level RBAC: sensitive routes (members, settings) require OWNER role
- UI-level RBAC: action buttons (create, delete, manage) hidden for insufficient roles
- Authorization header sent with all authenticated API requests

## Project Structure

```
src/
├── api/          # API clients, React Query hooks, HTTP interceptor
├── auth/         # AuthProvider, ProtectedRoute, usePermissions, login/register pages
├── components/   # Shared UI components (EmptyState, PageSkeleton, CommandPalette, etc.)
│   └── ui/       # Radix-based primitives (Button, Card, Dialog, Table, etc.)
├── i18n/         # i18next config + locale files (en.json, uk.json)
├── layout/       # AppLayout (sidebar, breadcrumb), PublicLayout (footer)
├── lib/          # Utilities (cn, date formatting, theme)
├── pages/        # Page components (one per route)
├── types/        # TypeScript API types
├── router.tsx    # Route definitions with lazy loading + RBAC guards
└── App.tsx       # Root provider (Auth, React Query, Router, i18n)
```

## API Integration

Backend URL configured via:
- **Local dev**: `http://localhost:8080` (default)
- **Docker**: `http://api:8080` (via `VITE_API_URL` env var)

All API calls use Bearer token authentication except login/register.
