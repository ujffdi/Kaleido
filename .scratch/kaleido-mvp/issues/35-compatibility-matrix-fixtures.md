# 35 — Establish the mandatory Compatibility Matrix

**What to build:** The packaged Kaleido candidate is exercised through marker-based real Consumer Projects on every exact mandatory toolchain row, producing immutable evidence for Java-only, built-in Kotlin, Compose, Sample App, and Sana Reference Consumer builds.

**Blocked by:** 32 — Publish an atomic Release Evidence Set; 34 — Implement version, schema, and migration contracts.

**Status:** claimed

- [x] Define exact immutable A3 and A4 rows: AGP 9.2.1/Gradle 9.4.1 and AGP 9.3.2/Gradle 9.5.0 on Linux x86_64 with JDK 17, Build Tools 36, and compile SDK 36.
- [x] Resolve the packaged plugin through a temporary Maven marker for every matrix job rather than applying implementation classes or a composite implementation shortcut.
- [ ] Build Java-only Safe, built-in Kotlin Safe, and minified Full with default Compose Generator fixtures on both mandatory rows.
- [ ] Exercise the public Sample App on every mandatory row without private test-only adoption APIs.
- [ ] Exercise the Sana Reference Consumer on both mandatory rows with dedicated test signing and no production secrets or product-specific Kaleido defaults.
- [ ] Run exhaustive supported-boundary and rejected-topology fixtures on A3 and A4.
- [ ] Publish a machine-readable matrix record that binds exact tool versions, platform/architecture, fixture/candidate digests, and results.
- [ ] Optional macOS arm64, Windows x86_64, JDK 21, preview tools, and extra AGP/Gradle pairs remain explicitly nonblocking evidence unless promoted by a future release decision.
- [x] A failed mandatory row is a product failure and prevents candidate promotion.

## Implementation progress

`CompatibilityMatrix` fixes the exact A3/A4 environments, required six-fixture closure, candidate/source/AAB digest binding, canonical ordering, and fail-closed `KLD-COMPAT-001` record validation. The standalone Java Safe, AGP built-in Kotlin Safe, Full-plus-default-Compose, and public Sample App fixtures all resolve `com.tongsr.kaleido:0.1.0-dev` through its temporary Maven marker; contract tests reject implementation-class and composite shortcuts. `run-compatibility-row.sh` requires the exact Linux x86_64/JDK 17/Build Tools/Gradle row, an explicitly migrated Sana matrix checkout using the public marker, non-production generated signing, the exhaustive functional suite, and all required AABs before it can atomically write a PASS record.

Current macOS arm64 nonblocking evidence passes all four standalone fixtures on both
A3 and A4 toolchains and the updated complete 91-test plugin suite on each row. The
script correctly rejects this host as mandatory evidence. The issue remains open until
both Linux x86_64 rows and the migrated Sana Reference Consumer produce their real
immutable records.
