# 27 — Complete the final-AAB resource rewrite tracer

**What to build:** After AGP/R8 produces an ordinary base-module AAB, Kaleido deterministically renames eligible application resource entries and packaged paths as one closed transformation while preserving every numeric resource ID and all code/native payloads.

**Blocked by:** 25 — Integrate deterministic R8 dictionaries and composed mappings.

**Status:** resolved

- [x] Inventory base-module resource-table entries, compiled XML references, payload paths, ZIP entries, ownership, protection state, and collision domains.
- [x] Produce an immutable deterministic `BundleRewritePlan.v1` bound to exact AAB input digests and sorted expected outputs.
- [x] Preserve every numeric resource ID while deterministically renaming only eligible application-owned symbolic entries and resource paths.
- [x] Rewrite resource-table protobuf entries, protobuf references, compiled XML, payload paths, and corresponding ZIP entries consistently.
- [x] Leave dependency-owned and protected resource identities unchanged.
- [x] Byte-preserve DEX entries, native libraries, and code-transparency payloads during the resource stage.
- [x] Emit a canonical resource mapping and `TransformReceipt.v1` that binds plan, inputs, output, and reference-closure validation.
- [x] Reject missing/dangling references, missing/orphaned paths, collisions, unexpected modules, unplanned entries, and preserved-payload drift.
- [x] The transformed unsigned AAB passes bundletool validation for representative Consumer Projects.

Implementation evidence: the final `SingleArtifact.BUNDLE` transform now inventories every ZIP
entry and base `resources.pb` ID/name/config/file edge, application source ownership, explicit and
typed protection, compiled Manifest/resource XML references, path collision domains, and protected
payloads. It encodes and re-decodes one sorted digest-bound `BundleRewritePlan.v1` before execution,
then rewrites AAPT2 9.2.1 entry names, name-bearing references, raw compiled-XML attributes,
`FileReference.path`, and ZIP paths without changing numeric IDs. Root JAR signatures are removed
to produce an unsigned candidate; DEX/native/code-transparency bytes are hash-checked unchanged.
The canonical resource mapping and Bundle `TransformReceipt.v1` bind plan/input/output/mapping
digests and validation counts. Output closure rescans IDs, references, paths, planned ZIP entries,
and protected payloads before bundletool 1.18.1 validation.

Verification: `./gradlew :kaleido-gradle-plugin:test
:kaleido-gradle-plugin:validatePlugins --stacktrace` passes 51 tests with zero failures/errors.
