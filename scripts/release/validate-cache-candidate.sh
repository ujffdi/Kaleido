#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
manifest="${1:?usage: validate-cache-candidate.sh RELEASE_MANIFEST}"
output="$repo_root/build/release-gates/candidate/cache-gates.properties"

fail() { echo "KLD-PUBLICATION-001 $*" >&2; exit 1; }
[[ -f "$manifest" && -f "$manifest.asc" ]] || fail "signed release manifest is required"
command -v gpg >/dev/null || fail "gpg is required"
gpg --batch --verify "$manifest.asc" "$manifest" >/dev/null 2>&1 || \
  fail "release manifest signature is invalid"

candidate="$(awk -F= '$1=="asset.0.sha256" {print $2}' "$manifest")"
version="$(awk -F= '$1=="version" {print $2}' "$manifest")"
[[ -n "$candidate" && -n "$version" ]] || fail "candidate identity is incomplete"
[[ "$version" != *dev* && "$version" != *SNAPSHOT* ]] || \
  fail "development versions cannot become release candidates"
plugin_jar="$repo_root/kaleido-gradle-plugin/build/libs/kaleido-gradle-plugin-$version.jar"
[[ -f "$plugin_jar" ]] || fail "candidate plugin JAR is missing"
actual="$(shasum -a 256 "$plugin_jar" | awk '{print $1}')"
[[ "$actual" == "$candidate" ]] || fail "candidate plugin JAR differs from the manifest"

cd "$repo_root"
./gradlew :kaleido-gradle-plugin:test -PkaleidoVersion="$version" \
  --tests com.tongsr.kaleido.gradle.KaleidoPluginFunctionalTest.configurationCacheIsReusedWithoutCapturingGradleModelObjects \
  --tests com.tongsr.kaleido.gradle.KaleidoPluginFunctionalTest.noCleanBuildKeepsDeterministicStagesUpToDateAndRevalidatesSensitiveStages \
  --tests com.tongsr.kaleido.gradle.KaleidoPluginFunctionalTest.relocatedConsumerRestoresDeterministicStagesFromConsumerBuildCache \
  --tests com.tongsr.kaleido.gradle.KaleidoPluginFunctionalTest.threeRelocatedWorkspacesHaveByteIdenticalDeterministicBoundaries \
  --stacktrace

mkdir -p "$(dirname "$output")"
cat > "$output" <<EOF
schema=KaleidoCacheCandidateGate.v1
candidate.sha256=$candidate
configurationCache=PASS
noCleanUpToDate=PASS
relocatedBuildCache=PASS
threeWorkspaceByteReproducibility=PASS
verdict=PASS
EOF
echo "$output"
