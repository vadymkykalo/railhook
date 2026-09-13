# Releasing

Maintainer's checklist. Contributors do not need any of this — see
[`CONTRIBUTING.md`](../CONTRIBUTING.md).


## One-off: claiming the Railhook names

Needed once, before the first release that publishes under the new names. Every
step here needs a human login — a registry namespace cannot be claimed by a
token that does not own it yet, and the publish tokens in CI only work on
namespaces that already exist.

The workflows are already pointed at the new names, so until these are done the
`Publish SDKs` job fails on "package not found" or "scope does not exist". That
is the intended failure: it is louder than publishing to the old names by
accident.

1. **npm** — create the `@railhook` organisation (free for public packages) with
   the account behind `NPM_TOKEN`, then after the first publish mark the old
   package as moved:

   ```bash
   npm deprecate @webhook-platform/node "moved to @railhook/node"
   ```

2. **PyPI** — the project `railhook` is created by the first `twine upload`, so
   only `PYPI_TOKEN`'s scope needs widening: a project-scoped token cannot
   create a new project. Use an account-scoped token for the first publish, then
   narrow it again. On the old project, publish nothing further; its final
   release description should point at `railhook`.

3. **Packagist** — submit `https://github.com/vadymkykalo/railhook-php` as a new
   package. Then open the old `webhook-platform/php` page and mark it abandoned,
   giving `railhook/php` as the replacement — composer then tells anyone who
   installs it where the package went.

   The old package will stop updating on its own once the split repository is
   renamed: Packagist follows the redirect, finds a `composer.json` named
   `railhook/php`, and refuses the mismatch. Marking it abandoned is what turns
   that silent failure into a message.

4. **The domain** — production is `https://railhook.io`, deployed by
   `.github/workflows/deploy-prod.yml`, running the published images with nothing
   built on the host. The production `.env` carries what differs from any other
   install, all read by the UI container at startup: `APP_BASE_URL=https://railhook.io`
   (canonical, og tags, sitemap), `RAILHOOK_CONTACT_DOMAIN=railhook.io` and
   `CAPTCHA_SITE_KEY`. The deploy fails unless `https://railhook.io/version.txt`
   answers the version it deployed.

5. **GHCR** — nothing to claim. The first release publishes `railhook-api`,
   `-worker` and `-ui` as new packages; the old `hookflow-*` ones stay pullable,
   which is what keeps existing installations running.

## Steps

1. Create release branch: `git checkout -b release/1.x.0 develop`
2. Update version numbers everywhere in one step: `make version-set VERSION=1.x.0`
   (wraps `scripts/set-version.sh`, which sets the reactor poms, `Chart.yaml`,
   `railhook-ui/package.json` and the three SDK manifests together —
   don't hand-edit them individually, that's how these drifted apart in the
   first place). Verify with `make version-check`.
3. Update `CHANGELOG.md`: move `[Unreleased]` content under the new version
   heading, and write `UPGRADING.md` notes if the release breaks anything.
4. Create PR to `main`. **Merge it with "Create a merge commit"** — not
   "Squash and merge", which is GitHub's default here and breaks step 6 (see
   Code Review above).
5. After merge, tag release: `git tag v1.x.0`
6. Merge back to `develop` and bump the reactor to the next `-SNAPSHOT`
   (`make version-set VERSION=1.x+1.0-SNAPSHOT`). With a merge commit in step 4
   this is conflict-free; after a squash it is a manual reconciliation.

CI's `version-check` job (`.github/workflows/ci.yml`) fails the build if the
pom, Chart, UI and SDK versions ever disagree again.

## Production settings

What production runs with is set in GitHub, not in a shell on the host. In **Settings →
Environments → production**, a variable named `DOTENV_<NAME>` becomes `NAME=value` in
`/opt/railhook/.env` on every deploy; use a secret with the same naming for anything
sensitive (`DOTENV_SMTP_PASSWORD`, `DOTENV_CAPTCHA_SECRET_KEY`). A secret wins over a variable
of the same name.

To change a setting, edit it there and run **Deploy to production** again — redeploying the
version that is already live is fine and applies the change. The deploy log lists the names it
sent, never the values.

The host keeps what it generated at install and a deploy must never replace: the encryption key
and salt (a new one leaves encrypted columns unreadable), `JWT_SECRET`, and the Postgres and
Redis passwords. The image tags come from the version being deployed. The helper refuses any of
these, and the whole upgrade stops before anything changes.

