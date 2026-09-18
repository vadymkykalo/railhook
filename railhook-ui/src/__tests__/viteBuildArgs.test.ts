import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..', '..', '..');
const read = (p: string) => readFileSync(join(repoRoot, p), 'utf8');

/**
 * Every `VITE_` variable the operator is told about actually reaches the build.
 *
 * Vite inlines `import.meta.env.VITE_*` when the bundle is compiled, so these are build
 * arguments and nothing else: as runtime environment on the nginx stage they are dead config,
 * which `docker-compose.build.yml` already says in a comment. What nothing said is that the
 * list has to be *complete*, and it was not.
 *
 * `VITE_CAPTCHA_SITE_KEY` and `VITE_CAPTCHA_SCRIPT_URL` were documented in `.env.dist`, read by
 * `src/lib/csp.ts`, and passed by nobody — not the Dockerfile, not Compose. Setting them did
 * nothing, and the failure was worse than inert: with `CAPTCHA_SECRET_KEY` set on the API and no
 * site key in the bundle, the registration page renders no challenge and sends no token, the
 * API verifies a null token, and every registration is refused. A deployment turns the
 * protection on and loses the ability to sign anyone up.
 */
describe('VITE_ build arguments', () => {
  const envDist = read('.env.dist');
  const dockerfile = read('railhook-ui/Dockerfile');
  const composeBuild = read('docker-compose.build.yml');

  const documented = [...new Set(
    [...envDist.matchAll(/^#?\s*(VITE_[A-Z0-9_]+)=/gm)].map((m) => m[1]),
  )].sort();

  it('finds the variables it is meant to be checking', () => {
    // If this ever empties out, every assertion below passes vacuously.
    expect(documented.length).toBeGreaterThanOrEqual(2);
    expect(documented).toContain('VITE_API_URL');
  });

  it('has nothing that differs between deployments: those are runtime settings of the UI container', () => {
    // The published image runs railhook.io and every self-hosted install alike. A contact domain,
    // public origin or CAPTCHA site key baked into it is either one deployment's value on all of
    // them, or — what railhook.io did — a second, hand-built image that `railhook upgrade` never
    // replaced, so a release shipped the API and left the site a version behind.
    // RAILHOOK_CONTACT_DOMAIN, RAILHOOK_SITE_URL and RAILHOOK_CAPTCHA_* replaced them, and nothing
    // is kept for the old names.
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

/**
 * The two content-security policies do not fight each other.
 *
 * `src/lib/csp.ts` writes a meta tag that widens `script-src`, `frame-src` and `connect-src` to
 * the CAPTCHA's origin when one is configured, and it was written carefully — derived from the
 * script URL so the two cannot disagree. nginx then sends a *second*, static policy in a header.
 *
 * A browser given both enforces the intersection, so the header's `script-src 'self'` silently
 * overrode all of that: the widget would have been blocked even once the site key reached the
 * bundle. One of the two has to own the policy, and it has to be the one that knows what was
 * configured.
 */
describe('content-security-policy ownership', () => {
  const nginxConf = read('railhook-ui/nginx.conf');
  const securityHeaders = read('railhook-ui/nginx-security-headers.conf');
  const commonHeaders = read('railhook-ui/nginx-security-headers-common.conf');

  it('nginx does not send a Content-Security-Policy of its own', () => {
    // One exception, and it cannot collide with the meta tag: the customer portal's
    // frame-ancestors, the one directive a meta tag is not allowed to carry. A policy header with
    // anything else in it would be intersected with the meta tag's, which is the bug this guards.
    const policies = [...nginxConf.matchAll(/add_header\s+Content-Security-Policy\s+"([^"]*)"/gi)].map((m) => m[1]);
    expect(policies).toEqual(['frame-ancestors $portal_frame_ancestors']);
    expect(securityHeaders).not.toMatch(/add_header\s+Content-Security-Policy/i);
    expect(commonHeaders).not.toMatch(/add_header\s+Content-Security-Policy/i);
  });

  it('nginx still sends the headers that are not policy, and cannot be set from a meta tag', () => {
    // frame-ancestors is ignored in a meta tag by specification, so X-Frame-Options is what
    // actually stops this being framed and has to survive. The headers live in a snippet that
    // nginx.conf includes; nginxSecurityHeaders.test.ts holds every location to it.
    expect(securityHeaders).toMatch(/add_header\s+X-Frame-Options/i);
    expect(commonHeaders).toMatch(/add_header\s+X-Content-Type-Options/i);
    expect(commonHeaders).toMatch(/add_header\s+Referrer-Policy/i);
  });
});
