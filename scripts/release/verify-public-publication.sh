#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
manifest="${1:?usage: verify-public-publication.sh RELEASE_MANIFEST VERSION}"
version="${2:?usage: verify-public-publication.sh RELEASE_MANIFEST VERSION}"
work="$repo_root/build/release-gates/post-publication/$version"
portal="https://plugins.gradle.org/m2"

fail() { echo "KLD-PUBLICATION-001 $*" >&2; exit 1; }
[[ -f "$manifest" && -f "$manifest.asc" ]] || fail "signed manifest is required"
gpg --verify "$manifest.asc" "$manifest" >/dev/null 2>&1 || fail "manifest signature is invalid"
mkdir -p "$work/download"

implementation="$work/download/kaleido-gradle-plugin-$version.jar"
marker="$work/download/com.tongsr.kaleido.gradle.plugin-$version.pom"
curl -fsSL "$portal/com/tongsr/kaleido/kaleido-gradle-plugin/$version/kaleido-gradle-plugin-$version.jar" -o "$implementation"
curl -fsSL "$portal/com/tongsr/kaleido/com.tongsr.kaleido.gradle.plugin/$version/com.tongsr.kaleido.gradle.plugin-$version.pom" -o "$marker"
expected_candidate="$(awk -F= '$1=="asset.0.sha256" {print $2}' "$manifest")"
expected_marker="$(awk -F= '$1=="asset.2.sha256" {print $2}' "$manifest")"
[[ "$(shasum -a 256 "$implementation" | awk '{print $1}')" == "$expected_candidate" ]] || fail "public plugin digest mismatch"
[[ "$(shasum -a 256 "$marker" | awk '{print $1}')" == "$expected_marker" ]] || fail "public marker digest mismatch"

consumer="$work/consumer"
rm -rf "$consumer"
mkdir -p "$consumer"
cp -R "$repo_root/samples/kaleido-sample/." "$consumer/"
python3 - "$consumer/settings.gradle.kts" <<'PY'
import pathlib, re, sys
p = pathlib.Path(sys.argv[1])
s = p.read_text()
s = re.sub(r'maven \{ url = uri\(providers\.gradleProperty\("matrixPluginRepository"\)\.get\(\)\) \}\n\s*', '', s)
p.write_text(s)
PY

test_store="$work/post-publication-test.p12"
password="kaleido-post-publication-test"
keytool -genkeypair -alias upload -keyalg RSA -keysize 2048 -validity 7 \
  -dname "CN=Kaleido Post Publication Test" -storetype PKCS12 -keystore "$test_store" \
  -storepass "$password" -keypass "$password" >/dev/null 2>&1
certificate="$({ keytool -exportcert -alias upload -keystore "$test_store" -storepass "$password" -rfc 2>/dev/null \
  | openssl x509 -outform der 2>/dev/null | shasum -a 256; } | awk '{print $1}')"
export KALEIDO_UPLOAD_KEYSTORE="$test_store" KALEIDO_UPLOAD_STORE_PASSWORD="$password"
export KALEIDO_UPLOAD_KEY_ALIAS=upload KALEIDO_UPLOAD_KEY_PASSWORD="$password"
export KALEIDO_UPLOAD_CERTIFICATE_SHA256="$certificate"
gradle -p "$consumer" clean bundleRelease -PmatrixAgp=9.2.1 -PmatrixKaleido="$version"
aab="$consumer/app/build/outputs/bundle/release/app-release.aab"
"$repo_root/gradlew" -q :release-gates:installDist
[[ -f "$aab" ]] || fail "public marker Consumer AAB is missing"
serial="${KALEIDO_DEVICE_SERIAL:-}"
sdk="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
[[ -n "$serial" && -n "$sdk" ]] || fail "controlled device serial and Android SDK are required"
adb="$sdk/platform-tools/adb"
aapt2="$sdk/build-tools/36.0.0/aapt2"
[[ -x "$adb" && -x "$aapt2" ]] || fail "adb and aapt2 are required"
apks="$work/post-publication.apks"
classpath="$repo_root/release-gates/build/install/release-gates/lib/*"
export KALEIDO_POST_PUBLICATION_PASSWORD="$password"
java -cp "$classpath" com.tongsr.kaleido.release.RuntimeGateCli \
  "$aab" "$repo_root/release/device-specs/pixel-api-36-arm64.json" "$apks" \
  "$test_store" upload KALEIDO_POST_PUBLICATION_PASSWORD "$aapt2"
java -cp "$classpath" com.tongsr.kaleido.release.RuntimeGateInstallCli "$apks" "$adb" "$serial"
package_name="com.tongsr.kaleido.sample"
activity="$($adb -s "$serial" shell cmd package resolve-activity --brief "$package_name" | tr -d '\r' | tail -1)"
[[ "$activity" == "$package_name/"* ]] || fail "launcher activity did not resolve"
$adb -s "$serial" shell am force-stop "$package_name"
$adb -s "$serial" shell am start -W -n "$activity" >/dev/null
$adb -s "$serial" shell pidof "$package_name" >/dev/null || fail "post-publication Consumer did not stay alive"
[[ -f "$consumer/app/build/reports/kaleido/release/release-evidence-set/release-evidence-set-manifest.properties" ]] || \
  fail "post-publication Release Evidence Set is missing"
$adb -s "$serial" uninstall "$package_name" >/dev/null || true

cat > "$work/post-publication-record.properties" <<EOF
schema=KaleidoPostPublication.v1
candidate.sha256=$expected_candidate
coordinates=com.tongsr.kaleido:$version
publicPluginDigest=PASS
publicMarkerDigest=PASS
cleanMarkerResolution=PASS
consumerReleaseEvidence=PASS
bundletoolAndDeviceSmoke=PASS
verdict=PASS
EOF
echo "$work/post-publication-record.properties"
