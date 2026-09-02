#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
version="${1:?usage: publish-portal-release.sh VERSION}"

fail() { echo "KLD-PUBLICATION-001 $*" >&2; exit 1; }
portal_credentials_available() {
  if [[ -n "${GRADLE_PUBLISH_KEY:-}" && -n "${GRADLE_PUBLISH_SECRET:-}" ]]; then
    return 0
  fi
  local gradle_user_home="${GRADLE_USER_HOME:-${HOME:?}/.gradle}"
  local properties="$gradle_user_home/gradle.properties"
  [[ -f "$properties" ]] &&
    grep -Eq '^gradle[.]publish[.]key=.+$' "$properties" &&
    grep -Eq '^gradle[.]publish[.]secret=.+$' "$properties"
}

[[ "$version" != *dev* && "$version" != *SNAPSHOT* ]] || fail "a final version is required"
[[ "${KALEIDO_RELEASE_PUBLISH:-}" == "publish-portal-release" ]] || \
  fail "explicit publication authorization is absent"
[[ -z "$(git -C "$repo_root" status --porcelain)" ]] || fail "source work tree is not clean"
[[ "$(git -C "$repo_root" rev-parse HEAD)" == "$(git -C "$repo_root" rev-parse origin/main)" ]] || \
  fail "HEAD is not the published origin/main revision"
portal_credentials_available || fail "Portal credentials are absent"

cd "$repo_root"
./gradlew :kaleido-gradle-plugin:test -PkaleidoVersion="$version"
bash scripts/release/validate-public-docs.sh "$version"
./gradlew :kaleido-gradle-plugin:publishPlugins -PkaleidoVersion="$version" \
  -PkaleidoWebsite=https://github.com/ujffdi/Kaleido \
  -PkaleidoVcsUrl=https://github.com/ujffdi/Kaleido.git --validate-only
./gradlew :kaleido-gradle-plugin:publishPlugins -PkaleidoVersion="$version" \
  -PkaleidoWebsite=https://github.com/ujffdi/Kaleido \
  -PkaleidoVcsUrl=https://github.com/ujffdi/Kaleido.git
echo "Gradle Plugin Portal publication submitted for io.github.ujffdi.kaleido:$version"
