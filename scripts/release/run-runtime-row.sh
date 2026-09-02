#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 3 ]]; then
  echo "usage: run-runtime-row.sh A3 <device-serial> VERSION" >&2
  exit 2
fi

runtime_row="$1"
device_serial="$2"
candidate_version="$3"
case "$runtime_row" in
  A3) runtime_agp="9.2.0" ;;
  *) echo "KLD-RUNTIME-001 unsupported compatibility row: $runtime_row" >&2; exit 2 ;;
esac
if [[ "$candidate_version" == *dev* || "$candidate_version" == *SNAPSHOT* ]]; then
  echo "KLD-RUNTIME-001 mandatory rows require a final candidate version" >&2
  exit 2
fi

repository_root="$(cd "$(dirname "$0")/../.." && pwd)"
runtime_gradle="${KALEIDO_MATRIX_GRADLE:-$repository_root/gradlew}"
runtime_sdk="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
runtime_output="$repository_root/build/release-gates/compatibility/$runtime_row"
matrix_record="$runtime_output/matrix-record.properties"
matrix_work="$runtime_output/work"
runtime_work="$runtime_output/runtime"
plugin_repository="$repository_root/kaleido-gradle-plugin/build/functional-test-repository"
plugin_jar="$repository_root/kaleido-gradle-plugin/build/libs/kaleido-gradle-plugin-$candidate_version.jar"
device_spec="$repository_root/release/device-specs/pixel-api-36-arm64.json"
adb="$runtime_sdk/platform-tools/adb"
aapt2="$runtime_sdk/build-tools/36.0.0/aapt2"

if [[ ! -f "$matrix_record" ]]; then
  echo "KLD-RUNTIME-001 mandatory Compatibility Matrix record is missing" >&2
  exit 1
fi
if [[ ! -x "$adb" || ! -x "$aapt2" ]]; then
  echo "KLD-RUNTIME-001 pinned adb/aapt2 tools are missing" >&2
  exit 1
fi
if ! "$adb" -s "$device_serial" get-state 2>/dev/null | grep -qx device; then
  echo "KLD-RUNTIME-001 controlled device is not online: $device_serial" >&2
  exit 1
fi
mkdir -p "$runtime_work"
runtime_store="$runtime_output/test-upload.p12"
runtime_password="kaleido-matrix-test-signing"
export KALEIDO_RUNTIME_TEST_PASSWORD="$runtime_password"

file_digest() {
  sha256sum "$1" | awk '{print $1}'
}

tree_digest() {
  local source_root="$1"
  (
    cd "$source_root"
    find . -type f ! -path './build/*' ! -path './.gradle/*' ! -path './.kotlin/*' \
      ! -name 'local.properties' -print | LC_ALL=C sort \
      | while IFS= read -r source_file; do
          printf '%s  %s\n' "$(sha256sum "$source_file" | awk '{print $1}')" "$source_file"
        done
  ) | sha256sum | awk '{print $1}'
}

cd "$repository_root"
./gradlew :release-gates:installDist --stacktrace

native_source="$repository_root/release/fixtures/native-resource"
native_work="$matrix_work/native-resource"
mkdir -p "$native_work"
cp -R "$native_source/." "$native_work/"
"$runtime_gradle" -p "$native_work" clean bundleRelease --stacktrace \
  -PmatrixPluginRepository="$plugin_repository" -PmatrixAgp="$runtime_agp" \
  -PmatrixKaleido="$candidate_version"

fixture_names=(java-safe kotlin-safe full-compose native-resource sample-comprehensive)
fixture_aabs=(
  "$matrix_work/java-safe/app/build/outputs/bundle/release/app-release.aab"
  "$matrix_work/kotlin-safe/app/build/outputs/bundle/release/app-release.aab"
  "$matrix_work/full-compose/app/build/outputs/bundle/release/app-release.aab"
  "$native_work/app/build/outputs/bundle/release/app-release.aab"
  "$matrix_work/sample-comprehensive/app/build/outputs/bundle/release/app-release.aab"
)
fixture_packages=(
  com.tongsr.kaleido.matrix.java
  com.tongsr.kaleido.matrix.kotlin
  com.tongsr.kaleido.matrix.compose
  com.tongsr.kaleido.matrix.nativeprobe
  com.tongsr.kaleido.sample
)
fixture_markers=(
  KALEIDO_PROBE_PASS
  KALEIDO_PROBE_PASS
  KALEIDO_PROBE_PASS
  'KALEIDO_PROBE_PASS resource=resource-ok native=42'
  KALEIDO_RESOURCE_PROBE_PASS
)

