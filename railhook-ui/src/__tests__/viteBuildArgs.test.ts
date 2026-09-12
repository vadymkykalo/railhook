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
    expect(documented.length).toBeGreaterThanOrEqual(6);
    expect(documented).toContain('VITE_CAPTCHA_SITE_KEY');
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

  it('nginx does not send a Content-Security-Policy of its own', () => {
    expect(nginxConf).not.toMatch(/add_header\s+Content-Security-Policy/i);
  });

  it('nginx still sends the headers that are not policy, and cannot be set from a meta tag', () => {
    // frame-ancestors is ignored in a meta tag by specification, so X-Frame-Options is what
    // actually stops this being framed and has to survive.
    expect(nginxConf).toMatch(/add_header\s+X-Frame-Options/i);
    expect(nginxConf).toMatch(/add_header\s+X-Content-Type-Options/i);
    expect(nginxConf).toMatch(/add_header\s+Referrer-Policy/i);
  });
});

/**
 * A deployment with a domain gets a sitemap that names it.
 *
 * The committed sitemap says example.com, and has to: IANA reserves that name so a
 * self-hosted image does not ship a list of pages asking a crawler to go index a stranger.
 * But nothing regenerated it at build time, so a deployment that set VITE_SITE_URL got
 * correct canonical tags and a sitemap still pointing at example.com — which is worse than
 * having none, because it is an instruction to a crawler rather than an omission.
 */
describe('sitemap follows the configured origin', () => {
  const dockerfile = readFileSync(join(repoRoot, 'railhook-ui/Dockerfile'), 'utf8');

  it('the image regenerates the sitemap when a domain is configured', () => {
    expect(dockerfile).toMatch(/SITE_URL="\$VITE_SITE_URL"\s+npm run seo:sitemap/);
  });

  it("and points robots.txt's Sitemap: line at the same origin", () => {
    // A sitemap a crawler is never told about is a file nobody reads.
    expect(dockerfile).toMatch(/robots\.txt/);
  });

  it('but leaves the placeholder alone when there is no domain', () => {
    expect(dockerfile).toMatch(/if \[ -n "\$VITE_SITE_URL" \]/);
  });
});
