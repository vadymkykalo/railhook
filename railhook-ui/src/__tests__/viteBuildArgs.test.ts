import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..', '..', '..');
const read = (p: string) => readFileSync(join(repoRoot, p), 'utf8');

/** VITE_CAPTCHA_* were documented but passed by nobody, which refused every registration. */
describe('VITE_ build arguments', () => {
  const envDist = read('.env.dist');
  const dockerfile = read('railhook-ui/Dockerfile');
  const composeBuild = read('docker-compose.build.yml');

  const documented = [...new Set(
    [...envDist.matchAll(/^#?\s*(VITE_[A-Z0-9_]+)=/gm)].map((m) => m[1]),
  )].sort();

  it('finds the variables it is meant to be checking', () => {
    // An empty list would make every assertion below pass vacuously.
    expect(documented.length).toBeGreaterThanOrEqual(2);
    expect(documented).toContain('VITE_API_URL');
  });

  it('has nothing that differs between deployments: those are runtime settings of the UI container', () => {
    // One image serves every deployment, so per-deployment values are runtime settings.
    const files = {
      envDist,
      dockerfile,
      composeBuild,
      vite: read('railhook-ui/vite.config.ts'),
      viteEnv: read('railhook-ui/src/vite-env.d.ts'),
      csp: read('railhook-ui/src/lib/csp.ts'),
      siteUrl: read('railhook-ui/src/lib/siteUrl.ts'),
      captcha: read('railhook-ui/src/components/CaptchaWidget.tsx'),
      docs: read('railhook-docs/astro.config.mjs'),
    };
    for (const [file, text] of Object.entries(files)) {
      expect(text, file).not.toMatch(/VITE_(CONTACT_DOMAIN|SITE_URL|CAPTCHA_SITE_KEY|CAPTCHA_SCRIPT_URL)/);
    }
  });

  it.each(documented)('%s is declared as an ARG in the Dockerfile', (name) => {
    expect(dockerfile).toMatch(new RegExp(`^ARG ${name}=`, 'm'));
  });

  it.each(documented)('%s is promoted to ENV so Vite can read it', (name) => {
    expect(dockerfile).toMatch(new RegExp(`^ENV ${name}=\\$${name}$`, 'm'));
  });

  it.each(documented)('%s is passed as a build arg by the build overlay', (name) => {
    expect(composeBuild).toMatch(new RegExp(`^\\s+${name}: \\$\\{${name}:-`, 'm'));
  });
});

/** A browser enforces the intersection of header and meta CSPs; csp.ts owns the policy. */
describe('content-security-policy ownership', () => {
  const nginxConf = read('railhook-ui/nginx.conf');
  const securityHeaders = read('railhook-ui/nginx-security-headers.conf');
  const commonHeaders = read('railhook-ui/nginx-security-headers-common.conf');

  it('nginx does not send a Content-Security-Policy of its own', () => {
    // Only frame-ancestors, which a meta tag can't carry.
    const policies = [...nginxConf.matchAll(/add_header\s+Content-Security-Policy\s+"([^"]*)"/gi)].map((m) => m[1]);
    expect(policies).toEqual(['frame-ancestors $portal_frame_ancestors']);
    expect(securityHeaders).not.toMatch(/add_header\s+Content-Security-Policy/i);
    expect(commonHeaders).not.toMatch(/add_header\s+Content-Security-Policy/i);
  });

  it('nginx still sends the headers that are not policy, and cannot be set from a meta tag', () => {
    // frame-ancestors is ignored in a meta tag, so X-Frame-Options must stay.
    expect(securityHeaders).toMatch(/add_header\s+X-Frame-Options/i);
    expect(commonHeaders).toMatch(/add_header\s+X-Content-Type-Options/i);
    expect(commonHeaders).toMatch(/add_header\s+Referrer-Policy/i);
  });
});
