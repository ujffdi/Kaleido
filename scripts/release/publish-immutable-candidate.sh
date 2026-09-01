#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
dossier="${1:?usage: publish-immutable-candidate.sh DOSSIER RELEASE_MANIFEST VERSION SIGNED_TAG}"
manifest="${2:?usage: publish-immutable-candidate.sh DOSSIER RELEASE_MANIFEST VERSION SIGNED_TAG}"
version="${3:?usage: publish-immutable-candidate.sh DOSSIER RELEASE_MANIFEST VERSION SIGNED_TAG}"
signed_tag="${4:?usage: publish-immutable-candidate.sh DOSSIER RELEASE_MANIFEST VERSION SIGNED_TAG}"

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

verify_manifest_assets() {
  local key path index expected actual count=0
  while IFS='=' read -r key path; do
    [[ "$key" =~ ^asset\.([0-9]+)\.path$ ]] || continue
    index="${BASH_REMATCH[1]}"
    [[ "$path" != /* && "$path" != ../* && "$path" != */../* && "$path" != */.. ]] || \
      fail "manifest asset path escapes the source tree"
    [[ -f "$repo_root/$path" ]] || fail "manifest asset is missing: $path"
    expected="$(awk -F= -v wanted="asset.$index.sha256" '$1==wanted {print $2}' "$manifest")"
    actual="$(sha256sum "$repo_root/$path" | awk '{print $1}')"
    [[ -n "$expected" && "$actual" == "$expected" ]] || fail "manifest asset digest mismatch: $path"
    count=$((count + 1))
  done < "$manifest"
  [[ "$count" -gt 0 ]] || fail "release manifest has no assets"
}

[[ "${KALEIDO_RELEASE_PUBLISH:-}" == "publish-exact-approved-candidate" ]] || \
  fail "explicit protected publication authorization is absent"
portal_credentials_available || fail "Portal credentials are absent"
command -v gh >/dev/null || fail "GitHub CLI is required"
[[ -f "$dossier" && "$(awk -F= '$1=="verdict" {print $2}' "$dossier")" == "PASS" ]] || fail "passing dossier is required"
[[ -f "$manifest" && -f "$manifest.asc" ]] || fail "signed manifest is required"
gpg --verify "$manifest.asc" "$manifest" >/dev/null 2>&1 || fail "manifest signature is invalid"
[[ "$version" != *dev* && "$version" != *SNAPSHOT* ]] || fail "development versions cannot be publicly promoted"
[[ "$(awk -F= '$1=="version" {print $2}' "$manifest")" == "$version" ]] || fail "manifest version mismatch"
[[ -z "$(git -C "$repo_root" status --porcelain)" ]] || fail "source work tree is not clean"
[[ "$(git -C "$repo_root" rev-list -n 1 "$signed_tag")" == "$(git -C "$repo_root" rev-parse HEAD)" ]] || fail "signed tag is not HEAD"
git -C "$repo_root" tag -v "$signed_tag" >/dev/null 2>&1 || fail "source tag signature is invalid"
[[ "$(awk -F= '$1=="source.revision" {print $2}' "$manifest")" == "$(git -C "$repo_root" rev-parse HEAD)" ]] || \
  fail "manifest source revision is not HEAD"

candidate="$(awk -F= '$1=="asset.0.sha256" {print $2}' "$manifest")"
website="$(awk -F= '$1=="source.website" {print $2}' "$manifest")"
vcs_url="$(awk -F= '$1=="source.vcsUrl" {print $2}' "$manifest")"
[[ "$(awk -F= '$1=="candidate.sha256" {print $2}' "$dossier")" == "$candidate" ]] || fail "dossier candidate mismatch"
plugin_jar="$repo_root/kaleido-gradle-plugin/build/libs/kaleido-gradle-plugin-$version.jar"
[[ "$(shasum -a 256 "$plugin_jar" | awk '{print $1}')" == "$candidate" ]] || fail "plugin bytes differ from approval"
verify_manifest_assets

cd "$repo_root"
./gradlew :kaleido-gradle-plugin:validatePlugins \
  :kaleido-gradle-plugin:publishPlugins -PkaleidoVersion="$version" \
  -PkaleidoWebsite="$website" -PkaleidoVcsUrl="$vcs_url" --validate-only
verify_manifest_assets
asset_dir="$(dirname "$manifest")"
declare -a release_assets=(
  "$asset_dir"/*
  "$repo_root/kaleido-gradle-plugin/build/libs/kaleido-gradle-plugin-$version.jar"
  "$repo_root/kaleido-gradle-plugin/build/libs/kaleido-gradle-plugin-$version-sources.jar"
  "$repo_root/kaleido-gradle-plugin/build/libs/kaleido-gradle-plugin-$version-javadoc.jar"
  "$repo_root/build/release-gates/supply-chain/kaleido-$version.cdx.json"
)
gh release create "$signed_tag" "${release_assets[@]}" --draft \
  --verify-tag --title "Kaleido $version" \
  --notes-file "$repo_root/release/RELEASE_NOTES_$version.md"
./gradlew :kaleido-gradle-plugin:publishPlugins -PkaleidoVersion="$version" \
  -PkaleidoWebsite="$website" -PkaleidoVcsUrl="$vcs_url"
verify_manifest_assets
echo "Portal publication dispatched and signed-tag GitHub draft created for immutable candidate $candidate"
