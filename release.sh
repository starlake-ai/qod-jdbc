#!/usr/bin/env bash
# Release qod-jdbc from this checkout: build and test, publish to Maven Central
# (ai.starlake.qod:qod-jdbc, signed), tag v<version>, push the tag, and publish a GitHub release
# holding the same jar and its SHA-256.
#
# Maven Central reads its credentials from SONATYPE_USERNAME / SONATYPE_PASSWORD (a Central Portal
# user token), and signs with the key pom.xml's release profile names, without a passphrase prompt.
#
# The version is pom.xml's, which is the embedded Arrow Flight SQL JDBC driver's (19.0.0 embeds
# Arrow's 19.0.0). Bump it there, commit and push, then run this.
#
#   ./release.sh            release
#   ./release.sh --dry-run  every check and the build, nothing tagged, pushed or published
set -euo pipefail
cd "$(dirname "$0")"

dry=0
case "${1:-}" in
  "") ;;
  --dry-run) dry=1 ;;
  *) echo "usage: $0 [--dry-run]" >&2; exit 2 ;;
esac

# A refusal stops a release; a dry run reports it and goes on, so one run shows every problem.
refused=0
refuse() {
  if [ "$dry" = 1 ]; then
    echo "dry run: a release would refuse: $*" >&2
    refused=1
  else
    echo "release refused: $*" >&2
    exit 1
  fi
}

for tool in git mvn gh; do
  command -v "$tool" >/dev/null || { echo "release refused: $tool is not on PATH" >&2; exit 1; }
done

version=$(mvn -q -DforceStdout help:evaluate -Dexpression=project.version)
tag="v$version"
jar="target/qod-jdbc-$version.jar"
echo "qod-jdbc $version (tag $tag)"

case "$version" in
  *-SNAPSHOT) refuse "pom.xml's version $version is a snapshot" ;;
esac

branch=$(git branch --show-current)
[ "$branch" = main ] || refuse "on branch '$branch', not main"
[ -z "$(git status --porcelain)" ] || refuse "the working tree has changes (git status)"

git fetch -q origin main --tags
[ "$(git rev-parse HEAD)" = "$(git rev-parse origin/main)" ] ||
  refuse "HEAD is not origin/main: pull or push first"
if git rev-parse -q --verify "refs/tags/$tag" >/dev/null ||
  [ -n "$(git ls-remote --tags origin "refs/tags/$tag")" ]; then
  refuse "tag $tag already exists: bump the version in pom.xml"
fi
gh auth status >/dev/null 2>&1 || refuse "gh is not signed in (gh auth login)"
[ -n "${SONATYPE_USERNAME:-}" ] && [ -n "${SONATYPE_PASSWORD:-}" ] ||
  refuse "SONATYPE_USERNAME and SONATYPE_PASSWORD must hold a Maven Central Portal user token"

# Settings naming the Central server; they hold references to the environment, never a secret.
settings=$(mktemp)
trap 'rm -f "$settings"' EXIT
cat >"$settings" <<'SETTINGS'
<settings>
  <servers>
    <server>
      <id>central</id>
      <username>${env.SONATYPE_USERNAME}</username>
      <password>${env.SONATYPE_PASSWORD}</password>
    </server>
  </servers>
</settings>
SETTINGS

# The release build, signed; a dry run stops before the upload (skipUpload) and keeps the bundle.
if [ "$dry" = 1 ]; then
  mvn -B -q -s "$settings" -Prelease clean deploy -DskipUpload=true
else
  mvn -B -q -s "$settings" -Prelease clean verify
fi
[ -f "$jar" ] || { echo "release refused: the build made no $jar" >&2; exit 1; }
if command -v sha256sum >/dev/null; then
  (cd target && sha256sum "$(basename "$jar")" >"$(basename "$jar").sha256")
else
  (cd target && shasum -a 256 "$(basename "$jar")" >"$(basename "$jar").sha256")
fi
echo "built $jar ($(du -h "$jar" | awk '{print $1}')), tests passed"
cat "$jar.sha256"

if [ "$dry" = 1 ]; then
  echo "Maven Central bundle (not uploaded): target/central-publishing/central-bundle.zip"
  if [ "$refused" = 1 ]; then
    echo "dry run done: a release would have stopped at the refusals above" >&2
    exit 1
  fi
  echo "dry run done: a release would tag $tag at $(git rev-parse --short HEAD), push it and publish $jar"
  exit 0
fi

# Maven Central first: a version published there cannot change, so nothing is tagged before it
# succeeds. The upload reuses the build above (the jar the GitHub release then carries).
mvn -B -q -s "$settings" -Prelease deploy
echo "published ai.starlake.qod:qod-jdbc:$version to Maven Central"

git tag -a "$tag" -m "qod-jdbc $version"
git push -q origin "$tag"
gh release create "$tag" "$jar" "$jar.sha256" \
  --verify-tag \
  --title "qod-jdbc $version" \
  --notes "The QoD JDBC driver $version: the Apache Arrow Flight SQL JDBC driver $version, unchanged and bundled, plus \`authenticator=externalbrowser\`, a browser sign-in brokered by the QoD manager (QoD 0.13.0 or later).

Download \`qod-jdbc-$version.jar\`, or take it from Maven Central as \`ai.starlake.qod:qod-jdbc:$version\`, and use it in place of the Arrow driver jar: driver class \`ai.starlake.qod.jdbc.QodDriver\`, URL \`jdbc:qod://<host>:31338?tenant=..&pool=..&authenticator=externalbrowser\`. Java 11 or later. See the README for every property."
echo "released $tag: $(gh release view "$tag" --json url --jq .url)"
