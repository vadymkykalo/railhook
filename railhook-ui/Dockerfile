# Builder's arch: dist is static, and Chromium under QEMU timed out the arm64 build.
FROM --platform=$BUILDPLATFORM node:22-alpine AS build

WORKDIR /app

COPY railhook-ui/package*.json ./

RUN npm ci

# Alpine's Chromium for the prerender: the builds Puppeteer downloads don't run on musl.
RUN apk add --no-cache chromium nss freetype harfbuzz ca-certificates ttf-freefont
ENV PUPPETEER_EXECUTABLE_PATH=/usr/bin/chromium

COPY railhook-ui/ .

# Vite inlines VITE_* at build time; per-deployment settings are runtime (20-runtime-config.sh).
ARG VITE_API_URL=""
ARG VITE_CSP_EXTRA_CONNECT=""
ENV VITE_API_URL=$VITE_API_URL
ENV VITE_CSP_EXTRA_CONNECT=$VITE_CSP_EXTRA_CONNECT

RUN npm run build && npm run prerender \
    && node -p "require('./package.json').version" > dist/version.txt

# Reads openapi.yaml and .env.dist from the repo root, hence the root build context.
FROM --platform=$BUILDPLATFORM node:22-alpine AS docs

WORKDIR /app
COPY openapi.yaml .env.dist ./
COPY railhook-docs/package*.json railhook-docs/
RUN cd railhook-docs && npm ci
COPY railhook-docs/ railhook-docs/
RUN cd railhook-docs && npm run build

FROM nginx:1.30-alpine

COPY --from=build /app/dist /usr/share/nginx/html

COPY --from=docs /app/railhook-docs/dist /usr/share/nginx/html/docs

COPY install.sh /usr/share/nginx/html/install.sh
COPY railhook-cli/install.sh /usr/share/nginx/html/install-cli.sh

COPY railhook-ui/nginx.conf /etc/nginx/conf.d/default.conf
COPY railhook-ui/nginx-security-headers.conf /etc/nginx/snippets/security-headers.conf
COPY railhook-ui/nginx-security-headers-common.conf /etc/nginx/snippets/security-headers-common.conf

COPY railhook-ui/docker-entrypoint.d/15-resolver.sh /docker-entrypoint.d/15-resolver.sh
COPY railhook-ui/docker-entrypoint.d/20-runtime-config.sh /docker-entrypoint.d/20-runtime-config.sh
RUN chmod +x /docker-entrypoint.d/15-resolver.sh /docker-entrypoint.d/20-runtime-config.sh

EXPOSE 5173

CMD ["nginx", "-g", "daemon off;"]
