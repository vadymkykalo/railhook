#!/usr/bin/env bash
set -euo pipefail

# Bumps every version source in this repo in one step: the reactor poms, the
# Helm chart, the UI package.json (+ lockfile), and the three SDK manifests.
#
# This replaces the old release process step ("2. Update version numbers",
# CONTRIBUTING.md), which meant hand-editing six files and was the direct
# cause of the drift that followed: root pom.xml stuck at 1.0.0-SNAPSHOT while
# eight releases were tagged and Chart.yaml/ui/package.json never moved.
#
# Usage:
#   scripts/set-version.sh <new-version>            e.g. scripts/set-version.sh 2.3.0
#   scripts/set-version.sh <new-version>-SNAPSHOT    for develop's next-cycle bump
#
# A trailing -SNAPSHOT is applied to the reactor poms only (Maven convention);
# every other file gets the version with -SNAPSHOT stripped, since Helm/npm/
# PyPI/Packagist have no equivalent concept and always describe the last
# real release.
#
# After running this, `scripts/check-version-drift.sh` should pass.

if [ $# -ne 1 ] || [ -z "$1" ]; then
  echo "Usage: $0 <new-version>" >&2
  echo "  e.g. $0 2.3.0" >&2
  echo "       $0 2.3.0-SNAPSHOT" >&2
  exit 1
fi

NEW_VERSION="$1"
RELEASE_VERSION="${NEW_VERSION%-SNAPSHOT}"

cd "$(git rev-parse --show-toplevel)"

echo "Setting reactor (pom.xml, all modules) version to $NEW_VERSION"
mvn -q versions:set -DnewVersion="$NEW_VERSION" -DgenerateBackupPoms=false -DprocessAllModules=true

echo "Setting Helm chart version/appVersion to $RELEASE_VERSION"
sed -i.bak -E "s/^version: .*/version: $RELEASE_VERSION/" deploy/helm/railhook/Chart.yaml
sed -i.bak -E "s/^appVersion: .*/appVersion: \"$RELEASE_VERSION\"/" deploy/helm/railhook/Chart.yaml
rm -f deploy/helm/railhook/Chart.yaml.bak

echo "Setting webhook-platform-ui version to $RELEASE_VERSION"
node -e "
const fs = require('fs');
const version = '$RELEASE_VERSION';
for (const f of ['webhook-platform-ui/package.json', 'webhook-platform-ui/package-lock.json']) {
  const data = JSON.parse(fs.readFileSync(f, 'utf8'));
  data.version = version;
  if (data.packages && data.packages['']) data.packages[''].version = version;
  fs.writeFileSync(f, JSON.stringify(data, null, 2) + '\n');
}
"

echo "Setting sdks/node version to $RELEASE_VERSION"
node -e "
const fs = require('fs');
const f = 'sdks/node/package.json';
const data = JSON.parse(fs.readFileSync(f, 'utf8'));
data.version = '$RELEASE_VERSION';
fs.writeFileSync(f, JSON.stringify(data, null, 2) + '\n');
"

echo "Setting sdks/python version to $RELEASE_VERSION"
sed -i.bak -E "s/^version = \".*\"/version = \"$RELEASE_VERSION\"/" sdks/python/pyproject.toml
rm -f sdks/python/pyproject.toml.bak

echo "Setting sdks/php version to $RELEASE_VERSION"
node -e "
const fs = require('fs');
const f = 'sdks/php/composer.json';
const data = JSON.parse(fs.readFileSync(f, 'utf8'));
data.version = '$RELEASE_VERSION';
fs.writeFileSync(f, JSON.stringify(data, null, 2) + '\n');
"

echo ""
# The SDKs also carry the version in code, not just in the manifest: each client
# builds its User-Agent from it, and Python exposes it as __version__. Those four
# constants were unmanaged and drifted two minor versions behind the manifests,
# so every SDK reported a wrong User-Agent and railhook.__version__ lied.
echo "Setting SDK in-code version constants to $RELEASE_VERSION"
sed -i.bak -E "s/^const SDK_VERSION = '.*';/const SDK_VERSION = '$RELEASE_VERSION';/" sdks/node/src/client.ts
sed -i.bak -E "s/^SDK_VERSION = \".*\"/SDK_VERSION = \"$RELEASE_VERSION\"/" sdks/python/railhook/client.py
sed -i.bak -E "s/^__version__ = \".*\"/__version__ = \"$RELEASE_VERSION\"/" sdks/python/railhook/__init__.py
sed -i.bak -E "s/private const SDK_VERSION = '.*';/private const SDK_VERSION = '$RELEASE_VERSION';/" sdks/php/src/Railhook.php
rm -f sdks/node/src/client.ts.bak sdks/python/railhook/client.py.bak \
      sdks/python/railhook/__init__.py.bak sdks/php/src/Railhook.php.bak

echo "Done. Reactor is at $NEW_VERSION; Chart.yaml, UI and SDKs are at $RELEASE_VERSION."
echo "Verify with: scripts/check-version-drift.sh"
