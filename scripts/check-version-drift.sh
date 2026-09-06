#!/usr/bin/env bash
set -euo pipefail

# Fails when the version recorded in the reactor poms disagrees with the
# Helm chart, the UI package.json, any of the three SDK manifests, or (when
# HEAD sits exactly on a release tag) the tag itself.
#
# This exists because five sources of truth silently drifted apart for
# months: root pom.xml frozen at 1.0.0-SNAPSHOT while eight tags were cut,
# Chart.yaml and ui/package.json never touched at all.
#
# Usage:
#   scripts/check-version-drift.sh
#
# Run locally with `make version-check`; CI runs it on every push/PR via the
# `version-check` job in .github/workflows/ci.yml.

cd "$(git rev-parse --show-toplevel)"

# The root pom has no <parent>, so its own <version> is reliably the first
# <version> tag in the file (every subsequent one belongs to a dependency or
# plugin).
pom_version=$(grep -m1 '<version>' pom.xml | sed -E 's/.*<version>([^<]+)<\/version>.*/\1/')
# develop legitimately runs a version ahead of the last release between
# release branches (see CONTRIBUTING.md's Release Process) — strip any
# -SNAPSHOT suffix before comparing so that's not reported as drift.
pom_compare="${pom_version%-SNAPSHOT}"

chart_version=$(grep -E '^version:' deploy/helm/railhook/Chart.yaml | awk '{print $2}')
chart_app_version=$(grep -E '^appVersion:' deploy/helm/railhook/Chart.yaml | awk '{print $2}' | tr -d '"')

ui_version=$(grep -m1 '"version"' railhook-ui/package.json | sed -E 's/.*"version": *"([^"]+)".*/\1/')

node_sdk_version=$(grep -m1 '"version"' sdks/node/package.json | sed -E 's/.*"version": *"([^"]+)".*/\1/')
python_sdk_version=$(grep -m1 -E '^version *=' sdks/python/pyproject.toml | sed -E 's/^version *= *"([^"]+)".*/\1/')
php_sdk_version=$(grep -m1 '"version"' sdks/php/composer.json | sed -E 's/.*"version": *"([^"]+)".*/\1/')

echo "pom.xml (reactor):          $pom_version  (compared as $pom_compare)"
echo "Chart.yaml version:         $chart_version"
echo "Chart.yaml appVersion:      $chart_app_version"
echo "railhook-ui:        $ui_version"
echo "sdks/node/package.json:     $node_sdk_version"
echo "sdks/python/pyproject.toml: $python_sdk_version"
echo "sdks/php/composer.json:     $php_sdk_version"

fail=0
check() {
  local label="$1" value="$2"
  if [ "$value" != "$pom_compare" ]; then
    echo "::error::$label ($value) disagrees with pom.xml ($pom_compare)"
    fail=1
  fi
}

# The SDKs carry the version in code too — each client builds its User-Agent
# from it, and Python exposes it as __version__. These were unmanaged until
# they had drifted two minor versions behind the manifests, which is exactly
# the class of drift this script exists to catch.
node_sdk_const=$(sed -nE "s/^const SDK_VERSION = '(.*)';/\1/p" sdks/node/src/client.ts)
python_sdk_const=$(sed -nE 's/^SDK_VERSION = "(.*)"/\1/p' sdks/python/railhook/client.py)
python_dunder=$(sed -nE 's/^__version__ = "(.*)"/\1/p' sdks/python/railhook/__init__.py)
php_sdk_const=$(sed -nE "s/.*private const SDK_VERSION = '(.*)';/\1/p" sdks/php/src/Railhook.php)

check "Chart.yaml version" "$chart_version"
check "Chart.yaml appVersion" "$chart_app_version"
check "railhook-ui/package.json" "$ui_version"
check "sdks/node/package.json" "$node_sdk_version"
check "sdks/python/pyproject.toml" "$python_sdk_version"
check "sdks/php/composer.json" "$php_sdk_version"
check "sdks/node/src/client.ts SDK_VERSION" "$node_sdk_const"
check "sdks/python/railhook/client.py SDK_VERSION" "$python_sdk_const"
check "sdks/python/railhook/__init__.py __version__" "$python_dunder"
check "sdks/php/src/Railhook.php SDK_VERSION" "$php_sdk_const"

# If HEAD is exactly on a release tag (vX.Y.Z), that tag must match too —
# this is what would have caught v2.2.0/v2.2.1 being tagged while every pom
# still said 1.0.0-SNAPSHOT.
if tag=$(git describe --tags --exact-match 2>/dev/null); then
  tag_version="${tag#v}"
  echo "git tag (exact match on HEAD): $tag -> $tag_version"
  check "git tag $tag" "$tag_version"
fi

if [ "$fail" -ne 0 ]; then
  echo ""
  echo "Version drift detected. Realign every file in one step with:"
  echo "  scripts/set-version.sh <version>"
  exit 1
fi

echo ""
echo "All version sources agree on $pom_compare"
