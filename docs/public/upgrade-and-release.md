# Upgrade, deprecation, and release policy

Kaleido applies Semantic Versioning to the Adoption Contract, exact Compatibility
Matrix, diagnostics, public report/evidence schemas, and mapping envelopes.
Internal task names, plans, receipts, and cache schemas are not public API.

A public removal or supported-row shrink normally requires a major release after
at least one non-preview Bridge Release and 90 days. A confirmed security, legal,
licensing, or upstream-removal constraint may shorten that window when continued
support is unsafe or impossible. The next major reads at least the previous
Artifact Report major; internal cache formats are invalidated, never migrated.
Historical Release Evidence Sets and mappings are immutable and are not rewritten.

Before upgrading, archive the current plugin/toolchain, reports, and mappings;
read `CHANGELOG.md`; adopt the Bridge Release; resolve every
deprecation; run both old and new versions on the same non-production Release
fixture; compare semantics and evidence; then promote the new version. Unknown
schema/diagnostic/compatibility failures require investigation rather than a
suppression flag.

For 0.1.0, publish from a clean `origin/main` revision after plugin/release-gate
tests, public Sample builds, `validatePlugins`, and `publishPlugins --validate-only`
pass for the exact final version. `publishPlugins` submits those bytes to the
Gradle Plugin Portal; the first version may remain pending while Gradle completes
its normal manual review. GPG manifests, independent approvals, and a Release
Dossier are optional higher-assurance records rather than publication blockers.
Maven Central and Google Play are outside the MVP. A defective public version is
never replaced: document it and publish a new patch version.
