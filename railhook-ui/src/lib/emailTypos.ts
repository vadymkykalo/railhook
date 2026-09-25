/** Undelegated endings (.con) are refused, as EmailTypoPolicy does; near-miss domains are only suggested. */

const POPULAR_DOMAINS = [
  'gmail.com', 'googlemail.com', 'yahoo.com', 'yahoo.co.uk', 'hotmail.com', 'hotmail.co.uk',
  'outlook.com', 'live.com', 'msn.com', 'icloud.com', 'me.com', 'mac.com', 'aol.com',
  'proton.me', 'protonmail.com', 'gmx.com', 'gmx.de', 'mail.com', 'zoho.com', 'yandex.com',
  'ukr.net', 'i.ua', 'meta.ua', 'bigmir.net',
];

/** Mirrored in EmailTypoPolicy.java. */
export const IMPOSSIBLE_TLDS: Record<string, string> = {
  con: 'com',
  cmo: 'com',
  comm: 'com',
  ocm: 'com',
  cpm: 'com',
  vom: 'com',
  xom: 'com',
  coom: 'com',
  comn: 'com',
  nte: 'net',
  nett: 'net',
  nt: 'net',
  ogr: 'org',
  orgg: 'org',
};

/** Short domains are one keystroke from too many real ones to guess at. */
const FUZZY_MIN_DOMAIN_LENGTH = 7;

function split(email: string): { local: string; domain: string } | null {
  const trimmed = email.trim();
  const at = trimmed.lastIndexOf('@');
  if (at <= 0 || at !== trimmed.indexOf('@') || /\s/.test(trimmed)) return null;
  const domain = trimmed.slice(at + 1).toLowerCase();
  if (!domain.includes('.') || domain.startsWith('.') || domain.endsWith('.')) return null;
  return { local: trimmed.slice(0, at), domain };
}

function distance(a: string, b: string): number {
  const d: number[][] = Array.from({ length: a.length + 1 }, (_, i) => [i, ...Array(b.length).fill(0)]);
  for (let j = 1; j <= b.length; j++) d[0][j] = j;
  for (let i = 1; i <= a.length; i++) {
    for (let j = 1; j <= b.length; j++) {
      const cost = a[i - 1] === b[j - 1] ? 0 : 1;
      d[i][j] = Math.min(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + cost);
      if (i > 1 && j > 1 && a[i - 1] === b[j - 2] && a[i - 2] === b[j - 1]) {
        d[i][j] = Math.min(d[i][j], d[i - 2][j - 2] + 1);
      }
    }
  }
  return d[a.length][b.length];
}

function tldOf(domain: string): string {
  return domain.slice(domain.lastIndexOf('.') + 1);
}

export function hasImpossibleTld(email: string): boolean {
  const parts = split(email);
  return parts !== null && tldOf(parts.domain) in IMPOSSIBLE_TLDS;
}

export function suggestEmail(email: string): string | null {
  const parts = split(email);
  if (!parts) return null;
  const { local, domain } = parts;
  if (POPULAR_DOMAINS.includes(domain)) return null;

  if (domain.length >= FUZZY_MIN_DOMAIN_LENGTH) {
    const near = POPULAR_DOMAINS.find((known) => distance(domain, known) === 1);
    if (near) return `${local}@${near}`;
  }

  const tld = tldOf(domain);
  const fixed = IMPOSSIBLE_TLDS[tld];
  if (fixed) return `${local}@${domain.slice(0, domain.length - tld.length)}${fixed}`;
  return null;
}
