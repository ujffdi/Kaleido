#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
manifest="${1:?usage: assemble-release-dossier.sh RELEASE_MANIFEST OWNER_APPROVAL SECURITY_APPROVAL}"
owner_approval="${2:?usage: assemble-release-dossier.sh RELEASE_MANIFEST OWNER_APPROVAL SECURITY_APPROVAL}"
security_approval="${3:?usage: assemble-release-dossier.sh RELEASE_MANIFEST OWNER_APPROVAL SECURITY_APPROVAL}"

fail() { echo "KLD-PUBLICATION-001 $*" >&2; exit 1; }
command -v gpg >/dev/null || fail "gpg is required"

verify_signature() {
  local signed_file="$1"
  local signature_file="$2"
  local status fingerprint
  [[ -f "$signed_file" && -f "$signature_file" ]] || fail "signed file or detached signature is missing"
  status="$(gpg --batch --status-fd 1 --verify "$signature_file" "$signed_file" 2>/dev/null)" || \
    fail "detached signature is invalid: $signed_file"
  fingerprint="$(printf '%s\n' "$status" | awk '$1=="[GNUPG:]" && $2=="VALIDSIG" {print toupper($3); exit}')"
  [[ "$fingerprint" =~ ^[0-9A-F]{40}$|^[0-9A-F]{64}$ ]] || \
    fail "verified signature has no valid signing fingerprint: $signed_file"
  printf '%s\n' "$fingerprint"
}

manifest_signature="$manifest.asc"
verify_signature "$manifest" "$manifest_signature" >/dev/null
version="$(awk -F= '$1=="version" {print $2}' "$manifest")"
candidate="$(awk -F= '$1=="asset.0.sha256" {print $2}' "$manifest")"
[[ -n "$version" && -n "$candidate" ]] || fail "candidate identity is incomplete"

owner_signature="$owner_approval.asc"
security_signature="$security_approval.asc"
owner_fingerprint="$(verify_signature "$owner_approval" "$owner_signature")"
security_fingerprint="$(verify_signature "$security_approval" "$security_signature")"
[[ "$owner_fingerprint" != "$security_fingerprint" ]] || \
  fail "approval signing keys must be independent"
[[ "$(awk -F= '$1=="signer.fingerprint" {print toupper($2)}' "$owner_approval")" == "$owner_fingerprint" ]] || \
  fail "release-owner approval fingerprint does not match its signature"
[[ "$(awk -F= '$1=="signer.fingerprint" {print toupper($2)}' "$security_approval")" == "$security_fingerprint" ]] || \
  fail "provenance/security approval fingerprint does not match its signature"

declare -a records=(
  "cache|$repo_root/build/release-gates/candidate/cache-gates.properties"
  "matrix.A3|$repo_root/build/release-gates/compatibility/A3/matrix-record.properties"
  "matrix.A4|$repo_root/build/release-gates/compatibility/A4/matrix-record.properties"
  "runtime.A3|$repo_root/build/release-gates/compatibility/A3/runtime-record.properties"
  "runtime.A4|$repo_root/build/release-gates/compatibility/A4/runtime-record.properties"
  "performance|$repo_root/build/release-gates/performance/performance-gate.properties"
  "provenance|$repo_root/build/release-gates/supply-chain/supply-chain-manifest.properties"
  "documentation|$repo_root/build/release-gates/documentation/documentation-validation.properties"
  "portal-dry-run|$repo_root/build/release-gates/candidate/portal-dry-run.properties"
)
for encoded in "${records[@]}"; do
  record="${encoded#*|}"
  [[ -f "$record" ]] || fail "mandatory record is missing: ${encoded%%|*}"
done

cd "$repo_root"
./gradlew :release-gates:installDist -PkaleidoVersion="$version"
dossier="$repo_root/build/release-gates/candidate/$version/pre-publication-dossier.properties"
declare -a arguments=(
  --output "$dossier"
  --manifest "$manifest"
  --manifest-signature "$manifest_signature"
  --manifest-signature-verified true
)
for encoded in "${records[@]}"; do arguments+=(--record "$encoded"); done
arguments+=(
  --approval "$owner_approval" --approval-signature "$owner_signature"
  --approval "$security_approval" --approval-signature "$security_signature"
)
java -cp "$repo_root/release-gates/build/install/release-gates/lib/*" \
  com.tongsr.kaleido.release.ReleaseDossierCli "${arguments[@]}"
echo "$dossier"
