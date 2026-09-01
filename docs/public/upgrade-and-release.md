# Upgrade, deprecation, and immutable release policy

Kaleido applies Semantic Versioning to the Adoption Contract, exact Compatibility
Matrix, diagnostics, public report/evidence schemas, and mapping envelopes.
Internal task names, plans, receipts, and cache schemas are not public API.

A public removal or supported-row shrink normally requires a major release after
at least one non-preview Bridge Release and 90 days. A confirmed security, legal,
licensing, or upstream-removal constraint may shorten that window when continued
support is unsafe or impossible. The next major reads at least the previous
Artifact Report major; internal cache formats are invalidated, never migrated.
Historical Release Evidence Sets and mappings are immutable and are not rewritten.

Before upgrading, archive the current plugin/toolchain, reports, mappings and
release manifest; read `CHANGELOG.md`; adopt the Bridge Release; resolve every
deprecation; run both old and new versions on the same non-production Release
fixture; compare semantics and evidence; then promote the new version. Unknown
schema/diagnostic/compatibility failures require investigation rather than a
suppression flag.

Each release candidate identity binds source revision/tag, plugin and marker,
sources, documentation, SBOM, provenance, matrix, runtime, reproducibility,
performance and approval records. Automated gates finish before two independent
human approvals. Publication submits those exact bytes to the Gradle Plugin
Portal and creates a matching signed-tag GitHub draft without rebuild. The source
release is finalized only after the public Portal bytes, clean marker resolution,
Release Evidence Set, bundletool validation, and controlled-device smoke close into
the signed final Release Dossier. Maven Central and Google Play are outside the MVP.
A defective public version is never replaced: publish an advisory and gate a new
patch candidate.
