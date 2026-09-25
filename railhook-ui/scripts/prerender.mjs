#!/usr/bin/env node
/** A real browser, not renderToString: the app reads matchMedia, localStorage and dynamic imports. */
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

/** One set of URLs for both locales, so the crawled copy is the canonical locale. */
const PRERENDER_LOCALE = process.env.PRERENDER_LOCALE || 'en';

/** Generous: runs inside docker build on a shared machine, and a timeout fails the image. */
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

/** The prerender passes this as the site URL, so canonicals carry the placeholder, not localhost. */
const SITE_URL_PLACEHOLDER = 'https://site-url.railhook.invalid';

const PRERENDER_CONFIG_JS = `window.__RAILHOOK__ = ${JSON.stringify({
  contactDomain: '',
  siteUrl: SITE_URL_PLACEHOLDER,
  captchaSiteKey: '',
  captchaScriptUrl: '',
  webAnalyticsToken: '',
  // The pages are the public site's; a self-hosted install re-renders from its own /config.js.
  publicTester: true,
  publicBlog: true,
})};\n`;

function serveDist() {
  return createServer(async (req, res) => {
    const url = new URL(req.url, `http://localhost:${PORT}`);
    if (url.pathname === '/config.js') {
      res.writeHead(200, { 'Content-Type': 'text/javascript' });
      res.end(PRERENDER_CONFIG_JS);
      return;
    }
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
  const routes = publicRoutes().map((r) => r.path);
  const server = serveDist();
  await new Promise((ok) => server.listen(PORT, ok));

  const browser = await puppeteer.launch({
    executablePath: chromiumPath(),
    // Pin the locale: the build machine's navigator once crawled Ukrainian titles on English URLs.
    args: ['--no-sandbox', '--disable-dev-shm-usage', `--lang=${PRERENDER_LOCALE}`],
  });

  let written = 0;
  try {
    const page = await browser.newPage();
    /* Reveal starts at opacity 0 unless reduced motion is on; crawlers discount hidden content. */
    await page.emulateMediaFeatures([{ name: 'prefers-reduced-motion', value: 'reduce' }]);
    await page.setExtraHTTPHeaders({ 'Accept-Language': PRERENDER_LOCALE });
    await page.evaluateOnNewDocument((locale) => {
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
      // The locale bundle is a dynamic import; without it the capture is raw keys.
      await page.waitForFunction(() => document.querySelector('#root')?.childElementCount > 0, {
        timeout: RENDER_TIMEOUT_MS,
      });

      if (failures.length) {
        throw new Error(`${route} threw while rendering: ${failures.join(' | ')}`);
      }

      // The prerender's CSP has no CAPTCHA origin; baked in, browsers would intersect it with the real one.
      await page.evaluate(() => {
        document.querySelectorAll('meta[http-equiv="Content-Security-Policy"]').forEach((m) => m.remove());
      });

      const html = await page.content();
      // A shell-only render would be served to crawlers as a real, empty page.
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
