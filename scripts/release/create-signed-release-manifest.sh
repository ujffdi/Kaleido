#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
version="${1:?usage: create-signed-release-manifest.sh VERSION SIGNED_TAG}"
signed_tag="${2:?usage: create-signed-release-manifest.sh VERSION SIGNED_TAG}"
output_dir="$repo_root/build/release-gates/candidate/$version"
key_id="${KALEIDO_RELEASE_GPG_KEY_ID:-}"
website="${KALEIDO_RELEASE_WEBSITE:-}"
vcs_url="${KALEIDO_RELEASE_VCS_URL:-}"

fail() {
  echo "KLD-PROVENANCE-001 $*" >&2
  exit 1
}

git -C "$repo_root" rev-parse --is-inside-work-tree >/dev/null 2>&1 || fail "source is not a Git work tree"
[[ -z "$(git -C "$repo_root" status --porcelain)" ]] || fail "source work tree is not clean"
revision="$(git -C "$repo_root" rev-parse HEAD)"
[[ "$(git -C "$repo_root" rev-list -n 1 "$signed_tag")" == "$revision" ]] || fail "tag does not point to candidate source"
git -C "$repo_root" tag -v "$signed_tag" >/dev/null 2>&1 || fail "source tag signature is invalid"
[[ -n "$key_id" ]] || fail "KALEIDO_RELEASE_GPG_KEY_ID is required"
[[ "$website" == https://* && "$vcs_url" == https://* ]] || fail "authoritative website and VCS URLs are required"

mkdir -p "$output_dir"
source_archive="$output_dir/kaleido-$version-source.tar.gz"
git -C "$repo_root" archive --format=tar.gz --prefix="kaleido-$version/" -o "$source_archive" "$signed_tag"

declare -a assets=(
  "$repo_root/kaleido-gradle-plugin/build/libs/kaleido-gradle-plugin-$version.jar"
  "$repo_root/kaleido-gradle-plugin/build/libs/kaleido-gradle-plugin-$version-sources.jar"
  "$repo_root/kaleido-gradle-plugin/build/functional-test-repository/com/tongsr/kaleido/com.tongsr.kaleido.gradle.plugin/$version/com.tongsr.kaleido.gradle.plugin-$version.pom"
  "$repo_root/build/release-gates/supply-chain/kaleido-$version.cdx.json"
  "$repo_root/build/release-gates/supply-chain/source-dependency-inventory.properties"
  "$repo_root/build/release-gates/supply-chain/content-similarity-audit.properties"
  "$repo_root/LICENSE"
  "$repo_root/NOTICE"
  "$repo_root/THIRD_PARTY_NOTICES.md"
  "$repo_root/release/provenance/upstream-components.properties"
  "$repo_root/gradle/verification-metadata.xml"
  "$source_archive"
)
for asset in "${assets[@]}"; do [[ -f "$asset" ]] || fail "required release asset is missing: $asset"; done

manifest="$output_dir/release-manifest.properties"
{
  echo "schema=KaleidoImmutableReleaseManifest.v1"
  echo "version=$version"
  echo "source.tag=$signed_tag"
  echo "source.revision=$revision"
  echo "source.website=$website"
  echo "source.vcsUrl=$vcs_url"
  echo "claims.slsa=false"
  echo "claims.upstreamPermission=false"
  index=0
  for asset in "${assets[@]}"; do
    relative="${asset#"$repo_root"/}"
    digest="$(sha256sum "$asset" | awk '{print $1}')"
    echo "asset.$index.path=$relative"
    echo "asset.$index.sha256=$digest"
    index=$((index + 1))
  done
  docs_digest="$(cd "$repo_root" && git ls-files -z docs README.md CHANGELOG.md SECURITY.md CONTRIBUTING.md 2>/dev/null \
    | sort -z | xargs -0 sha256sum | sha256sum | awk '{print $1}')"
  mappings_schema_digest="$(cd "$repo_root" && git ls-files -z \
    'kaleido-gradle-plugin/src/main/**' 'docs/schemas/**' 2>/dev/null \
    | sort -z | xargs -0 sha256sum | sha256sum | awk '{print $1}')"
  echo "documentation.tree.sha256=$docs_digest"
  echo "mappingsSchema.tree.sha256=$mappings_schema_digest"
  echo "verdict=PASS"
} > "$manifest"

gpg --batch --local-user "$key_id" --armor --detach-sign --output "$manifest.asc" "$manifest"
gpg --verify "$manifest.asc" "$manifest" >/dev/null 2>&1 || fail "release manifest signature verification failed"
echo "$manifest"
