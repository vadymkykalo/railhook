# Build stage
# Node:18-alpine went EOL April 2025. 22-alpine is the current
# Maintenance LTS line (actively rebuilt/patched as of 2026-08).
#
# --platform=$BUILDPLATFORM pins this stage to the builder's own architecture.
# What it produces — `dist` — is static HTML, CSS and JS, identical whatever CPU
# built it, and it is copied into the per-arch runtime stage below, so the image
# stays multi-arch. Without the pin, `docker buildx --platform linux/arm64` runs
# this stage under QEMU emulation, and the prerender step launches Chromium
# there: on the v2.6.0 tag that timed out after 16 minutes
# ("Timed out after waiting 30000ms") and the UI image for that release was
# never published, which broke `install.sh` for every new user.
FROM --platform=$BUILDPLATFORM node:22-alpine AS build

WORKDIR /app

# Copy package files (build context is project root)
COPY railhook-ui/package*.json ./

# Install dependencies
RUN npm ci

# Copy UI source files
COPY railhook-ui/ .

# Vite inlines import.meta.env.VITE_* at build time, so these have to arrive
# here as build args — passing them as runtime env to the nginx stage does
# nothing. Empty is the normal case: nginx.conf proxies /api/, /ws/tunnel,
# /hook/ and /ingress/ to the api service, so the bundle talks to its own
# origin and needs no absolute API URL.
ARG VITE_API_URL=""
ARG VITE_CSP_EXTRA_CONNECT=""
ARG VITE_SITE_URL=""
ARG VITE_CONTACT_DOMAIN=""
# The registration challenge. Build-time like every other VITE_ value, and the
# site key's presence is also what src/lib/csp.ts keys the widened script-src
# and frame-src off — so an image built without it cannot show a challenge even
# if the API is configured to demand one.
ARG VITE_CAPTCHA_SITE_KEY=""
ARG VITE_CAPTCHA_SCRIPT_URL=""
ENV VITE_API_URL=$VITE_API_URL
ENV VITE_CSP_EXTRA_CONNECT=$VITE_CSP_EXTRA_CONNECT
ENV VITE_SITE_URL=$VITE_SITE_URL
ENV VITE_CONTACT_DOMAIN=$VITE_CONTACT_DOMAIN
ENV VITE_CAPTCHA_SITE_KEY=$VITE_CAPTCHA_SITE_KEY
ENV VITE_CAPTCHA_SCRIPT_URL=$VITE_CAPTCHA_SCRIPT_URL

# Chromium for the prerender step. Alpine's package rather than a Puppeteer download:
# puppeteer-core ships no browser, and the musl builds Puppeteer fetches do not run here.
# It lives in the build stage only — the runtime image below is nginx and carries none of it.
RUN apk add --no-cache chromium nss freetype harfbuzz ca-certificates ttf-freefont
ENV PUPPETEER_EXECUTABLE_PATH=/usr/bin/chromium

# Build, then render the public pages to static HTML. Without the second step nginx serves
# `<div id="root"></div>` for every URL, and the titles, canonicals and sitemap all point at
# pages whose body is empty until React mounts.
RUN npm run build && npm run prerender

# Runtime stage
# Bumped from the 1.25 stable branch (past EOL) to the current 1.30
# stable branch.
FROM nginx:1.30-alpine

# Copy built assets from build stage
COPY --from=build /app/dist /usr/share/nginx/html

# Copy nginx config
COPY railhook-ui/nginx.conf /etc/nginx/conf.d/default.conf

EXPOSE 5173

CMD ["nginx", "-g", "daemon off;"]
