#!/usr/bin/env node
/**
 * Every English page has a Ukrainian twin, and every Ukrainian page an English one.
 *
 * Starlight would quietly fill a missing translation with the English page and a "not
 * translated" notice, which is how a docs site ends up bilingual in its sidebar and
 * monolingual in its content. The contract here is stricter: both, or neither.
 *
 * It checks what is present, not the slug contract — a page not yet written in either
 * language is the sidebar's concern (it only lists pages that exist), not this check's.
 *
 *   node scripts/check-parity.mjs
 */
import { readdirSync, statSync } from 'node:fs';
import { join, relative, sep } from 'node:path';
import { fileURLToPath } from 'node:url';

const PAGE = /\.mdx?$/;
const UK = 'uk';

/** Every page under `dir`, as a slash-separated path relative to it, extension kept. */
function listPages(dir, root = dir) {
  let out = [];
  for (const name of readdirSync(dir)) {
    const full = join(dir, name);
    if (statSync(full).isDirectory()) out = out.concat(listPages(full, root));
    else if (PAGE.test(name)) out.push(relative(root, full).split(sep).join('/'));
  }
  return out;
}

/** A page's slug: the path without its extension, so `a.md` and `a.mdx` are twins. */
const slugOf = (page) => page.replace(PAGE, '');

/**
 * @param {string} docsDir the `src/content/docs` directory
 * @returns {{ missingUk: string[], missingEn: string[] }} slugs lacking a twin, sorted
 */
export function findParityGaps(docsDir) {
  const pages = listPages(docsDir);
  const en = new Set(pages.filter((p) => !p.startsWith(`${UK}/`)).map(slugOf));
  const uk = new Set(pages.filter((p) => p.startsWith(`${UK}/`)).map((p) => slugOf(p.slice(UK.length + 1))));
  return {
    missingUk: [...en].filter((slug) => !uk.has(slug)).sort(),
    missingEn: [...uk].filter((slug) => !en.has(slug)).sort(),
  };
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const docsDir = fileURLToPath(new URL('../src/content/docs', import.meta.url));
  const { missingUk, missingEn } = findParityGaps(docsDir);
  for (const slug of missingUk) console.error(`missing uk/${slug}.mdx (the English page ${slug}.mdx has no translation)`);
  for (const slug of missingEn) console.error(`missing ${slug}.mdx (the Ukrainian page uk/${slug}.mdx has no English original)`);
  if (missingUk.length || missingEn.length) process.exit(1);
  console.log('Every page has both an English and a Ukrainian version.');
}
