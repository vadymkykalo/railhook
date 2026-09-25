import { afterEach, describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { initCSP } from '../csp';

/** Without a token nothing is added: self-hosted installs must never report to anyone. */
const root = resolve(dirname(fileURLToPath(import.meta.url)), '..', '..', '..');
const SCRIPT = readFileSync(join(root, 'public', 'analytics.js'), 'utf8');
const BEACON = 'https://static.cloudflareinsights.com/beacon.min.js';
const TOKEN = '0123456789abcdef0123456789abcdef';

function run(config: Record<string, string> | undefined) {
  window.__RAILHOOK__ = config;
  new Function(SCRIPT)();
}

function beacons() {
  return [...document.querySelectorAll<HTMLScriptElement>(`script[src="${BEACON}"]`)];
}

function policy() {
  return document.head.querySelector<HTMLMetaElement>('meta[http-equiv="Content-Security-Policy"]')?.content ?? '';
}

afterEach(() => {
  document.head.innerHTML = '';
  document.body.innerHTML = '';
  delete window.__RAILHOOK__;
});

describe('public/analytics.js', () => {
  it('adds the beacon with the configured token', () => {
    run({ webAnalyticsToken: TOKEN });
    const [beacon] = beacons();
    expect(beacon).toBeDefined();
    expect(beacon.defer).toBe(true);
    expect(JSON.parse(beacon.getAttribute('data-cf-beacon') ?? '{}')).toEqual({ token: TOKEN });
  });

  it('adds nothing without a token, or without a runtime config at all', () => {
    run({ webAnalyticsToken: '' });
    run(undefined);
    expect(beacons()).toHaveLength(0);
  });

  it('adds nothing inside the customer portal, which is someone else’s product', () => {
    window.history.pushState({}, '', '/portal');
    run({ webAnalyticsToken: TOKEN });
    expect(beacons()).toHaveLength(0);
    window.history.pushState({}, '', '/');
  });
});

describe('the Content-Security-Policy', () => {
  it('lets the beacon load and report when analytics is on', () => {
    window.__RAILHOOK__ = { webAnalyticsToken: TOKEN };
    initCSP();
    expect(policy()).toMatch(/script-src [^;]*https:\/\/static\.cloudflareinsights\.com/);
    expect(policy()).toMatch(/connect-src [^;]*https:\/\/cloudflareinsights\.com/);
  });

  it('stays as tight as before when it is off', () => {
    window.__RAILHOOK__ = {};
    initCSP();
    expect(policy()).not.toContain('cloudflareinsights');
  });
});
