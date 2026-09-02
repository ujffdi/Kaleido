# Adoption, profiles, DSL, and signing

## Preconditions and topology

Apply `com.android.application` before `io.github.ujffdi.kaleido`. The MVP supports one
base application module, one minified `release` build type, no dynamic-feature,
asset-pack, AI-pack, test-only, library, or KMP target, and no other final-AAB
transform owner. Unsupported topology fails before output publication.

The single comprehensive Sample App is
[`samples/kaleido-sample`](../../samples/kaleido-sample). Its shared `baseline` and
`app` modules provide ordinary and Full-plus-Compose AABs from the same Consumer
source tree. It is compiled against the packaged plugin marker by
`scripts/release/validate-public-docs.sh` and the Compatibility Matrix row. Minimal
Safe, Full Compose, and native projects remain under `release/fixtures` as
machine-facing acceptance inputs, not developer examples.

## Safe Defaults v1

With no `kaleido` block, the profile is `SAFE`; Compose is disabled; deletion,
language filtering, unused-string replacement, and every Protection declaration
are empty. Kaleido generates 4 packages x 4 classes x 4 methods, 8 layouts, 16
drawables, 32 strings, and no Activity. The package base defaults to
`<android namespace>.kaleido.generated`. The default seed is derived from the
application and exact variant identity; provide a Gradle `Provider<String>` via
`seed.set(...)` when release policy requires an explicit secret seed. Reports
contain only its SHA-256 fingerprint.

## Full and Compose

`FULL` enables explicitly selected Activity generation and narrowly bounded
resource operations: exact native-library basenames, permitted exact `META-INF`
metadata, confirmed-unused string replacement, and retained languages. These
operations are never inferred from an empty selector.

Compose Generator is optional AndroidJunkCode-equivalent content, disabled by
default. Enabling it requires the Consumer Project's Compose build feature and
matching Kotlin Compose compiler plugin. Generated functions are Runtime-only,
are retained in final DEX, and have no Kaleido-declared Activity, Manifest,
navigation, reflection, JNI, or other deliberate entry point. Static and
controlled-device evidence does not prove absolute unreachability from arbitrary
Consumer or third-party runtime behavior.

Generation ranges are finite: package/class counts 1..64, methods 1..128,
layouts 1..256, drawables 1..512, strings 1..4096, Activities 0..64, Compose
files 1..64, functions per file 1..32, and Compose total at most 512.

## Protection Requirements and Escape Hatches

`originalClassNames`, `resourceNames`, and `packagedPaths` are exact bounded
Protection Requirements. Use typed `classes("stable-id")` or
`resources("stable-id")` Escape Hatches for an exact identity or bounded prefix;
every declaration needs a non-empty reason and applicable dimensions:
`REACHABILITY`, `ORIGINAL_IDENTITY`, `DESCRIPTOR_CLOSURE`, `RUNTIME_ATTRIBUTES`,
`RESOURCE_NAME`, or `PACKAGED_PATH`. A broad undocumented wildcard is not an
escape hatch. Final class, Manifest/XML, JNI/reflection, R8, resource-table, and
Bundle checks fail closed when the declared closure cannot be proven.

## Signing

Kaleido owns signing after canonical final-AAB rewriting. Configure exactly one
complete source. Precedence is exact-variant DSL, top-level DSL, environment,
then Gradle properties; a partial higher-precedence source fails rather than
mixing credentials. Each source needs keystore path, store password, alias, key
password, and expected certificate SHA-256. Environment names are
`KALEIDO_UPLOAD_KEYSTORE`, `KALEIDO_UPLOAD_STORE_PASSWORD`,
`KALEIDO_UPLOAD_KEY_ALIAS`, `KALEIDO_UPLOAD_KEY_PASSWORD`, and
`KALEIDO_UPLOAD_CERTIFICATE_SHA256`. Production credentials must come from a
protected secret store and must never be committed or exposed to untrusted PRs.

The task verifies the unsigned digest, expected certificate identity, signature
coverage of every non-signature entry, Bundle structure, and any pre-existing
code-transparency entry before atomic publication.
