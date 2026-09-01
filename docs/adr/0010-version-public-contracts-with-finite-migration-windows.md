---
status: accepted
---

# Version public contracts with finite migration windows

Kaleido applies Semantic Versioning to its Adoption Contract, exact Compatibility Matrix, stable diagnostic identity, Artifact Report and Release Evidence Set schemas, and public mapping envelopes while keeping tasks, plans, receipts, and cache formats behind the implementation seam. Each release supports only exact toolchain rows proven by complete fixtures; public removals and support shrinkage require a major after at least one non-preview Bridge Release and 90 days, except when a confirmed security, legal, licensing, or upstream-removal constraint makes continued support unsafe or impossible. Artifact Reports use independent schema versions: one Kaleido major reads every report minor it emits and the next major reads at least the previous report major, while older immutable evidence remains usable through its recorded schema, matching per-release mappings, toolchain, and archived tools rather than indefinite latest-binary compatibility. Internal schema upgrades invalidate cache entries instead of migrating them, and breaking Consumer Project upgrades proceed through a documented Bridge Release without rewriting historical Release Evidence Sets.
