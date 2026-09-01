# 34 — Implement version, schema, and migration contracts

**What to build:** Consumers and release investigators can upgrade Kaleido under an explicit SemVer and Bridge Release policy, read bounded historical report evidence, and receive stable compatibility, deprecation, schema, and migration diagnostics.

**Blocked by:** 32 — Publish an atomic Release Evidence Set.

**Status:** resolved

- [x] Treat plugin/DSL behavior, defaults, Compatibility Matrix claims, diagnostics, report/evidence schemas, and mapping formats as SemVer-governed public surfaces.
- [x] Allow compatible additive public behavior in minor releases and require a major release for breaking behavior.
- [x] Retain deprecations through at least the next non-preview minor and ninety days, with removal only in a major version.
- [x] Provide Bridge Release diagnostics that identify the deprecated surface, replacement, deadline/window, and migration action.
- [x] Identify Artifact Report dialects by URI and `major.minor` schema family.
- [x] A report reader accepts every minor dialect in its own major and the immediately previous major, while rejecting unsupported newer or older major families clearly.
- [x] Historical evidence remains immutable; any derived modern view has its own identity and never overwrites or impersonates the original.
- [x] Maintain distinct stable diagnostic code groups for compatibility, deprecation, schema, and migration concerns.
- [x] Golden compatibility tests cover additive minor fields, previous-major reading, unknown current-minor fields where permitted, unsupported major rejection, and immutable derived views.

## Answer

`KaleidoVersionContract` now inventories every SemVer-governed public surface and enforces patch/minor/major classification, a subsequent non-preview minor Bridge Release, a minimum 90-day retention window, major-only removal, and complete deprecation/migration guidance. Compatibility, deprecation, schema, and migration failures use distinct stable `KLD-COMPAT-*`, `KLD-DEPRECATION-*`, `KLD-SCHEMA-*`, and `KLD-MIGRATION-*` groups. Artifact Report 1.0 uses one canonical URI and is validated by `ArtifactReportReader` during atomic publication. The reader preserves additive unknown fields, accepts every minor in its own major plus the immediately previous major, and clearly rejects newer, older, malformed, or URI-mismatched dialects. Historical source bytes are defensively immutable; conversion creates a separately identified view bound to source schema, Release Evidence Set ID, source digest, and converter version. Checked-in 1.7 and 0.9 goldens exercise the compatibility boundary. The full 81-test suite and `validatePlugins` pass.
