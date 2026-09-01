#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
work_root="$repo_root/build/release-gates/documentation"
plugin_repository="$repo_root/kaleido-gradle-plugin/build/functional-test-repository"
mkdir -p "$work_root"

python3 - "$repo_root" <<'PY'
import pathlib, re, sys
root = pathlib.Path(sys.argv[1])
files = [root / "README.md", root / "SECURITY.md", root / "CONTRIBUTING.md",
         root / "CHANGELOG.md"] + sorted((root / "docs/public").glob("*.md"))
failures = []
for source in files:
    text = source.read_text(encoding="utf-8")
    for target in re.findall(r"(?<!!)\[[^]]+\]\(([^)]+)\)", text):
        target = target.split("#", 1)[0]
        if not target or "://" in target or target.startswith("mailto:"):
            continue
        if not (source.parent / target).resolve().exists():
            failures.append(f"{source.relative_to(root)} -> {target}")
    if re.search(r"/Users/|[A-Za-z]:\\\\Users\\\\", text):
        failures.append(f"{source.relative_to(root)} contains a user-home path")
if failures:
    raise SystemExit("KLD-PUBLICATION-001 invalid public documentation: " + "; ".join(failures))

source_codes = set(re.findall(r"KLD-([A-Z]+)-[0-9]+", "\n".join(
    path.read_text(encoding="utf-8") for path in
    (root / "kaleido-gradle-plugin/src/main/java").rglob("*.java"))))
docs_text = "\n".join(path.read_text(encoding="utf-8") for path in files)
documented = set(re.findall(r"KLD-([A-Z]+)-", docs_text))
missing = sorted(source_codes - documented)
if missing:
    raise SystemExit("KLD-PUBLICATION-001 undocumented diagnostic families: " + ",".join(missing))
print(f"links-ok files={len(files)} diagnostics={len(source_codes)}")
PY

cd "$repo_root"
./gradlew :kaleido-gradle-plugin:publishAllPublicationsToFunctionalTestRepository \
  :kaleido-gradle-plugin:validatePlugins

test_store="$work_root/documentation-test-upload.p12"
test_password="kaleido-documentation-test"
if [[ ! -f "$test_store" ]]; then
  keytool -genkeypair -alias upload -keyalg RSA -keysize 2048 -validity 7 \
    -dname "CN=Kaleido Documentation Test" -storetype PKCS12 \
    -keystore "$test_store" -storepass "$test_password" -keypass "$test_password" \
    >/dev/null 2>&1
fi
test_certificate="$({ keytool -exportcert -alias upload -keystore "$test_store" \
  -storepass "$test_password" -rfc 2>/dev/null \
  | openssl x509 -outform der 2>/dev/null \
  | shasum -a 256; } | awk '{print $1}')"
export KALEIDO_UPLOAD_KEYSTORE="$test_store"
export KALEIDO_UPLOAD_STORE_PASSWORD="$test_password"
export KALEIDO_UPLOAD_KEY_ALIAS="upload"
export KALEIDO_UPLOAD_KEY_PASSWORD="$test_password"
export KALEIDO_UPLOAD_CERTIFICATE_SHA256="$test_certificate"

for fixture in samples/kaleido-sample release/fixtures/full-compose; do
  name="$(basename "$fixture")"
  target="$work_root/$name"
  rm -rf "$target"
  mkdir -p "$target"
  cp -R "$repo_root/$fixture/." "$target/"
  ./gradlew -p "$target" clean bundleRelease \
    -PmatrixPluginRepository="$plugin_repository" \
    -PmatrixAgp=9.2.1 -PmatrixKaleido=0.1.0-dev -PmatrixKotlin=2.2.10
done

cat > "$work_root/documentation-validation.properties" <<EOF
schema=KaleidoDocumentationValidation.v1
candidate.version=0.1.0-dev
markerResolution=PASS
sampleSafe=PASS
fullCompose=PASS
links=PASS
diagnostics=PASS
credentials=test-only
verdict=PASS
EOF
echo "$work_root/documentation-validation.properties"