record_arguments=()
for index in 0 1 2 3 4; do
  fixture_name="${fixture_names[$index]}"
  fixture_aab="${fixture_aabs[$index]}"
  fixture_package="${fixture_packages[$index]}"
  fixture_marker="${fixture_markers[$index]}"
  fixture_apks="$runtime_work/$fixture_name.apks"
  if [[ ! -f "$fixture_aab" ]]; then
    echo "KLD-RUNTIME-001 missing $fixture_name final AAB" >&2
    exit 1
  fi
  java -cp "$repository_root/release-gates/build/install/release-gates/lib/*" \
    com.tongsr.kaleido.release.RuntimeGateCli \
    "$fixture_aab" "$device_spec" "$fixture_apks" "$runtime_store" upload \
    KALEIDO_RUNTIME_TEST_PASSWORD "$aapt2"
  "$adb" -s "$device_serial" uninstall "$fixture_package" >/dev/null 2>&1 || true
  java -cp "$repository_root/release-gates/build/install/release-gates/lib/*" \
    com.tongsr.kaleido.release.RuntimeGateInstallCli \
    "$fixture_apks" "$adb" "$device_serial"
  "$adb" -s "$device_serial" shell pm path "$fixture_package" | grep -q '^package:'
  resolved_activity="$("$adb" -s "$device_serial" shell cmd package resolve-activity \
    --brief -c android.intent.category.LAUNCHER "$fixture_package" | tail -1 | tr -d '\r')"
  if [[ "$resolved_activity" != "$fixture_package"/* \
      || "$resolved_activity" == *'.kaleido.generated.'* ]]; then
    echo "KLD-RUNTIME-001 unexpected launcher for $fixture_name" >&2
    exit 1
  fi
  "$adb" -s "$device_serial" logcat -c
  "$adb" -s "$device_serial" shell monkey -p "$fixture_package" \
    -c android.intent.category.LAUNCHER 1 >/dev/null
  observed=""
  for attempt in $(seq 1 15); do
    observed="$("$adb" -s "$device_serial" logcat -d | tr -d '\r')"
    if printf '%s' "$observed" | grep -Fq "$fixture_marker"; then break; fi
    sleep 1
  done
  if ! printf '%s' "$observed" | grep -Fq "$fixture_marker"; then
    echo "KLD-RUNTIME-001 core smoke marker missing for $fixture_name" >&2
    exit 1
  fi
  if printf '%s' "$observed" | grep -Fq '.kaleido.generated.'; then
    echo "KLD-RUNTIME-001 generated startup signal observed for $fixture_name" >&2
    exit 1
  fi
  if [[ -z "$("$adb" -s "$device_serial" shell pidof "$fixture_package" | tr -d '\r')" ]]; then
    echo "KLD-RUNTIME-001 process did not remain alive for $fixture_name" >&2
    exit 1
  fi
  native_check=NOT_APPLICABLE
  if [[ "$fixture_name" == native-resource ]]; then native_check=PASS; fi
  record_arguments+=(--fixture \
    "$fixture_name,$(file_digest "$fixture_aab"),$(file_digest "$fixture_apks"),PASS,PASS,PASS,PASS,PASS,PASS,$native_check,PASS,PASS")
  "$adb" -s "$device_serial" uninstall "$fixture_package" >/dev/null
done

java -cp "$repository_root/release-gates/build/install/release-gates/lib/*" \
  com.tongsr.kaleido.release.RuntimeRecordCli \
  --row "$runtime_row" --candidate-sha256 "$(file_digest "$plugin_jar")" \
  --matrix-record-sha256 "$(file_digest "$matrix_record")" \
  --test-revision-sha256 "$(tree_digest "$repository_root/release")" \
  --device-spec-sha256 "$(file_digest "$device_spec")" \
  "${record_arguments[@]}" --output "$runtime_output/runtime-record.properties"
