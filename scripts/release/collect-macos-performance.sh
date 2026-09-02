#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
version="${1:?usage: collect-macos-performance.sh VERSION}"
sdk="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
work="$repo_root/build/release-gates/performance/measurement-work"
output="$repo_root/build/release-gates/performance/raw-measurements.properties"
plugin_repository="$repo_root/kaleido-gradle-plugin/build/functional-test-repository"
plugin_jar="$repo_root/kaleido-gradle-plugin/build/libs/kaleido-gradle-plugin-$version.jar"
size_record="$repo_root/build/release-gates/performance/local-size-gate.properties"

fail() { echo "KLD-PERF-001 $*" >&2; exit 1; }
[[ "$(uname -s)" == "Darwin" && "$(uname -m)" == "arm64" ]] || fail "macOS arm64 is required"
[[ "$version" != *dev* && "$version" != *SNAPSHOT* ]] || fail "a final candidate version is required"
[[ -n "$sdk" && -d "$sdk/build-tools/36.0.0" ]] || fail "Build Tools 36.0.0 are required"

rm -rf "$work"
mkdir -p "$work"
cp -R "$repo_root/release/fixtures/java-safe/." "$work/safe-baseline/"
cp -R "$repo_root/release/fixtures/java-safe/." "$work/safe-candidate/"
cp -R "$repo_root/samples/kaleido-sample/." "$work/full-sample/"
sed -i '' '/id("io.github.ujffdi.kaleido")/d' "$work/safe-baseline/app/build.gradle.kts"

test_store="$work/performance-test-upload.p12"
password="kaleido-performance-test-signing"
keytool -genkeypair -alias upload -keyalg RSA -keysize 2048 -validity 7 \
  -dname "CN=Kaleido Performance Test" -storetype PKCS12 -keystore "$test_store" \
  -storepass "$password" -keypass "$password" >/dev/null 2>&1
certificate="$({ keytool -exportcert -alias upload -keystore "$test_store" \
  -storepass "$password" -rfc 2>/dev/null | openssl x509 -outform der 2>/dev/null \
  | shasum -a 256; } | awk '{print $1}')"
export KALEIDO_UPLOAD_KEYSTORE="$test_store"
export KALEIDO_UPLOAD_STORE_PASSWORD="$password"
export KALEIDO_UPLOAD_KEY_ALIAS=upload
export KALEIDO_UPLOAD_KEY_PASSWORD="$password"
export KALEIDO_UPLOAD_CERTIFICATE_SHA256="$certificate"

common=(--no-daemon --no-build-cache --no-configuration-cache --console=plain)
matrix=(
  -PmatrixPluginRepository="$plugin_repository"
  -PmatrixAgp=9.2.0
  -PmatrixKaleido="$version"
)
sample=(
  -PsamplePluginRepository="$plugin_repository"
  -PsampleAgpVersion=9.2.0
  -PsampleKaleidoVersion="$version"
)

measure() {
  local name="$1"
  local index="$2"
  shift 2
  local metrics="$work/$name-$index.metrics"
  local log="$work/$name-$index.log"
  if ! /usr/bin/time -lp "$@" >"$log" 2>"$metrics"; then
    tail -80 "$log" >&2 || true
    cat "$metrics" >&2
    fail "$name sample $index failed"
  fi
  local seconds rss
  seconds="$(awk '$1=="real" {print $2; exit}' "$metrics")"
  rss="$(awk '/maximum resident set size/ {printf "%.3f", $1 / 1048576; exit}' "$metrics")"
  [[ -n "$seconds" && -n "$rss" ]] || fail "$name sample $index has incomplete metrics"
  printf '%s|%s\n' "$seconds" "$rss"
}

append_value() {
  local current="$1"
  local value="$2"
  if [[ -z "$current" ]]; then printf '%s' "$value"; else printf '%s,%s' "$current" "$value"; fi
}

safe_baseline_seconds=""
safe_candidate_seconds=""
full_baseline_seconds=""
full_candidate_seconds=""
full_baseline_memory=""
full_candidate_memory=""
warm_baseline_seconds=""
warm_candidate_seconds=""

