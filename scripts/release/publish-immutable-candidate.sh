#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
dossier="${1:?usage: publish-immutable-candidate.sh DOSSIER RELEASE_MANIFEST VERSION SIGNED_TAG}"
manifest="${2:?usage: publish-immutable-candidate.sh DOSSIER RELEASE_MANIFEST VERSION SIGNED_TAG}"
version="${3:?usage: publish-immutable-candidate.sh DOSSIER RELEASE_MANIFEST VERSION SIGNED_TAG}"
signed_tag="${4:?usage: publish-immutable-candidate.sh DOSSIER RELEASE_MANIFEST VERSION SIGNED_TAG}"

fail() { echo "KLD-PUBLICATION-001 $*" >&2; exit 1; }
[[ "${KALEIDO_RELEASE_PUBLISH:-}" == "publish-exact-approved-candidate" ]] || \
  fail "explicit protected publication authorization is absent"
[[ -n "${GRADLE_PUBLISH_KEY:-}" && -n "${GRADLE_PUBLISH_SECRET:-}" ]] || fail "Portal credentials are absent"
command -v gh >/dev/null || fail "GitHub CLI is required"
[[ -f "$dossier" && "$(awk -F= '$1=="verdict" {print $2}' "$dossier")" == "PASS" ]] || fail "passing dossier is required"
[[ -f "$manifest" && -f "$manifest.asc" ]] || fail "signed manifest is required"
gpg --verify "$manifest.asc" "$manifest" >/dev/null 2>&1 || fail "manifest signature is invalid"
[[ "$version" != *dev* && "$version" != *SNAPSHOT* ]] || fail "development versions cannot be publicly promoted"
[[ "$(git -C "$repo_root" rev-list -n 1 "$signed_tag")" == "$(git -C "$repo_root" rev-parse HEAD)" ]] || fail "signed tag is not HEAD"
git -C "$repo_root" tag -v "$signed_tag" >/dev/null 2>&1 || fail "source tag signature is invalid"

candidate="$(awk -F= '$1=="asset.0.sha256" {print $2}' "$manifest")"
website="$(awk -F= '$1=="source.website" {print $2}' "$manifest")"
vcs_url="$(awk -F= '$1=="source.vcsUrl" {print $2}' "$manifest")"
[[ "$(awk -F= '$1=="candidate.sha256" {print $2}' "$dossier")" == "$candidate" ]] || fail "dossier candidate mismatch"
plugin_jar="$repo_root/kaleido-gradle-plugin/build/libs/kaleido-gradle-plugin-$version.jar"
[[ "$(shasum -a 256 "$plugin_jar" | awk '{print $1}')" == "$candidate" ]] || fail "plugin bytes differ from approval"
before="$(shasum -a 256 "$repo_root/kaleido-gradle-plugin/build/libs/"*.jar)"

cd "$repo_root"
./gradlew :kaleido-gradle-plugin:publishPlugins -PkaleidoVersion="$version" \
  -PkaleidoWebsite="$website" -PkaleidoVcsUrl="$vcs_url"
after="$(shasum -a 256 "$repo_root/kaleido-gradle-plugin/build/libs/"*.jar)"
[[ "$before" == "$after" ]] || fail "publication rebuilt or changed approved bytes"

asset_dir="$(dirname "$manifest")"
gh release create "$signed_tag" "$asset_dir"/* \
  --verify-tag --title "Kaleido $version" \
  --notes-file "$repo_root/release/RELEASE_NOTES_$version.md"
echo "Portal and signed-tag GitHub publication dispatched for immutable candidate $candidate"
