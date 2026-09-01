# 33 — Pass cache, up-to-date, and byte-reproducibility gates

**What to build:** Kaleido demonstrates configuration-cache reuse, correct no-clean up-to-date behavior, portable Consumer-owned build-cache reuse, and byte-identical deterministic evidence across independent workspaces as four separate guarantees.

**Blocked by:** 32 — Publish an atomic Release Evidence Set.

**Status:** resolved

- [x] Classify generation, analysis/protection, plan creation, dictionary/rule generation, class/Manifest rewrite, mapping composition, bundle planning/rewrite, and unsigned deterministic evidence as cacheable only after their complete declared inputs and path independence are proven.
- [x] Keep credential resolution, signing, final validation, publication-evidence assembly, and atomic publication noncacheable.
- [x] Pass a two-run configuration-cache gate with reuse on the second invocation and no eager secret resolution or unsupported captured state.
- [x] Pass a two-run no-clean gate with build cache disabled and deterministic tasks correctly up-to-date on the second invocation.
- [x] Fill and reuse the Consumer Project's Gradle build cache from clean and relocated workspaces without introducing a Kaleido cache endpoint.
- [x] Build identical normalized inputs in three independent workspaces and compare generated trees, plans/receipts, dictionaries/rules, mappings, resource map, unsigned AAB, and deterministic evidence byte for byte.
- [x] Vary absolute path, discovery order, worker count, locale, timezone, and cache hit/miss state without changing deterministic bytes.
- [x] Change one declared input at a time and prove that the affected fingerprint/output changes without contaminating other variants.
- [x] Cache schema changes naturally miss through versioned implementation/schema fingerprints; no private cache migration is introduced.

## Answer

Eight deterministic pipeline tasks are now explicitly cacheable, each with a versioned schema input in addition to Gradle's implementation fingerprint: adoption-plan resolution, generation, semantic XML rewrite, Manifest rewrite, class rewrite, R8 rule generation, mapping composition, and unsigned Bundle rewrite. Credential resolution, signing, final DEX/signature/Bundle validation, evidence assembly, and atomic publication remain noncacheable and execute on every requested build. `KaleidoCachePolicy` and reflection tests keep that classification and the absence of credential surfaces in cacheable tasks machine-checkable.

The functional gates independently prove Configuration Cache reuse with `problems=fail`, second-run no-clean `UP-TO-DATE` reuse with Build Cache disabled, clean same-checkout and relocated-checkout `FROM-CACHE` restoration from a Consumer-configured local cache, and byte equality across three independent workspaces. The clean-byte gate varies absolute path, input creation order, worker count, locale, timezone, and disables Build Cache while comparing the complete deterministic Release Evidence boundary. A flavor-scoped input mutation changes the affected free Release output without changing the paid Release output. Kaleido neither configures a cache endpoint nor migrates private cache records; implementation or explicit schema changes naturally miss. The full 87-test suite has zero failures and `validatePlugins` passes.
