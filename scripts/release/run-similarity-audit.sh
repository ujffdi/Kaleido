#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
checkout_root="$repo_root/build/release-gates/upstreams"
output="$repo_root/build/release-gates/supply-chain/content-similarity-audit.properties"
mkdir -p "$checkout_root"

checkout() {
  local name="$1"
  local url="$2"
  local revision="$3"
  local destination="$checkout_root/$name"
  if [[ ! -d "$destination/.git" ]]; then
    git clone --filter=blob:none --no-checkout "$url" "$destination"
  fi
  git -C "$destination" fetch --depth=1 origin "$revision"
  git -C "$destination" checkout --detach --force FETCH_HEAD
  [[ "$(git -C "$destination" rev-parse HEAD)" == "$revision" ]] || {
    echo "KLD-PROVENANCE-001 upstream revision mismatch: $name" >&2
    exit 1
  }
}

checkout android-junk-code https://github.com/qq549631030/AndroidJunkCode.git cfcd9eed0b8d5a938033a9268a20e58e059b3039
checkout xml-class-guard https://github.com/liujingxing/XmlClassGuard.git 198cea9ccd87129d8ffb6ec5f258190a3b3ee8a1

"$repo_root/gradlew" -q :release-gates:classes
classpath="$repo_root/release-gates/build/classes/java/main:$repo_root/release-gates/build/resources/main"
java -cp "$classpath" com.tongsr.kaleido.release.SimilarityAuditCli \
  --output "$output" \
  --candidate "$repo_root/kaleido-gradle-plugin/src/main" \
  --candidate "$repo_root/release/fixtures" \
  --candidate "$repo_root/samples" \
  --upstream "$checkout_root/android-junk-code" \
  --upstream "$checkout_root/xml-class-guard"

echo "$output"
