# Upgrade and release policy

Kaleido applies Semantic Versioning to its public plugin ID, DSL, diagnostics,
reports, and mapping formats. Internal task names, plans, receipts, and cache
formats are not public API. Historical Release Evidence Sets and mappings remain
bound to the version and AAB that produced them.

Before upgrading, archive the current reports and mappings, read `CHANGELOG.md`,
and run the new version on a non-production Release build. Unknown schema,
diagnostic, or toolchain failures require investigation rather than suppression.

Publishing uses a small Gradle Plugin Portal workflow from a clean `origin/main`
revision: plugin tests, the public Sample AAB build, `validatePlugins`,
`publishPlugins --validate-only`, and `publishPlugins`. Performance benchmarks,
device runs, extra hosts, signed manifests, SBOM generation, and release dossiers
are optional maintenance work rather than publication blockers. A defective
public version is documented and followed by a new patch version instead of
overwriting an existing Portal version.
