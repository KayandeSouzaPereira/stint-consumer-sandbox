#!/usr/bin/env bash
# SDD S.5, RF1: installs Stint at the pinned SHA into the local Maven repository under a version of its own, so that
# installing Stint's working tree for another phase (0.1.0-SNAPSHOT) can never change what the sandbox builds and runs.
#   bash infra/install-stint.sh [work-dir]      work-dir defaults to ./stint-pinned (a clean clone, kept for reruns)
# Needs git, JDK 25 and Maven 3.9+ on the PATH. Idempotent: it reinstalls the same artifacts.
set -euo pipefail

SHA=05fb70e
VERSION=0.1.0-3b-05fb70e
DIR="${1:-stint-pinned}"

if [ ! -d "$DIR/.git" ]; then git clone --no-checkout https://github.com/stintflow/stint.git "$DIR"; fi
cd "$DIR"
git checkout --force "$SHA"
git clean -fdx -e target >/dev/null
# the version Stint's poms declare, replaced by the pinned one in every pom of this clean checkout
grep -rl --include=pom.xml "0.1.0-SNAPSHOT" . | xargs sed -i "s/0\.1\.0-SNAPSHOT/$VERSION/g"
mvn install -DskipTests
echo "Stint $SHA installed as $VERSION"
