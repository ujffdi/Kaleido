# 36 — Pass bundletool and controlled-device runtime gates

**What to build:** Every mandatory Compatibility Matrix row proves that Kaleido's final AAB is structurally valid, generates device-specific APK sets, installs, starts, and preserves the Consumer Project's core runtime behavior without generated startup effects.

**Blocked by:** 35 — Establish the mandatory Compatibility Matrix.

**Status:** claimed

- [x] Pin and record the bundletool version used for release validation.
- [ ] Run bundletool validation against every final AAB produced by every mandatory matrix fixture.
- [ ] Generate APK sets for representative controlled device specifications on every mandatory row.
- [ ] Install and launch controlled Java, Kotlin, Compose, native-library, and representative resource fixtures for A3 and A4.
- [ ] Verify package identity, launch Activity resolution, representative resource lookups, native loading where present, and the consumer's core smoke path.
- [ ] Verify that Safe and Compose Junk Code add no generated startup invocation or user-visible UI behavior.
- [ ] Run Sana controlled-device core smoke coverage with test credentials and test signing only.
- [x] Capture machine-readable validation and runtime results bound to the candidate, matrix row, AAB digest, device specification, and test revision.
- [x] Installation, launch, bundletool, resource, native, or code-transparency failure blocks the candidate.

## Implementation progress

The release-only runtime gate pins bundletool 1.18.1, validates each final AAB, creates an arm64 API 36 device-targeted APK set using Build Tools 36.0.0 `aapt2`, installs through bundletool, resolves the package and launcher, launches the Consumer path, and requires a fixture-specific core-smoke marker with no generated-startup signal. `RuntimeGate` binds the candidate, Compatibility Matrix record, test revision, device-spec digest, every AAB/APK-set digest, and individual bundletool/install/launch/package/Activity/resource/native/startup-isolation verdict into a canonical fail-closed record. The standalone native/resource fixture compiles an arm64 JNI library and asserts both `resource-ok` and native result `42` at runtime.

On the existing Pixel 9 Pro AVD, Java Safe, built-in Kotlin Safe, Full-plus-Compose, Sample App, and native/resource fixtures all passed bundletool validation, targeted APK generation, installation, launcher resolution, cold launch, process liveness, resource checks, and expected Consumer-owned startup. This is macOS arm64 nonblocking evidence. The issue remains open until the A3/A4 Linux matrix outputs and Sana test checkout each pass the controlled-device script and produce mandatory row records.
