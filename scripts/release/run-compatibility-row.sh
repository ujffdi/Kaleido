#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 2 ]]; then
  echo "usage: run-compatibility-row.sh A3 VERSION" >&2
  exit 2
fi

matrix_row="$1"
candidate_version="$2"
case "$matrix_row" in
  A3)
    matrix_agp="9.2.0"
    matrix_gradle="9.4.1"
    ;;
  *)
    echo "KLD-COMPAT-001 unsupported mandatory matrix row: $matrix_row" >&2
    exit 2
    ;;
esac
if [[ "$candidate_version" == *dev* || "$candidate_version" == *SNAPSHOT* ]]; then
  echo "KLD-COMPAT-001 mandatory rows require a final candidate version" >&2
  exit 2
fi

repository_root="$(cd "$(dirname "$0")/../.." && pwd)"
matrix_gradle_command="${KALEIDO_MATRIX_GRADLE:-$repository_root/gradlew}"
matrix_sdk="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
matrix_output="$repository_root/build/release-gates/compatibility/$matrix_row"
matrix_work="$matrix_output/work"
plugin_repository="$repository_root/kaleido-gradle-plugin/build/functional-test-repository"
plugin_jar="$repository_root/kaleido-gradle-plugin/build/libs/kaleido-gradle-plugin-$candidate_version.jar"

if [[ "$(uname -s)" != "Darwin" || "$(uname -m)" != "arm64" ]]; then
  echo "KLD-COMPAT-001 $matrix_row requires macOS arm64" >&2
  exit 1
fi
if ! java -version 2>&1 | head -1 | grep -Eq 'version "17([.]|\")'; then
  echo "KLD-COMPAT-001 $matrix_row requires runtime JDK 17" >&2
  exit 1
fi
if [[ -z "$matrix_sdk" || ! -d "$matrix_sdk/build-tools/36.0.0" ]]; then
  echo "KLD-COMPAT-001 Build Tools 36.0.0 are required" >&2
  exit 1
fi
if ! "$matrix_gradle_command" --version | grep -Eq "^Gradle $matrix_gradle$"; then
  echo "KLD-COMPAT-001 expected Gradle $matrix_gradle" >&2
  exit 1
fi
rm -rf "$matrix_work"
mkdir -p "$matrix_work"
test_store="$matrix_output/test-upload.p12"
test_password="kaleido-matrix-test-signing"
if [[ ! -f "$test_store" ]]; then
  keytool -genkeypair -alias upload -keyalg RSA -keysize 2048 -validity 30 \
    -dname "CN=Kaleido Matrix Test" -storetype PKCS12 -keystore "$test_store" \
    -storepass "$test_password" -keypass "$test_password" >/dev/null 2>&1
fi
test_certificate="$({ keytool -exportcert -alias upload -keystore "$test_store" \
  -storepass "$test_password" -rfc 2>/dev/null \
  | openssl x509 -outform der 2>/dev/null \
  | sha256sum; } | awk '{print $1}')"
export KALEIDO_UPLOAD_KEYSTORE="$test_store"
export KALEIDO_UPLOAD_STORE_PASSWORD="$test_password"
export KALEIDO_UPLOAD_KEY_ALIAS="upload"
export KALEIDO_UPLOAD_KEY_PASSWORD="$test_password"
export KALEIDO_UPLOAD_CERTIFICATE_SHA256="$test_certificate"

tree_digest() {
  local source_root="$1"
  (
    cd "$source_root"
    find . -type f \
      ! -path './build/*' ! -path './.gradle/*' ! -path './.kotlin/*' \
      ! -name 'local.properties' \
      -print | LC_ALL=C sort | while IFS= read -r source_file; do
        printf '%s  %s\n' "$(sha256sum "$source_file" | awk '{print $1}')" "$source_file"
      done
  ) | sha256sum | awk '{print $1}'
}

file_digest() {
  sha256sum "$1" | awk '{print $1}'
}

cd "$repository_root"
./gradlew :kaleido-gradle-plugin:publishAllPublicationsToFunctionalTestRepository \
  :kaleido-gradle-plugin:jar :release-gates:installDist \
  -PkaleidoVersion="$candidate_version" --stacktrace
./gradlew :kaleido-gradle-plugin:test :kaleido-gradle-plugin:validatePlugins \
  -PkaleidoVersion="$candidate_version" -PkaleidoTestAgp="$matrix_agp" \
  -PkaleidoTestGradle="$matrix_gradle" --stacktrace

fixture_arguments=()
for fixture_name in java-safe kotlin-safe full-compose; do
  fixture_source="$repository_root/release/fixtures/$fixture_name"
  fixture_work="$matrix_work/$fixture_name"
  mkdir -p "$fixture_work"
  cp -R "$fixture_source/." "$fixture_work/"
  "$matrix_gradle_command" -p "$fixture_work" clean bundleRelease --stacktrace \
    -PmatrixPluginRepository="$plugin_repository" -PmatrixAgp="$matrix_agp" \
    -PmatrixKaleido="$candidate_version" -PmatrixKotlin=2.2.10
  fixture_aab="$fixture_work/app/build/outputs/bundle/release/app-release.aab"
  fixture_arguments+=(--fixture \
    "$fixture_name,$(tree_digest "$fixture_source"),$(file_digest "$fixture_aab"),PASS")
done

sample_name=sample-comprehensive
sample_source="$repository_root/samples/kaleido-sample"
sample_work="$matrix_work/$sample_name"
mkdir -p "$sample_work"
cp -R "$sample_source/." "$sample_work/"
"$matrix_gradle_command" -p "$sample_work" clean \
  :baseline:bundleRelease :app:bundleRelease --stacktrace \
  -PsamplePluginRepository="$plugin_repository" -PsampleAgpVersion="$matrix_agp" \
  -PsampleKaleidoVersion="$candidate_version"
sample_aab="$sample_work/app/build/outputs/bundle/release/app-release.aab"
fixture_arguments+=(--fixture \
  "$sample_name,$(tree_digest "$sample_source"),$(file_digest "$sample_aab"),PASS")

fixture_arguments+=(--fixture \
  "exhaustive-boundary,$(tree_digest "$repository_root/kaleido-gradle-plugin/src/test"),,PASS")

"$repository_root/release-gates/build/install/release-gates/bin/release-gates" \
  --row "$matrix_row" --agp "$matrix_agp" --gradle "$matrix_gradle" \
  --os macos --arch arm64 --jdk 17 --build-tools 36.0.0 --compile-sdk 36 \
  --kotlin-mode built-in --candidate-sha256 "$(file_digest "$plugin_jar")" \
  "${fixture_arguments[@]}" --output "$matrix_output/matrix-record.properties"
