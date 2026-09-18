import { describe, expect, it } from 'vitest';
import { brandVariables, embeddingAllowed, readPortalParams, validHex, validOrigin } from '../portalParams';

const at = (search: string, hash = '') => readPortalParams({ search, hash });

describe('readPortalParams', () => {
  it('takes the token from the fragment, and only a portal token', () => {
    expect(at('', '#rhp_abc-DEF_123').token).toBe('rhp_abc-DEF_123');
    expect(at('', '#eyJhbGciOi').token).toBeNull();
    expect(at('?token=rhp_abc').token).toBeNull();
  });

  it('accepts a brand colour in either spelling and ignores anything else', () => {
    expect(at('?primary=1D4BFF').primary).toBe('#1d4bff');
    expect(at('?primary=%231d4bff').primary).toBe('#1d4bff');
    expect(at('?primary=0af').primary).toBe('#00aaff');
    expect(at('?primary=red').primary).toBeNull();
    expect(at('?primary=1234567').primary).toBeNull();
  });

  it('accepts only an https logo', () => {
    expect(at('?logo=https://cdn.example.com/logo.svg').logo).toBe('https://cdn.example.com/logo.svg');
    expect(at('?logo=http://cdn.example.com/logo.svg').logo).toBeNull();
    expect(at('?logo=javascript:alert(1)').logo).toBeNull();
    expect(at('?logo=data:image/svg+xml,<svg/>').logo).toBeNull();
  });

  it('accepts the two themes and the two languages, and nothing else', () => {
    expect(at('?theme=dark&lang=uk')).toMatchObject({ theme: 'dark', lang: 'uk' });
    expect(at('?theme=blue&lang=fr')).toMatchObject({ theme: null, lang: null });
  });

  it('reads the embedding origin, encoded or not', () => {
    expect(at('?origin=https://app.example.com').origin).toBe('https://app.example.com');
    expect(at('?origin=https%3A%2F%2Fapp.example.com%3A8443').origin).toBe('https://app.example.com:8443');
    expect(at('?origin=https://app.example.com/path').origin).toBeNull();
  });
});

describe('validOrigin', () => {
  it('matches what the API accepts for an allowed origin', () => {
    expect(validOrigin('https://app.example.com')).toBe('https://app.example.com');
    expect(validOrigin('http://localhost:3000')).toBe('http://localhost:3000');
    expect(validOrigin('http://app.example.com')).toBeNull();
    expect(validOrigin('https://app.example.com/')).toBeNull();
  });
});

describe('brandVariables', () => {
  it('turns the colour into the theme variables, with readable text on it', () => {
    expect(brandVariables(validHex('1D4BFF')!)).toMatchObject({
      '--primary': '227.8 100% 55.7%',
      '--primary-foreground': '0 0% 100%',
    });
    expect(brandVariables('#ffe066')['--primary-foreground']).toBe('226.7 29% 6.1%');
  });
});

describe('embeddingAllowed', () => {
  const allowed = 'https://app.example.com';

  it('lets a session without an allowed origin render anywhere', () => {
    expect(embeddingAllowed({ allowedOrigin: undefined, originParam: null, framed: true })).toBe(true);
  });

  it('renders inside the allowed origin when the URL names it', () => {
    expect(embeddingAllowed({
      allowedOrigin: allowed, originParam: allowed, framed: true, ancestorOrigins: [allowed],
    })).toBe(true);
  });

  it('refuses a frame whose URL does not name the session origin', () => {
    // No origin in the URL: nginx let any https page frame it, so the page must refuse.
    expect(embeddingAllowed({ allowedOrigin: allowed, originParam: null, framed: true })).toBe(false);
    // Someone else's origin written into the URL to get past frame-ancestors.
    expect(embeddingAllowed({
      allowedOrigin: allowed, originParam: 'https://evil.example', framed: true,
    })).toBe(false);
  });

  it('refuses when the browser reports a different embedder', () => {
    expect(embeddingAllowed({
      allowedOrigin: allowed, originParam: allowed, framed: true, ancestorOrigins: ['https://evil.example'],
    })).toBe(false);
  });

  it('lets a top-level tab through unless its URL names another origin', () => {
    expect(embeddingAllowed({ allowedOrigin: allowed, originParam: null, framed: false })).toBe(true);
    expect(embeddingAllowed({ allowedOrigin: allowed, originParam: 'https://evil.example', framed: false })).toBe(false);
  });
});
