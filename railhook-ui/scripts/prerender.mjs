#!/usr/bin/env node
/**
 * Renders the public pages to static HTML after the Vite build.
 *
 * The app is a single-page bundle, so what nginx serves for every URL is
 * `<div id="root"></div>` and nothing else. A crawler that does not run
 * JavaScript — and several that do, budget-permitting — sees an empty document
 * on the landing page, the pricing page and thirteen documentation guides.
 * Every other SEO fix in this repo (per-route titles, canonicals, the sitemap,
 * JSON-LD) points at pages whose body is empty until React mounts.
 *
 * This walks the built `dist` with a real browser and writes what it finds back
 * over `dist/<route>/index.html`. A real browser rather than `renderToString`
 * because the app is not written to be server-safe — `AmbientDelivery` reads
 * `matchMedia`, the auth store reads `localStorage`, the locale bundles are
 * dynamic imports — and hardening all of that would put a constraint on every
 * future import that nothing enforces. Puppeteer runs the app exactly as a
 * visitor does, which also means a page that throws is a page that fails the
 * build here rather than one that quietly renders empty.
 *
 * Not part of `npm run build`: the frontend CI job builds to typecheck and lint,
 * and making that job download a browser would cost minutes on every push. The
 * Dockerfile runs it as its own step, so the image that ships is prerendered and
 * the fast path stays fast.
 *
 *   npm run build && npm run prerender
 */
import { createServer } from 'node:http';
import { readFile, writeFile, mkdir } from 'node:fs/promises';
import { existsSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, resolve, join, extname } from 'node:path';
import puppeteer from 'puppeteer-core';
import { publicRoutes } from './public-routes.mjs';

const here = dirname(fileURLToPath(import.meta.url));
const DIST = resolve(here, '../dist');
const PORT = Number(process.env.PRERENDER_PORT || 4178);

/**
 * The locale the static HTML is written in.
 *
 * There is one set of URLs for two locales — the language is chosen client-side — so the
 * crawled copy has to be the one the canonical URLs and the sitemap describe. When there are
 * real `/uk/...` paths this becomes a loop over both.
 */
const PRERENDER_LOCALE = process.env.PRERENDER_LOCALE || 'en';

/**
 * How long one route gets to load and to mount React.
 *
 * Generous on purpose. This runs inside `docker build`, where the machine is
 * shared with whatever else the build is doing and Chromium is competing for
 * it. The Dockerfile pins the build stage to $BUILDPLATFORM so this no longer
 * runs under QEMU — the emulated arm64 leg is what blew the old 30s budget and
 * cost the v2.6.0 release its UI image — but a slow CI runner alone can come
 * close, and a timeout here fails the whole image build. Overridable so a
 * constrained builder can raise them without a code change.
 */
const NAV_TIMEOUT_MS = Number(process.env.PRERENDER_NAV_TIMEOUT_MS || 60_000);
const RENDER_TIMEOUT_MS = Number(process.env.PRERENDER_RENDER_TIMEOUT_MS || 30_000);

const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript',
  '.css': 'text/css',
  '.json': 'application/json',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.woff2': 'font/woff2',
  '.xml': 'application/xml',
  '.txt': 'text/plain',
};

/** The same SPA fallback nginx serves, so the browser sees production routing. */
function serveDist() {
  return createServer(async (req, res) => {
    const url = new URL(req.url, `http://localhost:${PORT}`);
    let filePath = join(DIST, decodeURIComponent(url.pathname));
    if (!existsSync(filePath) || !extname(filePath)) {
      filePath = join(DIST, 'index.html');
    }
    try {
      const body = await readFile(filePath);
      res.writeHead(200, { 'Content-Type': MIME[extname(filePath)] || 'application/octet-stream' });
      res.end(body);
    } catch {
      res.writeHead(404).end('not found');
    }
  });
}

function chromiumPath() {
  const candidates = [
    process.env.PUPPETEER_EXECUTABLE_PATH,
    '/usr/bin/chromium',
    '/usr/bin/chromium-browser',
    '/usr/bin/google-chrome',
  ].filter(Boolean);
  const found = candidates.find((p) => existsSync(p));
  if (!found) {
    throw new Error(
      'No Chromium found. Set PUPPETEER_EXECUTABLE_PATH, or install one '
        + '(alpine: apk add chromium; debian: apt-get install chromium).',
    );
  }
  return found;
}

async function main() {
  // The same list the sitemap publishes, from the same module: a URL promised to crawlers
  // and not rendered here would resolve to the empty shell it was meant to replace.
  const routes = publicRoutes().map((r) => r.path);
  const server = serveDist();
  await new Promise((ok) => server.listen(PORT, ok));

  const browser = await puppeteer.launch({
    executablePath: chromiumPath(),
    // The language detector's order is ['localStorage', 'navigator'], and the build machine's
    // navigator decides the rest. Without pinning it, whichever locale the machine happens to
    // prefer is the one that gets crawled — the first run of this produced Ukrainian titles
    // on every English canonical URL.
    args: ['--no-sandbox', '--disable-dev-shm-usage', `--lang=${PRERENDER_LOCALE}`],
  });

  let written = 0;
  try {
    const page = await browser.newPage();
    /* Every Reveal on the landing page initialises from prefers-reduced-motion, so with it
       emulated the whole page renders opaque immediately. Without it the captured HTML would
       carry `opacity: 0` on most of its content — present in the DOM, but the kind of hidden
       a crawler is entitled to discount. */
    await page.emulateMediaFeatures([{ name: 'prefers-reduced-motion', value: 'reduce' }]);
    await page.setExtraHTTPHeaders({ 'Accept-Language': PRERENDER_LOCALE });
    await page.evaluateOnNewDocument((locale) => {
      // 'localStorage' comes before 'navigator' in the detector's order, so this is the
      // authoritative one; the launch flag and header cover the fallback.
      try {
        window.localStorage.setItem('i18n_lng', locale);
      } catch {
        /* a private-mode-like context; the header still applies */
      }
    }, PRERENDER_LOCALE);

    const failures = [];
    page.on('pageerror', (err) => failures.push(err.message));

    for (const route of routes) {
      failures.length = 0;
      await page.goto(`http://localhost:${PORT}${route}`, { waitUntil: 'networkidle0', timeout: NAV_TIMEOUT_MS });
      // The locale bundle is a dynamic import; without it the capture is a tree of raw keys.
      await page.waitForFunction(() => document.querySelector('#root')?.childElementCount > 0, {
        timeout: RENDER_TIMEOUT_MS,
      });

      if (failures.length) {
        throw new Error(`${route} threw while rendering: ${failures.join(' | ')}`);
      }

      const html = await page.content();
      // A route that rendered a shell and nothing else is worse than no prerender: it would
      // be served to a crawler as a real, empty page.
      if (html.length < 2000) {
        throw new Error(`${route} rendered only ${html.length} bytes — refusing to write it`);
      }

      const out = route === '/' ? join(DIST, 'index.html') : join(DIST, route, 'index.html');
      await mkdir(dirname(out), { recursive: true });
      await writeFile(out, html);
      written += 1;
      console.log(`  ${route.padEnd(32)} ${(html.length / 1024).toFixed(0)} KB`);
    }
  } finally {
    await browser.close();
    server.close();
  }

  console.log(`Prerendered ${written} routes.`);
}

main().catch((err) => {
  console.error(err.message);
  process.exit(1);
});
