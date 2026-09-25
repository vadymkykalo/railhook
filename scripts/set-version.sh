#!/usr/bin/env bash
set -euo pipefail

# Usage: scripts/set-version.sh <version>[-SNAPSHOT]. -SNAPSHOT goes to the reactor poms only:
# Helm, npm, PyPI and Packagist have no such concept.

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

echo "Setting railhook-ui version to $RELEASE_VERSION"
node -e "
const fs = require('fs');
const version = '$RELEASE_VERSION';
for (const f of ['railhook-ui/package.json', 'railhook-ui/package-lock.json']) {
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

echo "Setting sdks/mcp version to $RELEASE_VERSION"
node -e "
const fs = require('fs');
const version = '$RELEASE_VERSION';
for (const f of ['sdks/mcp/package.json', 'sdks/mcp/package-lock.json']) {
  const data = JSON.parse(fs.readFileSync(f, 'utf8'));
  data.version = version;
  if (data.packages && data.packages['']) data.packages[''].version = version;
  fs.writeFileSync(f, JSON.stringify(data, null, 2) + '\n');
}
// The MCP Registry entry names the server's version and the npm package's, which are the same.
const server = JSON.parse(fs.readFileSync('sdks/mcp/server.json', 'utf8'));
server.version = version;
for (const p of server.packages) p.version = version;
fs.writeFileSync('sdks/mcp/server.json', JSON.stringify(server, null, 2) + '\n');
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
# The SDKs carry the version in code too: the User-Agent, and Python's __version__.
echo "Setting SDK in-code version constants to $RELEASE_VERSION"
sed -i.bak -E "s/^const SDK_VERSION = '.*';/const SDK_VERSION = '$RELEASE_VERSION';/" sdks/node/src/client.ts
sed -i.bak -E "s/^SDK_VERSION = \".*\"/SDK_VERSION = \"$RELEASE_VERSION\"/" sdks/python/railhook/client.py
sed -i.bak -E "s/^__version__ = \".*\"/__version__ = \"$RELEASE_VERSION\"/" sdks/python/railhook/__init__.py
sed -i.bak -E "s/private const SDK_VERSION = '.*';/private const SDK_VERSION = '$RELEASE_VERSION';/" sdks/php/src/Railhook.php
rm -f sdks/node/src/client.ts.bak sdks/python/railhook/client.py.bak \
      sdks/python/railhook/__init__.py.bak sdks/php/src/Railhook.php.bak

echo "Done. Reactor is at $NEW_VERSION; Chart.yaml, UI and SDKs are at $RELEASE_VERSION."
echo "Verify with: scripts/check-version-drift.sh"
