#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
manifest="${1:?usage: validate-portal-candidate.sh RELEASE_MANIFEST}"
output="$repo_root/build/release-gates/candidate/portal-dry-run.properties"

fail() { echo "KLD-PUBLICATION-001 $*" >&2; exit 1; }
[[ -f "$manifest" && -f "$manifest.asc" ]] || fail "signed release manifest is required"
gpg --verify "$manifest.asc" "$manifest" >/dev/null 2>&1 || fail "release manifest signature is invalid"
[[ -n "${GRADLE_PUBLISH_KEY:-}" && -n "${GRADLE_PUBLISH_SECRET:-}" ]] || \
  fail "Portal validation credentials are required"
candidate="$(awk -F= '$1=="asset.0.sha256" {print $2}' "$manifest")"
version="$(awk -F= '$1=="version" {print $2}' "$manifest")"
website="$(awk -F= '$1=="source.website" {print $2}' "$manifest")"
vcs_url="$(awk -F= '$1=="source.vcsUrl" {print $2}' "$manifest")"
[[ -n "$candidate" ]] || fail "candidate digest is absent"
[[ -n "$version" ]] || fail "candidate version is absent"
[[ -n "$website" && -n "$vcs_url" ]] || fail "authoritative source URLs are absent"

before="$(shasum -a 256 "$repo_root/kaleido-gradle-plugin/build/libs/"*.jar)"
cd "$repo_root"
./gradlew :kaleido-gradle-plugin:validatePlugins \
  :kaleido-gradle-plugin:publishPlugins -PkaleidoVersion="$version" \
  -PkaleidoWebsite="$website" -PkaleidoVcsUrl="$vcs_url" --validate-only
after="$(shasum -a 256 "$repo_root/kaleido-gradle-plugin/build/libs/"*.jar)"
[[ "$before" == "$after" ]] || fail "Portal validation changed approved artifact bytes"

mkdir -p "$(dirname "$output")"
cat > "$output" <<EOF
schema=KaleidoPortalDryRun.v1
candidate.sha256=$candidate
validatePlugins=PASS
publishPlugins.validateOnly=PASS
candidateBytesUnchanged=true
verdict=PASS
EOF
echo "$output"
