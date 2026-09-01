#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
pre_dossier="${1:?usage: finalize-public-release.sh PRE_DOSSIER RELEASE_MANIFEST POST_PUBLICATION_RECORD VERSION SIGNED_TAG}"
manifest="${2:?usage: finalize-public-release.sh PRE_DOSSIER RELEASE_MANIFEST POST_PUBLICATION_RECORD VERSION SIGNED_TAG}"
post_record="${3:?usage: finalize-public-release.sh PRE_DOSSIER RELEASE_MANIFEST POST_PUBLICATION_RECORD VERSION SIGNED_TAG}"
version="${4:?usage: finalize-public-release.sh PRE_DOSSIER RELEASE_MANIFEST POST_PUBLICATION_RECORD VERSION SIGNED_TAG}"
signed_tag="${5:?usage: finalize-public-release.sh PRE_DOSSIER RELEASE_MANIFEST POST_PUBLICATION_RECORD VERSION SIGNED_TAG}"
key_id="${KALEIDO_RELEASE_GPG_KEY_ID:-}"

fail() { echo "KLD-PUBLICATION-001 $*" >&2; exit 1; }
[[ "${KALEIDO_RELEASE_FINALIZE:-}" == "finalize-verified-public-release" ]] || \
  fail "explicit protected finalization authorization is absent"
command -v gpg >/dev/null || fail "gpg is required"
command -v gh >/dev/null || fail "GitHub CLI is required"
[[ -n "$key_id" ]] || fail "KALEIDO_RELEASE_GPG_KEY_ID is required"
[[ -f "$manifest" && -f "$manifest.asc" ]] || fail "signed manifest is required"
gpg --batch --verify "$manifest.asc" "$manifest" >/dev/null 2>&1 || fail "manifest signature is invalid"
[[ -z "$(git -C "$repo_root" status --porcelain)" ]] || fail "source work tree is not clean"
[[ "$(git -C "$repo_root" rev-list -n 1 "$signed_tag")" == "$(git -C "$repo_root" rev-parse HEAD)" ]] || \
  fail "signed tag is not HEAD"
git -C "$repo_root" tag -v "$signed_tag" >/dev/null 2>&1 || fail "source tag signature is invalid"
[[ "$(gh release view "$signed_tag" --json isDraft --jq .isDraft)" == "true" ]] || \
  fail "matching GitHub release is not a draft"

cd "$repo_root"
./gradlew :release-gates:installDist -PkaleidoVersion="$version"
asset_dir="$(dirname "$manifest")"
final_dossier="$asset_dir/release-dossier.properties"
java -cp "$repo_root/release-gates/build/install/release-gates/lib/*" \
  com.tongsr.kaleido.release.FinalReleaseDossierCli \
  --output "$final_dossier" \
  --pre-publication-dossier "$pre_dossier" \
  --post-publication-record "$post_record" \
  --manifest "$manifest" --version "$version" --signed-tag "$signed_tag"
gpg --batch --local-user "$key_id" --armor --detach-sign \
  --output "$final_dossier.asc" "$final_dossier"
gpg --batch --verify "$final_dossier.asc" "$final_dossier" >/dev/null 2>&1 || \
  fail "final Dossier signature verification failed"

gh release upload "$signed_tag" "$final_dossier" "$final_dossier.asc" "$post_record" --clobber
gh release edit "$signed_tag" --draft=false --latest --verify-tag
echo "Final public release closed for io.github.ujffdi.kaleido:$version"
