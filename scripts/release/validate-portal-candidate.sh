#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
manifest="${1:?usage: validate-portal-candidate.sh RELEASE_MANIFEST}"
output="$repo_root/build/release-gates/candidate/portal-dry-run.properties"

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

[[ -f "$manifest" && -f "$manifest.asc" ]] || fail "signed release manifest is required"
gpg --verify "$manifest.asc" "$manifest" >/dev/null 2>&1 || fail "release manifest signature is invalid"
portal_credentials_available || fail "Portal validation credentials are required"
candidate="$(awk -F= '$1=="asset.0.sha256" {print $2}' "$manifest")"
version="$(awk -F= '$1=="version" {print $2}' "$manifest")"
website="$(awk -F= '$1=="source.website" {print $2}' "$manifest")"
vcs_url="$(awk -F= '$1=="source.vcsUrl" {print $2}' "$manifest")"
[[ -n "$candidate" ]] || fail "candidate digest is absent"
[[ -n "$version" ]] || fail "candidate version is absent"
[[ -n "$website" && -n "$vcs_url" ]] || fail "authoritative source URLs are absent"

verify_manifest_assets
cd "$repo_root"
./gradlew :kaleido-gradle-plugin:validatePlugins \
  :kaleido-gradle-plugin:publishPlugins -PkaleidoVersion="$version" \
  -PkaleidoWebsite="$website" -PkaleidoVcsUrl="$vcs_url" --validate-only
verify_manifest_assets

mkdir -p "$(dirname "$output")"
cat > "$output" <<EOF
schema=KaleidoPortalDryRun.v1
candidate.sha256=$candidate
validatePlugins=PASS
publishPlugins.validateOnly=PASS
allManifestAssetsUnchanged=true
verdict=PASS
EOF
echo "$output"
