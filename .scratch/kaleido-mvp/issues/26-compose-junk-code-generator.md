# 26 — Implement the Compose Junk Code Generator

**What to build:** A Compose-enabled Consumer Project can explicitly enable bounded deterministic Compose Runtime-only Junk Code that has no UI or entry point, survives R8 in the final DEX, and participates in Kaleido's composed mapping and evidence.

**Blocked by:** 22 — Generate Safe ordinary Junk Code and resources; 25 — Integrate deterministic R8 dictionaries and composed mappings.

**Status:** resolved

- [x] Keep Compose Generator disabled by default and model it as a sub-capability of AndroidJunkCode generation.
- [x] Require the Consumer Project to already provide the Compose build feature, compiler plugin, and resolvable Compose Runtime; Kaleido adds none of them.
- [x] Support `fileCount` from 1 through 64, `functionsPerFile` from 1 through 32, and a total no greater than 512; default enabled values are four and four.
- [x] Generate internal top-level composables in a deterministic, closed, acyclic call graph using only pure computation, local conditionals, and generated-to-generated calls.
- [x] Reject generation or references involving UI, Foundation, Material, Activity Compose, Navigation, tooling, Preview, resources, components, startup, state/effects, coroutines, I/O, logging, networking, reflection, platform APIs, or consumer symbols.
- [x] Record the exact compiled façade/function inventory and Composer-lowered signatures.
- [x] Supply targeted retention that prevents shrinking of the exact generated inventory while allowing optimization and obfuscation, without creating a static runtime entry point.
- [x] Final DEX inspection and raw/composed mappings prove the expected lowered functions remain and that consumer code has no static reference to them.
- [x] Negative fixtures cover missing prerequisites, invalid bounds, forbidden APIs, cycles, consumer references, and any Kaleido-declared runtime entry.

## Answer

The optional generator now emits a deterministic Runtime-only graph into Kotlin generated sources only after all Consumer-owned Compose prerequisites pass. A compiled-bytecode gate rejects forbidden references, cycles, Manifest exposure, and incoming Consumer edges; exact generated members receive no-shrink retention while remaining optimizable and obfuscatable. The composed mapping and a post-R8 DEX receipt bind the final mapped façades and lowered functions to the Adoption Plan. Real Safe and Full minified Consumer fixtures, all negative prerequisite/closure fixtures, the full 67-test suite, and `validatePlugins` pass.
