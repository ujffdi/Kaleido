#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
input="${1:?usage: run-performance-gate.sh RAW_MEASUREMENTS.properties}"
matrix_record="${KALEIDO_MATRIX_RECORD:-}"
runtime_record="${KALEIDO_RUNTIME_RECORD:-}"
output="$repo_root/build/release-gates/performance/performance-gate.properties"

fail() {
  echo "KLD-PERF-001 $*" >&2
  exit 1
}

[[ "$(uname -s)" == "Linux" && "$(uname -m)" == "x86_64" ]] || fail "measurements require Linux x86_64"
[[ -f "$input" ]] || fail "raw measurement file is missing"
[[ -n "$matrix_record" && -f "$matrix_record" ]] || fail "KALEIDO_MATRIX_RECORD is required"
[[ -n "$runtime_record" && -f "$runtime_record" ]] || fail "KALEIDO_RUNTIME_RECORD is required"
grep -qx 'verdict=PASS' "$matrix_record" || fail "compatibility matrix did not pass"
grep -qx 'verdict=PASS' "$runtime_record" || fail "runtime gate did not pass"

"$repo_root/gradlew" -q :release-gates:classes :kaleido-gradle-plugin:test \
  --tests com.tongsr.kaleido.gradle.ClassRewriteContractTest.adversarialLargeInventoryAndPrefixCollisionsRemainBounded
mkdir -p "$(dirname "$output")"
classpath="$repo_root/release-gates/build/classes/java/main:$repo_root/release-gates/build/resources/main"
java -cp "$classpath" com.tongsr.kaleido.release.PerformanceGateCli \
  --input "$input" --output "$output"
echo "$output"
