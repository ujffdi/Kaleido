# 30 — Implement Full Profile destructive resource controls

**What to build:** Full Profile users can explicitly request the bounded lossy resource controls promised by the MVP, with plan-first previews, protection enforcement, deterministic output, and failure before mutation whenever safety cannot be proven.

**Blocked by:** 28 — Add resource protection, deduplication, and canonicalization.

**Status:** resolved

- [x] Keep native or metadata deletion, unused-string replacement, and language filtering unavailable in Safe Profile and disabled unless explicitly selected in Full Profile.
- [x] Represent each requested control in the immutable bundle plan with affected inventory, reason, protection decisions, expected output, and exact input digests.
- [x] Reject any requested deletion or replacement that intersects a Protection Requirement, dependency-owned content, native loading reference, unknown metadata contract, or unresolved resource reference.
- [x] Language filtering respects default resources, fallback behavior, configured retained languages, and all structurally referenced values.
- [x] Unused-string replacement operates only on entries proven eligible by the complete modeled reference graph and preserves numeric IDs.
- [x] Native/metadata handling leaves nonselected content and all code-transparency/signing inputs intact.
- [x] Each control produces deterministic evidence identifying affected logical identities without leaking absolute paths or host state.
- [x] Positive and negative Full Profile fixtures remain installable, bundletool-valid, and byte-reproducible at the unsigned boundary.

Implementation evidence: Full-only resource intent remains off by default and Safe Profile rejects
every selected control. Exact native basenames and a closed permitted `META-INF` selector set are
validated and resolved only against Application source ownership; zero matches, packaged-path
protection, signing/code-transparency content, modeled `loadLibrary` strings, and empty targeted ABI
directories fail during planning. Confirmed-unused string replacement now consumes an explicit
file input whose digest and sorted exact names enter the Adoption Plan; only one unprotected,
Application-owned, unreferenced, plain string entry is accepted, all configurations receive the
fixed replacement, and numeric IDs remain unchanged. Language filtering always retains default
configurations, retains configured canonical language tags and their regions, rejects missing
fallbacks or unknown locales, and skips dependency-owned configurations. Every action is a sorted
`ControlDecision` in `BundleRewritePlan.v1` with logical target, reason, input/output digest, and
expected entry/config action; execution revalidates those digests before mutation.

Verification: the Full fixture deletes one selected native library while byte-preserving a
nonselected library, replaces a confirmed-unused string, filters `fr` while retaining default and
`en`, passes output closure and bundletool validation, and produces byte-identical plans and
unsigned AABs on a forced repeated build. Protection-boundary, shared metadata-plan,
load-library-reference, Safe/Profile, malformed/zero-match, and canonical protobuf tests cover the
negative branches. The full plugin suite and validation pass 58 tests with zero
failures/errors/skips; the subsequently added native-loading negative contract test also passes.
