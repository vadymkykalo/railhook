#!/usr/bin/env node
// Starlight would silently show the English page for a missing translation.
import { readdirSync, statSync } from 'node:fs';
import { join, relative, sep } from 'node:path';
import { fileURLToPath } from 'node:url';

const PAGE = /\.mdx?$/;
const UK = 'uk';

function listPages(dir, root = dir) {
  let out = [];
  for (const name of readdirSync(dir)) {
    const full = join(dir, name);
    if (statSync(full).isDirectory()) out = out.concat(listPages(full, root));
    else if (PAGE.test(name)) out.push(relative(root, full).split(sep).join('/'));
  }
  return out;
}

const slugOf = (page) => page.replace(PAGE, '');

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
