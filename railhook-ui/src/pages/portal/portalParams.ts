/**
 * What the customer puts on the portal's URL, read and validated in one place.
 *
 * The token rides in the fragment and everything else in the query. Every value here is written
 * by someone else's page, so each is checked against the one shape it may have and dropped
 * otherwise — a bad `primary` leaves Railhook's colour, it does not break the page.
 */

export type PortalTheme = 'light' | 'dark';
export type PortalLanguage = 'en' | 'uk';

export interface PortalParams {
  token: string | null;
  /** The embedding origin the URL was issued for; see `embeddingAllowed`. */
  origin: string | null;
  /** `#rrggbb`, normalised from 3 or 6 hex digits with or without the `#`. */
  primary: string | null;
  logo: string | null;
  theme: PortalTheme | null;
  lang: PortalLanguage | null;
}

const TOKEN_PREFIX = 'rhp_';

/** The same shapes the API accepts for a session's allowed origin. */
const ORIGIN_PATTERN =
  /^(https:\/\/[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?(?:\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)*|http:\/\/(?:localhost|127\.0\.0\.1))(?::[0-9]{1,5})?$/;

export function readPortalParams(location: Pick<Location, 'hash' | 'search'>): PortalParams {
  const query = new URLSearchParams(location.search);
  // Not decoded: the token is URL-safe base64 behind its prefix, so it never needs escaping.
  const fragment = location.hash.replace(/^#/, '');
  return {
    token: fragment.startsWith(TOKEN_PREFIX) ? fragment : null,
    origin: validOrigin(query.get('origin')),
    primary: validHex(query.get('primary')),
    logo: validLogo(query.get('logo')),
    theme: oneOf(query.get('theme'), ['light', 'dark'] as const),
    lang: oneOf(query.get('lang'), ['en', 'uk'] as const),
  };
}

function oneOf<T extends string>(value: string | null, allowed: readonly T[]): T | null {
  return value && (allowed as readonly string[]).includes(value) ? (value as T) : null;
}

export function validOrigin(value: string | null): string | null {
  return value && ORIGIN_PATTERN.test(value) ? value : null;
}

export function validHex(value: string | null): string | null {
  const match = value?.trim().match(/^#?([0-9a-fA-F]{3}|[0-9a-fA-F]{6})$/);
  if (!match) return null;
  const digits = match[1].length === 3
    ? match[1].split('').map((d) => d + d).join('')
    : match[1];
  return `#${digits.toLowerCase()}`;
}

function validLogo(value: string | null): string | null {
  if (!value) return null;
  try {
    const url = new URL(value);
    return url.protocol === 'https:' ? url.toString() : null;
  } catch {
    return null;
  }
}

/** `#rrggbb` as the `H S% L%` triple the theme's CSS variables hold. */
export function hexToHslTriple(hex: string): { h: number; s: number; l: number } {
  const r = parseInt(hex.slice(1, 3), 16) / 255;
  const g = parseInt(hex.slice(3, 5), 16) / 255;
  const b = parseInt(hex.slice(5, 7), 16) / 255;
  const max = Math.max(r, g, b);
  const min = Math.min(r, g, b);
  const l = (max + min) / 2;
  let h = 0;
  let s = 0;
  if (max !== min) {
    const d = max - min;
    s = l > 0.5 ? d / (2 - max - min) : d / (max + min);
    if (max === r) h = (g - b) / d + (g < b ? 6 : 0);
    else if (max === g) h = (b - r) / d + 2;
    else h = (r - g) / d + 4;
    h *= 60;
  }
  return { h: round(h), s: round(s * 100), l: round(l * 100) };
}

function round(n: number): number {
  return Math.round(n * 10) / 10;
}

/** Relative luminance, to decide whether text on the colour should be light or dark. */
function luminance(hex: string): number {
  const channel = (i: number) => {
    const c = parseInt(hex.slice(i, i + 2), 16) / 255;
    return c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4;
  };
  return 0.2126 * channel(1) + 0.7152 * channel(3) + 0.0722 * channel(5);
}

/** The CSS variables a brand colour replaces, for both themes alike. */
export function brandVariables(hex: string): Record<string, string> {
  const { h, s, l } = hexToHslTriple(hex);
  const hover = Math.max(0, l - 8);
  return {
    '--primary': `${h} ${s}% ${l}%`,
    '--primary-hover': `${h} ${s}% ${hover}%`,
    '--ring': `${h} ${s}% ${l}%`,
    '--primary-foreground': luminance(hex) > 0.45 ? '226.7 29% 6.1%' : '0 0% 100%',
  };
}

/**
 * Whether the portal may render where it finds itself.
 *
 * Without an allowed origin on the session, anywhere. With one, the rule is enforced twice. The
 * browser does the first half: nginx serves this page with `frame-ancestors` set to the URL's
 * `origin`, so a page from any other origin cannot frame it at all. This is the second half —
 * the URL's `origin` has to be the session's. Otherwise anyone holding a token could frame it
 * from their own site by writing their own origin into the URL.
 *
 * Opened as a top-level page nothing embeds it, so there is nothing to check, unless the URL
 * names an origin that is not the session's. Where the browser reports the embedder itself
 * (`location.ancestorOrigins`, not in Firefox), that is checked too.
 */
export function embeddingAllowed({
  allowedOrigin, originParam, framed, ancestorOrigins,
}: {
  allowedOrigin?: string | null;
  originParam: string | null;
  framed: boolean;
  ancestorOrigins?: readonly string[];
}): boolean {
  if (!allowedOrigin) return true;
  if (originParam && originParam !== allowedOrigin) return false;
  if (!framed) return true;
  if (originParam !== allowedOrigin) return false;
  const parent = ancestorOrigins?.[0];
  return !parent || parent === allowedOrigin;
}
