# 21 — Implement the Safe Profile, DSL, and deterministic seed

**What to build:** Applying Kaleido without configuration gives each supported release variant the versioned Safe Defaults v1, while typed provider-based configuration can select product intent and supply a deterministic seed without leaking or eagerly resolving it.

**Blocked by:** 20 — Validate the supported adoption topology.

**Status:** resolved

- [x] Expose typed public sections for profile, seed provider, generation, resources, protection, and signing without exposing engine instances, task objects, or arbitrary callbacks.
- [x] Resolve an empty configuration to Safe Profile and Safe Defaults v1 for each eligible variant.
- [x] Represent Full Profile as an explicit selection that unlocks controls but does not weaken validation.
- [x] Derive the default seed from normalized application ID, complete variant identity, and a versioned derivation identifier.
- [x] Accept an explicit lazy seed provider and resolve it only when the consuming work executes.
- [x] Normalize seed text with Unicode NFC and UTF-8, fingerprint it with SHA-256, and derive domain-separated capability streams.
- [x] Never emit raw seed text in diagnostics, reports, serialized configuration state, task outputs, or cache keys.
- [x] Reject invalid and out-of-range configuration with stable diagnostics before mutation.
- [x] Configuration-cache tests prove that no Project, task, variant, resolved file collection, or secret-bearing object is captured.

## Answer

Added one typed public `kaleido {}` Interface with Profile, Provider-only seed,
generation and nested Compose, resources, protection, and signing declarations.
The public surface exposes no Project, Task, AGP variant, engine instance, raw
task wiring, or product callback.

Each eligible Release variant now resolves one canonical
`AdoptionPlan.v1` immediately before Kaleido's Bundle gate. Empty DSL resolves
Safe Defaults v1 exactly: namespace-derived generation package, 4 packages,
4 classes per package, 4 methods per class, 8 layouts, 16 drawables, 32
strings, no Activities, and Compose disabled. FULL only unlocks explicitly
selected Full-only controls and passes the same range/topology validation.

The default root fingerprint is versioned over NFC-normalized application ID
and complete variant identity. An explicit Provider remains unresolved until
the plan task input is queried. Only the SHA-256 fingerprint enters task
inputs and the canonical plan; five capability streams and the resource
prefix use versioned domain separation. Missing, blank, illegal, out-of-range,
and Safe/Full-conflicting declarations fail with stable `KLD-CONFIG-001`
diagnostics before a plan or final AAB is written.

Verification:

- `./gradlew :kaleido-gradle-plugin:test` passed 31 tests (16 packaged
  Consumer integration tests), including NFC equivalence, domain separation,
  lazy environment Provider resolution, raw-seed absence, exact defaults,
  FULL gates, and invalid DSL.
- A two-run real Consumer `--configuration-cache` fixture reused the stored
  entry while a Provider-backed signing secret remained unconsumed.
- `./gradlew :kaleido-gradle-plugin:validatePlugins` passed.
