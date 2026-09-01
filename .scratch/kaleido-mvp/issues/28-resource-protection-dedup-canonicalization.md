# 28 — Add resource protection, deduplication, and canonicalization

**What to build:** The unsigned hardened AAB becomes a byte-reproducible artifact whose protected resources remain unchanged and whose only deduplication is bounded to byte-identical compatible payloads without merging logical resource identities.

**Blocked by:** 27 — Complete the final-AAB resource rewrite tracer.

**Status:** resolved

- [x] Apply resource-name and packaged-path Protection Requirements and Escape Hatches to bundle planning and validation.
- [x] Hash protected names, paths, attributes, references, and bytes before and after transformation and fail on any prohibited drift.
- [x] Deduplicate only byte-identical, format-compatible, unprotected base-module resource payloads.
- [x] Never merge resource IDs, delete logical table entries, cross ownership/protection boundaries, or combine incompatible qualified resources.
- [x] Retain incompatible or protected duplicates separately and emit only the stable warning allowed by the diagnostics contract.
- [x] Canonicalize rewritten protobuf serialization, ZIP entry order, timestamps, compression policy, archive comment, and extra fields.
- [x] Remove stale signing material only as part of creating the unsigned canonical candidate and leave code/native/transparency payloads unchanged.
- [x] Repeated independent builds of the same normalized input produce byte-identical unsigned AABs and resource mappings.
- [x] Adversarial fixtures cover collisions, qualifiers, aliases, styles, compiled XML, identical payloads, incompatible payloads, protected resources, and malformed closure.

Implementation evidence: `BundleRewritePlan.v1` now carries independent resource-name and
packaged-path protection dimensions and the final receipt records canonical before/after hashes
for protected identities, paths, raw XML attributes, name-bearing references, and preserved
payload bytes. Source inventory adds public resources and finite `tools:keep` patterns, while the
pre-R8 class transform emits exact `Resources.getIdentifier` protection evidence. Deduplication is
limited to byte-identical payloads with the same directory/qualifier, AAPT2 representation,
suffix, size, digest, application ownership, and no protection boundary; it redirects physical
file references without merging IDs or deleting logical entries. Shared-path aliases are treated
as references to one payload, protected and incompatible duplicates remain separate with stable
`KLD-RESOURCE-002` evidence, and ordinary raw XML is no longer mistaken for protobuf XML.
Canonical protobuf/ZIP output fixes ordering, timestamps, compression, comments, and extras,
removes stale root signatures, and hash-checks DEX/native/code-transparency payloads unchanged.

Verification: `./gradlew :kaleido-gradle-plugin:test
:kaleido-gradle-plugin:validatePlugins --rerun-tasks` passes 56 tests with zero
failures/errors/skips. Targeted fixtures cover explicit and automatic protection, qualifiers,
styles and compiled XML, compatible and protected duplicates, shared physical-path aliases,
dangling closure, bundletool validation, and two independent normalized builds whose unsigned
AABs and resource mappings are byte-identical.
