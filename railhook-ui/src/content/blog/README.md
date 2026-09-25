# Writing a blog post

The blog at `/blog` is built from this directory. One directory per post, named for its slug, with
one Markdown file per language. Nothing else has to be edited by hand except the two generated
files at the bottom of this page.

```
src/content/blog/
  README.md                          ← this file
  <slug>/
    en.md                            ← required
    uk.md                            ← required: a real translation, not a machine pass
```

The slug is the URL: `<slug>/` becomes `/blog/<slug>`. Keep it short, lowercase, hyphenated, and
in English in both languages — it is one address serving both.

## Front matter

Every file opens with a `---` block. The parser (`src/lib/frontMatter.ts`) understands
`key: value` and `key: [a, b, c]`, and nothing else — no nesting, no multi-line values.

```yaml
---
title: What Stripe, GitHub and Shopify actually do when your endpoint is down
lead: Three providers, three completely different answers.
description: Stripe, GitHub and Shopify retry failed webhooks on wildly different schedules.
date: 2026-09-19
author: Vadym Kykalo
tags: [webhooks, reliability, stripe]
sourcesCheckedOn: 2026-09-19
---
```

| Field | Required | What it is |
|---|---|---|
| `title` | yes | The `<h1>`, the `<title>` and the feed item's title. |
| `lead` | yes | The standfirst under the title, and the card on the index. One or two sentences. |
| `description` | yes | The meta description. Written for a search result, so **not** a copy of the lead. Aim for 120–160 characters. |
| `date` | yes | `YYYY-MM-DD`. Decides the order everywhere: index, sitemap, feed. |
| `author` | yes | `Vadym Kykalo`. |
| `tags` | yes | A flat list. Shown on the index and the article, and published as `keywords`. |
| `sourcesCheckedOn` | when the post links out | `YYYY-MM-DD`: the day every external claim was read from its source. A test fails a post that links to an `http(s)` URL without it. |
| `image` | no | The social card. Defaults to `/blog/<slug>.png`, which is what you want. |

`title`, `lead` and `description` are translated per file; `date`, `author` and
`sourcesCheckedOn` should match between the two.

## The Markdown subset

`src/lib/markdown.ts` is a small parser, not a Markdown library — its output is data, so nothing
from a file is ever inserted as HTML. It reads exactly this and silently treats anything else as a
paragraph:

- `## Heading` and `### Heading`. Each gets an anchor and an entry in the table of contents.
  **No `#`**: the `<h1>` is the front matter's `title`.
- Paragraphs, separated by a blank line. A paragraph may be wrapped across lines.
- `- item` and `1. item` lists, one level deep. A wrapped item continues on an indented line.
- `> quoted line` — a pull quote. Consecutive `>` lines join into one.
- ```` ```bash ```` … ```` ``` ```` — a fenced code block, highlighted by the app's own
  highlighter. Languages it knows: `bash`, `json`, `http`, `javascript`, `python`, `php`.
- `| a | b |` with a `|---|---|` rule under the header row — a table.
- Inline: `**strong**`, `*emphasis*`, `` `code` ``, `[text](href)`, `![alt](src)`.
  A link may be an `http(s)` URL, a site path (`/pricing`, `/docs/`) or an `#anchor`; anything
  else is dropped and only its text kept.
- `:::figure <key>` on a line of its own — see below.

There is no horizontal rule, no footnote, no nested list and no inline HTML. If a post needs one
of those, add it to the parser and its test rather than working around it.

## Figures, charts and logos

Drawings are React components registered in `src/components/blog/figures.tsx`, placed from a post
by key:

```
:::figure provider-retries
```

Registered today: `provider-retries`, `retry-ladder`, `gateway`, and from
`figures/outbox.tsx` `delivery-semantics`, `dual-write`, `outbox-pipeline`, `ordering-hold`. A post naming a key nobody drew
renders nothing, and `src/lib/__tests__/blog.test.ts` fails on it.

To add one: write the component in `figures.tsx` (or, for a post's own set, in a file under
`figures/` that exports a map spread into `FIGURES`), register it in the `FIGURES` map, and give it

- a `role="img"` with a written-out `aria-label` making the same point the picture does, and a
  caption — the `Figure` wrapper takes both;
- colours from `src/components/charts/chartTheme.ts` only (`SERIES`, `CHROME`), never a literal
  hex, so it works on paper and on ink. `ok` / `retry` / `halt` / `idle` are reserved for what
  they name; anything else takes ink (`--primary`);
- every word inside as a translation key in **both** `src/i18n/locales/en.json` and `uk.json`,
  under `blog.figures.<key>`.

Do not add a charting dependency. These are inline SVG on purpose: the public pages are
prerendered and every kilobyte is paid for by a first-time reader.

Provider logos are already bundled in `public/logos/brand/` (stripe, github, shopify, slack,
postgresql, redis, apachekafka, google) with a `SOURCES.md` recording where each came from and
under what terms. Reuse those — in prose with `![](/logos/brand/stripe.svg)`, in a figure with an
`<image href="/logos/brand/stripe.svg">`. Do not draw a new brand mark, do not fetch one from a
CDN (the app ships `img-src 'self' data: blob:`), and do not add a file to that directory without
adding its row to `SOURCES.md`.

## Facts and sources

The blog's whole value is that its numbers are checkable.

- Every claim about a third party carries a link to that party's own documentation, in the
  sentence or in the table row that makes it.
- Read the page; do not quote from memory or from a blog post about the page. Several widely
  repeated figures are years out of date.
- If a provider does not publish something, say that it does not publish it. That is a fact too,
  and it is more useful than a plausible number.
- Put the day you checked in `sourcesCheckedOn`. The article prints it.

## Adding a post: the checklist

1. `mkdir src/content/blog/<slug>` and write `en.md` and `uk.md`.
2. `npm run blog:og` — draws `public/blog/<slug>.png`, the social card `og:image` points at.
   Commit the PNG. (Needs a local Chrome/Chromium; the script says so if it cannot find one.)
3. `npm run seo:sitemap` — rewrites `public/sitemap.xml`. Commit it; CI fails on a stale copy.
4. `npx vitest run src/lib/__tests__/blog.test.ts src/pages/__tests__/BlogPage.test.tsx` and then
   the rest of the suite.

Routes and the feed need no edit at all:

- `scripts/public-routes.mjs` enumerates the directories here, so the prerender and the sitemap
  pick a new post up on their own. Only `/blog` itself is spelled out there.
- `src/router.tsx` has one `/blog/:slug` route for every post.
- The RSS feed at `/blog/rss.xml` is written by the `blogRss()` plugin in `vite.config.ts` from
  this directory, through the same front-matter parser. It is English-only, because the site
  serves one URL per page in both languages.
- The index links posts newest first; `previous` / `next` on an article follow the same order.

## What not to do

- Do not hand-edit `public/sitemap.xml` or `dist/blog/rss.xml`. Both are generated.
- Do not add a UI string to a post's page in English. Every word of chrome is a key in both locale
  files; `src/i18n/__tests__/locales.test.ts` fails on a key present in one and missing from the
  other. Prose inside a `.md` file is content, not chrome, and is not a key.
- Do not use i18next plurals (`_one` / `_other`) for anything the blog prints: Ukrainian has four
  plural forms, so the two locale files would stop having the same key paths and the parity test
  would fail. Interpolate the number instead.
- Do not publish an English post without its Ukrainian translation. The app falls back to English
  so nothing 404s, but a test fails, and a reader gets the wrong language.
