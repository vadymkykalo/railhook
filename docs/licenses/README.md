# Dependency license report & SBOM

Generated, versioned inventories of every dependency Railhook pulls in and the
license it ships under — see `../../NOTICE` and `DECISIONS.md` for the license
questions this exists to answer (Bitnami subchart pins, and why the one AGPL
component this repo used to carry was removed rather than argued for).

| File | What | Tool |
|---|---|---|
| `backend-THIRD-PARTY.txt` | License per Maven dependency (common/api/worker/cli, 230 entries) | [license-maven-plugin](https://www.mojohaus.org/license-maven-plugin/) |
| `backend-sbom.json` | CycloneDX 1.5 SBOM, 191 components | [cyclonedx-maven-plugin](https://github.com/CycloneDX/cyclonedx-maven-plugin) |
| `frontend-licenses.json` | License per npm dependency (`webhook-platform-ui`, 654 packages) | [license-checker](https://github.com/davglass/license-checker) |
| `frontend-sbom.json` | CycloneDX 1.6 SBOM, 619 components | [@cyclonedx/cyclonedx-npm](https://github.com/CycloneDX/cyclonedx-node-npm) |

The SDKs (`sdks/node`, `sdks/python`, `sdks/php`) are intentionally not
included here — each is small enough to read directly (the Node SDK in
particular ships **zero runtime dependencies**, using `node:https` directly),
and none pulls in anything copyleft; see each SDK's own `package.json` /
`pyproject.toml` / `composer.json`.

## Regenerating

```bash
# Backend (from repo root; needs `mvn install` first so the reactor resolves)
mvn install -DskipTests -pl webhook-platform-common,webhook-platform-api,webhook-platform-worker,webhook-platform-cli,webhook-platform-coverage-report -am
mvn org.codehaus.mojo:license-maven-plugin:2.4.0:aggregate-add-third-party -DskipTests
cp target/generated-sources/license/THIRD-PARTY.txt docs/licenses/backend-THIRD-PARTY.txt
mvn org.cyclonedx:cyclonedx-maven-plugin:2.8.0:makeAggregateBom -DskipTests -DoutputFormat=json -DoutputName=sbom
cp target/sbom.json docs/licenses/backend-sbom.json

# Frontend
cd webhook-platform-ui
npm ci
npx license-checker --json --excludePrivatePackages > ../docs/licenses/frontend-licenses.json
npx @cyclonedx/cyclonedx-npm --output-format json --output-file ../docs/licenses/frontend-sbom.json
```

`.github/workflows/license-report.yml` runs the same commands on a monthly
schedule and on manual dispatch, uploading fresh copies as CI artifacts so a
drift check is possible without committing generated JSON on every dependency
bump — download the artifact and diff it against what's committed here if you
want to confirm nothing copyleft snuck in since the last manual refresh.
Re-run the commands above and commit the result whenever a dependency change
touches licensing (new dependency, major version bump of something
copyleft-adjacent) or before a release.
