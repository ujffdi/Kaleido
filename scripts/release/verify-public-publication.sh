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
marker="$work/download/io.github.ujffdi.kaleido.gradle.plugin-$version.pom"
curl -fsSL "$portal/io/github/ujffdi/kaleido-gradle-plugin/$version/kaleido-gradle-plugin-$version.jar" -o "$implementation"
curl -fsSL "$portal/io/github/ujffdi/kaleido/io.github.ujffdi.kaleido.gradle.plugin/$version/io.github.ujffdi.kaleido.gradle.plugin-$version.pom" -o "$marker"
expected_candidate="$(awk -F= '$1=="asset.0.sha256" {print $2}' "$manifest")"
marker_path="kaleido-gradle-plugin/build/publications/kaleidoPluginMarkerMaven/pom-default.xml"
expected_marker="$(awk -F= -v path="$marker_path" \
  '$1 ~ /^asset[.][0-9]+[.]path$/ && $2==path {getline; print $2; exit}' "$manifest")"
[[ -n "$expected_candidate" && -n "$expected_marker" ]] || fail "public artifact digests are absent"
[[ "$(shasum -a 256 "$implementation" | awk '{print $1}')" == "$expected_candidate" ]] || fail "public plugin digest mismatch"
[[ "$(shasum -a 256 "$marker" | awk '{print $1}')" == "$expected_marker" ]] || fail "public marker digest mismatch"

consumer="$work/consumer"
rm -rf "$consumer"
mkdir -p "$consumer"
cp -R "$repo_root/samples/kaleido-sample/." "$consumer/"

test_store="$work/post-publication-test.p12"
password="kaleido-post-publication-test"
if [[ ! -f "$test_store" ]]; then
  keytool -genkeypair -alias upload -keyalg RSA -keysize 2048 -validity 7 \
    -dname "CN=Kaleido Post Publication Test" -storetype PKCS12 -keystore "$test_store" \
    -storepass "$password" -keypass "$password" >/dev/null 2>&1
fi
certificate="$({ keytool -exportcert -alias upload -keystore "$test_store" -storepass "$password" -rfc 2>/dev/null \
  | openssl x509 -outform der 2>/dev/null | shasum -a 256; } | awk '{print $1}')"
export KALEIDO_UPLOAD_KEYSTORE="$test_store" KALEIDO_UPLOAD_STORE_PASSWORD="$password"
export KALEIDO_UPLOAD_KEY_ALIAS=upload KALEIDO_UPLOAD_KEY_PASSWORD="$password"
export KALEIDO_UPLOAD_CERTIFICATE_SHA256="$certificate"
"$repo_root/gradlew" -p "$consumer" clean \
  :baseline:bundleRelease :app:bundleRelease \
  -PsampleAgpVersion=9.2.0 -PsampleKaleidoVersion="$version"
aab="$consumer/app/build/outputs/bundle/release/app-release.aab"
"$repo_root/gradlew" -q :release-gates:installDist
[[ -f "$aab" ]] || fail "public marker Consumer AAB is missing"
sdk="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
[[ -n "$sdk" ]] || fail "Android SDK is required"
aapt2="$sdk/build-tools/36.0.0/aapt2"
[[ -x "$aapt2" ]] || fail "aapt2 is required"
apks="$work/post-publication.apks"
classpath="$repo_root/release-gates/build/install/release-gates/lib/*"
export KALEIDO_POST_PUBLICATION_PASSWORD="$password"
java -cp "$classpath" com.tongsr.kaleido.release.RuntimeGateCli \
  "$aab" "$repo_root/release/device-specs/pixel-api-36-arm64.json" "$apks" \
  "$test_store" upload KALEIDO_POST_PUBLICATION_PASSWORD "$aapt2"
[[ -f "$consumer/app/build/reports/kaleido/release/release-evidence-set/release-evidence-set-manifest.properties" ]] || \
  fail "post-publication Release Evidence Set is missing"

cat > "$work/post-publication-record.properties" <<EOF
schema=KaleidoPostPublication.v1
candidate.sha256=$expected_candidate
coordinates=io.github.ujffdi.kaleido:$version
publicPluginDigest=PASS
publicMarkerDigest=PASS
cleanMarkerResolution=PASS
consumerReleaseEvidence=PASS
bundletoolStaticValidation=PASS
verdict=PASS
EOF
echo "$work/post-publication-record.properties"
