#!/usr/bin/env node
/**
 * Derives `src/data/env-reference.json` from the repository's `.env.dist`.
 *
 * `.env.dist` is where every setting is documented — CLAUDE.md requires it of each new
 * variable — so the configuration reference is read from there rather than retyped. A
 * variable added with its comment appears in the docs on the next regeneration, and CI
 * fails while the committed JSON lags behind.
 *
 * What the parser understands, because it is what the file does:
 *   - a section is a `# ----` rule with an upper-case title beside it, or `# ─── Title ───`;
 *   - a variable's description is the comment block directly above it, with no blank line;
 *   - consecutive variables with no comment between them share that description;
 *   - `# KEY=value` is a documented variable left unset, its value an example, not a default;
 *   - an `Options:` / `Note:` comment directly under a variable belongs to that variable.
 *
 *   node scripts/env-reference.mjs           regenerate (commit the result)
 *   node scripts/env-reference.mjs --check   fail if the committed copy is stale
 */
import { readFileSync, writeFileSync, existsSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

const SOURCE = fileURLToPath(new URL('../../.env.dist', import.meta.url));
const OUT = fileURLToPath(new URL('../src/data/env-reference.json', import.meta.url));

const RULE = /^#\s*-{10,}\s*$/;
const BANNER = /^#\s*={10,}\s*$/;
const BOX_TITLE = /^#\s*─+\s*(.+?)\s*─+\s*$/;
const ACTIVE_VAR = /^([A-Z][A-Z0-9_]*)=(.*)$/;
// `# KEY=value` with no spaces in the value (or flags, `-Xms256m -Xmx384m`). Prose that
// happens to start with an assignment — `# APP_ENV=production it arrives as JSON` — is not one.
const COMMENTED_VAR = /^# ?([A-Z][A-Z0-9_]*)=(\S*|-\S.*)$/;
const UPPER_TITLE = /^([A-Z][A-Z0-9 /&-]*[A-Z0-9)])(?:\s*(\(.*))?$/;
const TRAILING_NOTE = /^(Options|Note):/;

/** Upper-case words stay upper-case: they are acronyms, not shouting. */
const ACRONYMS = new Set(['API', 'UI', 'CORS', 'JVM', 'DLQ', 'FIFO', 'CLI', 'SMTP', 'HTTP', 'TLS', 'DB', 'JWT', 'PII', 'README']);

function sentenceCase(title) {
  let first = true;
  return title.replace(/[A-Za-z][A-Za-z0-9_=]*/g, (word) => {
    const keep = ACRONYMS.has(word) || /[_=0-9]/.test(word) || word !== word.toUpperCase();
    const out = keep ? word : first ? word[0] + word.slice(1).toLowerCase() : word.toLowerCase();
    first = false;
    return out;
  });
}

/** Comment lines → text. A bare `#` is a paragraph break. */
function toText(lines) {
  return lines
    .map((line) => line.replace(/^# ?/, ''))
    .join('\n')
    .split(/\n\s*\n/)
    .map((para) => para.split('\n').map((l) => l.trimEnd()).join('\n').trim())
    .filter(Boolean)
    .join('\n\n');
}

export function parseEnvDist(source) {
  const lines = source.split(/\r?\n/);
  const sections = [];
  let section = { title: 'General', variables: [] };
  let comments = [];
  /** The description shared by the current run of consecutive variables. */
  let runDescription = null;
  /** The last variable, while a trailing note can still attach to it. */
  let lastVar = null;
  let lastWasActiveVar = false;

  const startSection = (title) => {
    if (section.variables.length) sections.push(section);
    section = { title: sentenceCase(title), variables: [] };
    comments = [];
    runDescription = null;
    lastVar = null;
  };

  for (let i = 0; i < lines.length; i++) {
    const line = lines[i].trimEnd();

    if (line === '') {
      comments = [];
      runDescription = null;
      lastVar = null;
      lastWasActiveVar = false;
      continue;
    }

    if (BANNER.test(line)) {
      comments = [];
      runDescription = null;
      lastVar = null;
      continue;
    }

    // `# ─── Dashboard error reporting ───` titles a few variables inside a larger section,
    // not a section of its own: it leads their description.
    const box = line.match(BOX_TITLE);
    if (box) {
      comments = [`# ${box[1]}.`, '#'];
      runDescription = null;
      lastVar = null;
      continue;
    }

    if (RULE.test(line)) {
      // Title above the rule (`# TITLE` then `# ----`), possibly followed by prose.
      const above = comments.length ? toText([comments[0]]).match(UPPER_TITLE) : null;
      if (above) {
        const rest = [...(above[2] ? [`# ${above[2]}`] : []), ...comments.slice(1)];
        startSection(above[1]);
        comments = rest;
        continue;
      }
      // Title between two rules (`# ----` / `# TITLE` / `# ----`).
      const next = lines[i + 1]?.trimEnd() ?? '';
      if (next.startsWith('#') && !RULE.test(next) && toText([next]).match(UPPER_TITLE)) {
        startSection(toText([next]).match(UPPER_TITLE).slice(1).filter(Boolean).join(' '));
        i += RULE.test(lines[i + 2]?.trimEnd() ?? '') ? 2 : 1;
        continue;
      }
      comments = [];
      continue;
    }

    const active = line.match(ACTIVE_VAR);
    const commented = active ? null : line.match(COMMENTED_VAR);
    const match = active ?? commented;

    if (match) {
      if (comments.length) {
        runDescription = toText(comments);
      } else if (commented && lastWasActiveVar) {
        // `API_REPLICAS=1` followed by `# UI_MEMORY_LIMIT=128m`: a different setting, not
        // one more member of the run above it.
        runDescription = null;
      }
      comments = [];
      lastVar = {
        name: match[1],
        default: active ? match[2] : null,
        example: commented && match[2] !== '' ? match[2] : null,
        description: runDescription ?? '',
      };
      section.variables.push(lastVar);
      lastWasActiveVar = Boolean(active);
      continue;
    }

    if (line.startsWith('#')) {
      const text = toText([line]);
      if (lastVar && comments.length === 0 && TRAILING_NOTE.test(text)) {
        lastVar.description = [lastVar.description, text].filter(Boolean).join('\n\n');
        runDescription = lastVar.description;
        continue;
      }
      if (lastVar && comments.length === 0 && TRAILING_NOTE.test(toText(lines.slice(i - 1, i)))) {
        // A second line continuing a trailing note.
        lastVar.description = `${lastVar.description}\n${text}`;
        continue;
      }
      comments.push(line);
      lastVar = null;
      continue;
    }
  }
  if (section.variables.length) sections.push(section);

  // A name documented twice (once commented, once set) is listed once, at its first place.
  const seen = new Set();
  for (const s of sections) {
    s.variables = s.variables.filter((v) => (seen.has(v.name) ? false : seen.add(v.name)));
  }
  return sections.filter((s) => s.variables.length);
}

function render() {
  const sections = parseEnvDist(readFileSync(SOURCE, 'utf8'));
  return `${JSON.stringify({ source: '.env.dist', sections }, null, 2)}\n`;
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const json = render();
  const count = JSON.parse(json).sections.reduce((n, s) => n + s.variables.length, 0);
  if (process.argv.includes('--check')) {
    if (!existsSync(OUT) || readFileSync(OUT, 'utf8') !== json) {
      console.error('src/data/env-reference.json is stale against .env.dist. Run: npm run env:reference');
      process.exit(1);
    }
    console.log(`env-reference.json is up to date (${count} variables).`);
  } else {
    writeFileSync(OUT, json);
    console.log(`Wrote src/data/env-reference.json (${count} variables).`);
  }
}
