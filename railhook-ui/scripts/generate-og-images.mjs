#!/usr/bin/env node
/** Committed, not built: CI shouldn't drive a browser, and og:image must exist in local builds too. */
import { readdirSync, readFileSync, existsSync, mkdirSync, writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join, resolve } from 'node:path';
import puppeteer from 'puppeteer-core';
import { parseFrontMatter } from '../src/lib/frontMatter.ts';
import { RAILHOOK_MARK } from '../src/components/icons/railhookMark.ts';

const here = dirname(fileURLToPath(import.meta.url));
const CONTENT = resolve(here, '../src/content/blog');
const OUT = resolve(here, '../public/blog');

const WIDTH = 1200;
const HEIGHT = 630;

function posts() {
  if (!existsSync(CONTENT)) return [];
  return readdirSync(CONTENT, { withFileTypes: true })
    .filter((entry) => entry.isDirectory() && existsSync(join(CONTENT, entry.name, 'en.md')))
    .map((entry) => ({
      slug: entry.name,
      ...parseFrontMatter(readFileSync(join(CONTENT, entry.name, 'en.md'), 'utf8')).values,
    }));
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

const escape = (value) =>
  String(value).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');

function card({ title, lead, author, date, tags }) {
  return `<!doctype html>
<html><head><meta charset="utf-8">
<link href="https://fonts.googleapis.com/css2?family=Manrope:wght@600;700;800&family=Onest:wght@400;500&family=JetBrains+Mono:wght@500&display=swap" rel="stylesheet">
<style>
  * { margin: 0; box-sizing: border-box; }
  body {
    width: ${WIDTH}px; height: ${HEIGHT}px;
    background: #0E1020; color: #E6E8F2;
    font-family: Onest, system-ui, sans-serif;
    display: flex; flex-direction: column; justify-content: space-between;
    padding: 64px 72px;
    position: relative; overflow: hidden;
  }
  .rail { position: absolute; left: 0; right: 0; top: 0; height: 6px; background: #1D4BFF; }
  .grid {
    position: absolute; inset: 0;
    background-image: radial-gradient(#23263A 1px, transparent 1px);
    background-size: 28px 28px; opacity: .55;
  }
  .content { position: relative; }
  .brand { display: flex; align-items: center; gap: 14px; font-family: Manrope, sans-serif; font-weight: 800; font-size: 26px; letter-spacing: -.01em; }
  .mark { width: 40px; height: 40px; border-radius: 11px; background: #1D4BFF; display: grid; place-items: center; }
  .mark svg { width: 24px; height: 24px; }
  h1 {
    font-family: Manrope, sans-serif; font-weight: 800;
    font-size: ${title.length > 68 ? 54 : 62}px; line-height: 1.06; letter-spacing: -.035em;
    margin-top: 44px; max-width: 980px; text-wrap: balance;
  }
  p.lead { margin-top: 22px; font-size: 25px; line-height: 1.45; color: #8A90A6; max-width: 900px; }
  .foot { position: relative; display: flex; align-items: center; justify-content: space-between; font-family: 'JetBrains Mono', ui-monospace, monospace; font-size: 18px; color: #8A90A6; }
  .foot .who { color: #E6E8F2; }
  .tags { display: flex; gap: 10px; }
  .tag { border: 1px solid #23263A; border-radius: 999px; padding: 5px 14px; color: #6F8DFF; font-size: 16px; }
</style></head>
<body>
  <div class="rail"></div><div class="grid"></div>
  <div class="content">
    <div class="brand">
      <span class="mark"><svg viewBox="0 0 20 20" fill="none" stroke="#fff" stroke-width="2.25" stroke-linecap="round" stroke-linejoin="round"><path d="${RAILHOOK_MARK.hook}"/><path d="${RAILHOOK_MARK.flow}"/><circle cx="${RAILHOOK_MARK.origin.cx}" cy="${RAILHOOK_MARK.origin.cy}" r="${RAILHOOK_MARK.origin.r}" fill="#fff" stroke="none"/></svg></span>
      Railhook
    </div>
    <h1>${escape(title)}</h1>
    <p class="lead">${escape(lead)}</p>
  </div>
  <div class="foot">
    <span><span class="who">${escape(author)}</span> · ${escape(date)}</span>
    <span class="tags">${tags.map((tag) => `<span class="tag">${escape(tag)}</span>`).join('')}</span>
  </div>
</body></html>`;
}

function shorten(text, limit = 132) {
  if (text.length <= limit) return text;
  const cut = text.slice(0, limit);
  return `${cut.slice(0, cut.lastIndexOf(' '))}…`;
}

async function main() {
  const entries = posts();
  if (entries.length === 0) {
    console.log('No blog posts; nothing to draw.');
    return;
  }

  if (process.argv.includes('--check')) {
    const missing = entries.filter((post) => !existsSync(join(OUT, `${post.slug}.png`)));
    if (missing.length) {
      console.error(`No social card for: ${missing.map((p) => p.slug).join(', ')}. Run: npm run blog:og`);
      process.exit(1);
    }
    console.log(`Every post has a social card (${entries.length}).`);
    return;
  }

  mkdirSync(OUT, { recursive: true });
  const browser = await puppeteer.launch({
    executablePath: chromiumPath(),
    args: ['--no-sandbox', '--disable-dev-shm-usage', '--lang=en'],
  });
  try {
    for (const post of entries) {
      // A fresh page per card: a reused page timed out on the second setContent.
      const page = await browser.newPage();
      await page.setViewport({ width: WIDTH, height: HEIGHT, deviceScaleFactor: 1 });
      const tags = (parseFrontMatter(readFileSync(join(CONTENT, post.slug, 'en.md'), 'utf8')).lists.tags ?? []).slice(0, 3);
      await page.setContent(
        card({
          title: post.title,
          lead: shorten(post.lead ?? ''),
          author: post.author ?? '',
          date: post.date ?? '',
          tags,
        }),
        { waitUntil: 'networkidle0' },
      );
      // The webfont arrives after networkidle0 on a cold cache often enough to matter.
      await page.evaluate(() => document.fonts.ready);
      const png = await page.screenshot({ type: 'png' });
      writeFileSync(join(OUT, `${post.slug}.png`), png);
      console.log(`  ${post.slug}.png  ${(png.length / 1024).toFixed(0)} KB`);
      await page.close();
    }
  } finally {
    await browser.close();
  }
  console.log(`Wrote ${entries.length} social card(s) to public/blog/.`);
}

main().catch((err) => {
  console.error(err.message);
  process.exit(1);
});