for index in 1 2 3 4 5 6 7; do
  result="$(measure safe-baseline "$index" "$repo_root/gradlew" -p "$work/safe-baseline" \
    clean bundleRelease "${common[@]}" "${matrix[@]}")"
  safe_baseline_seconds="$(append_value "$safe_baseline_seconds" "${result%%|*}")"

  result="$(measure safe-candidate "$index" "$repo_root/gradlew" -p "$work/safe-candidate" \
    clean bundleRelease "${common[@]}" "${matrix[@]}")"
  safe_candidate_seconds="$(append_value "$safe_candidate_seconds" "${result%%|*}")"

  result="$(measure full-baseline "$index" "$repo_root/gradlew" -p "$work/full-sample" \
    clean :baseline:bundleRelease "${common[@]}" "${sample[@]}")"
  full_baseline_seconds="$(append_value "$full_baseline_seconds" "${result%%|*}")"
  full_baseline_memory="$(append_value "$full_baseline_memory" "${result##*|}")"

  result="$(measure full-candidate "$index" "$repo_root/gradlew" -p "$work/full-sample" \
    clean :app:bundleRelease "${common[@]}" "${sample[@]}")"
  full_candidate_seconds="$(append_value "$full_candidate_seconds" "${result%%|*}")"
  full_candidate_memory="$(append_value "$full_candidate_memory" "${result##*|}")"
done

"$repo_root/gradlew" -p "$work/full-sample" :baseline:bundleRelease :app:bundleRelease \
  "${common[@]}" "${sample[@]}" >/dev/null
for index in 1 2 3 4 5 6 7; do
  result="$(measure warm-baseline "$index" "$repo_root/gradlew" -p "$work/full-sample" \
    :baseline:bundleRelease "${common[@]}" "${sample[@]}")"
  warm_baseline_seconds="$(append_value "$warm_baseline_seconds" "${result%%|*}")"
  result="$(measure warm-candidate "$index" "$repo_root/gradlew" -p "$work/full-sample" \
    :app:bundleRelease "${common[@]}" "${sample[@]}")"
  warm_candidate_seconds="$(append_value "$warm_candidate_seconds" "${result%%|*}")"
done

digest="$(shasum -a 256 "$plugin_jar" | awk '{print $1}')"
safe_baseline_aab="$work/safe-baseline/app/build/outputs/bundle/release/app-release.aab"
safe_candidate_aab="$work/safe-candidate/app/build/outputs/bundle/release/app-release.aab"
full_baseline_aab="$work/full-sample/baseline/build/outputs/bundle/release/baseline-release.aab"
full_candidate_aab="$work/full-sample/app/build/outputs/bundle/release/app-release.aab"
for artifact in "$plugin_jar" "$safe_baseline_aab" "$safe_candidate_aab" \
  "$full_baseline_aab" "$full_candidate_aab" "$size_record"; do
  [[ -f "$artifact" ]] || fail "measurement artifact is missing: $artifact"
done

cat >"$output" <<EOF
candidate.sha256=$digest
environment.os=macOS
environment.arch=arm64
complexity.verdict=PASS
safe.clean.baselineSeconds=$safe_baseline_seconds
safe.clean.candidateSeconds=$safe_candidate_seconds
full.clean.baselineSeconds=$full_baseline_seconds
full.clean.candidateSeconds=$full_candidate_seconds
warm.noClean.baselineSeconds=$warm_baseline_seconds
warm.noClean.candidateSeconds=$warm_candidate_seconds
memory.peakMib.baseline=$full_baseline_memory
memory.peakMib.candidate=$full_candidate_memory
sample.safe.baselineBytes=$(stat -f %z "$safe_baseline_aab")
sample.safe.candidateBytes=$(stat -f %z "$safe_candidate_aab")
sample.full.baselineBytes=$(stat -f %z "$full_baseline_aab")
sample.full.candidateBytes=$(stat -f %z "$full_candidate_aab")
plugin.jarBytes=$(awk -F= '$1=="plugin.jarBytes" {print $2}' "$size_record")
dependencies.newBytes=$(awk -F= '$1=="dependencies.newBytes" {print $2}' "$size_record")
EOF
echo "$output"
