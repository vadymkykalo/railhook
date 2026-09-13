/**
 * A `.env.dist` comment, hard-wrapped at ~95 columns, back into readable blocks.
 *
 * Blank-line paragraphs become separate blocks and their wrapped lines are rejoined. An
 * indented line is an example (`  WEBHOOK_TRUSTED_PROXIES=10.0.0.5,…`), so a run of them is
 * kept verbatim as code while the prose around it is rejoined.
 *
 * @param {string} text
 * @returns {Array<{ code: boolean, text: string }>}
 */
export function envParagraphs(text) {
  /** @type {Array<{ code: boolean, text: string }>} */
  const blocks = [];
  for (const para of text.split('\n\n').filter(Boolean)) {
    /** @type {{ code: boolean, lines: string[] } | null} */
    let current = null;
    for (const line of para.split('\n')) {
      const code = /^\s{2,}/.test(line);
      if (current && current.code === code) {
        current.lines.push(line);
      } else {
        if (current) blocks.push(toBlock(current));
        current = { code, lines: [line] };
      }
    }
    if (current) blocks.push(toBlock(current));
  }
  return blocks;
}

/**
 * The anchor id for a section heading: `Retry scheduler` → `env-retry-scheduler`.
 *
 * @param {string} title
 */
export function envSectionId(title) {
  return 'env-' + title.toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '');
}

/** @param {{ code: boolean, lines: string[] }} run */
function toBlock(run) {
  return run.code
    ? { code: true, text: run.lines.join('\n') }
    : { code: false, text: run.lines.map((l) => l.trim()).join(' ') };
}
