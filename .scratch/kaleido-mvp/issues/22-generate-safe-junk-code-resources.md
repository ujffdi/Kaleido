# 22 — Generate Safe ordinary Junk Code and resources

**What to build:** A Safe Profile release build deterministically generates bounded ordinary application code and resources, compiles and merges them through standard Android tasks, and introduces no user-enterable Android component or observable startup behavior.

**Blocked by:** 21 — Implement the Safe Profile, DSL, and deterministic seed.

**Status:** resolved

- [x] Safe Defaults v1 generate four packages, four ordinary classes per package, four methods per class, eight layouts, sixteen XML drawables, and thirty-two strings.
- [x] Generated code uses a namespace-derived internal package ending in `kaleido.generated`.
- [x] Generated resource names use a deterministic `kld_` prefix with an eight-character project/variant hash fragment and remain collision-free against consumer and generated resources.
- [x] Generated ordinary output counts are nonzero and every configurable count has a finite validated bound.
- [x] Safe generation adds no Activity, Service, Receiver, Provider, intent filter, startup hook, or other user-enterable component.
- [x] Generated source, resource, and Manifest-fragment inventories are stable, sorted, and independent of time, host, absolute path, discovery order, worker count, or cache outcome.
- [x] Repeating the same normalized build produces byte-identical generated trees; changing the seed changes the intended generated identities without affecting unrelated variants.
- [x] The packaged plugin fixture compiles, minifies, and bundles the generated code/resources through the Consumer Project's ordinary release task.

## Answer

Added one bounded Safe generation task per eligible Release variant. It consumes
only the immutable `AdoptionPlan.v1` and static Consumer resource roots, then
emits ordinary Java, Android resources, an empty application Manifest fragment,
generated R8 keep rules, and a canonical `GeneratedInventory.v1`.

Safe Defaults v1 produce exactly 4 packages, 16 package-private final classes,
64 pure static methods, 8 layouts, 16 XML drawables, and 32 strings under the
namespace-derived `*.kaleido.generated` root. Resource identities use the
variant-specific `kld_<8-hex>_` prefix. Consumer/generated identity collisions
fail closed with `KLD-GENERATION-001` before compilation.

The task registers every output through public AGP 9 Sources APIs. Java,
resources, the generated Manifest, and `.keep` rules therefore flow through the
Consumer's ordinary Java compiler, resource and Manifest mergers, R8, and
Bundle pipeline. The final Bundle gate requires the generation inventory, so a
release cannot silently bypass generation.

Verification:

- `./gradlew :kaleido-gradle-plugin:test` passed all 35 tests, including three
  generator contract tests and 17 packaged Consumer integration tests.
- Repeated TestKit generation with the same Provider seed produced identical
  relative-path/SHA-256 trees; changing the seed changed generated identities
  while preserving exact bounds, and flavored Release inventories remained
  independent.
- A real minified `bundleRelease` compiled and merged the generated Java,
  resources, Manifest, and keep rules before producing the final AAB.
- `./gradlew :kaleido-gradle-plugin:validatePlugins` passed.
