# 32 — Publish an atomic Release Evidence Set

**What to build:** Every successful Safe or Full release variant exposes one final signed AAB and one complete canonical Release Evidence Set atomically, while any failed stage exposes neither a new final Bundle nor a partial report.

**Blocked by:** 25 — Integrate deterministic R8 dictionaries and composed mappings; 26 — Implement the Compose Junk Code Generator; 29 — Implement Full Profile component generation; 30 — Implement Full Profile destructive resource controls; 31 — Select signing atomically and verify the final AAB.

**Status:** resolved

- [x] Assemble the ten ordered Hardening Pipeline stages from validation through generation, class/R8 processing, resource transformation, signing, verification, and publication using immutable stage artifacts.
- [x] Emit canonical raw Kaleido, raw R8, composed original-to-final class, and resource mappings with their required metadata and digests.
- [x] Emit one canonical UTF-8/LF Artifact Report v1 with schema, identity, deterministic evidence, publication evidence, diagnostics, and proof limitations.
- [x] Bind generated trees, plans, receipts, dictionaries/rules, mappings, resource map, protected-content proofs, and unsigned AAB into deterministic evidence.
- [x] Bind the exact unsigned digest to signed AAB digest, certificate digest, signature result, bundletool result, optional code-transparency result, and publication result.
- [x] Derive the Release Evidence Set ID from a canonical manifest of variant identity, unsigned/signed AAB digests, mapping digests, deterministic-evidence digest, and certificate digest without time, path, host, or self-hash fields.
- [x] Normalize ordinary paths to project-relative form and redact raw seed, credentials, credential paths, user/host identity, timestamps, durations, worker order, and cache outcomes.
- [x] Validate a complete staging set before atomically exposing the final standard Bundle artifact and evidence together.
- [x] A failed or interrupted build publishes no current report or partial set and does not overwrite a prior successful publication.
- [x] End-to-end Safe, Safe+Compose, and Full fixtures can independently verify every evidence digest and retrace a final identity.

## Answer

The Release pipeline now ends in a noncacheable atomic publication transform. It validates the complete staged variant identity and digest closure, copies every deterministic generated tree/plan/receipt/rule/dictionary/mapping/unsigned AAB under project-relative paths, publishes the four canonical mappings, emits UTF-8/LF Artifact Report 1.0 and manifest files, and derives a path/time/host-independent Release Evidence Set ID from the required digests. Only after the staged tree rehashes exactly does it swap the evidence directory and signed standard Bundle, with prior-output backups and interrupted-publication recovery. The standard `app-<variant>.aab` is now the verified signed candidate rather than the unsigned transform. Safe, Safe+Compose, and Full fixtures independently rehash every included file, recompute the set ID, verify the public signed digest, and Retrace a final identity; a failed replacement preserves the prior Bundle and evidence byte-for-byte. Raw seed, credentials, credential paths, user-home paths, timestamps, durations, worker order, and cache outcomes are absent. The full 76-test suite and `validatePlugins` pass.
