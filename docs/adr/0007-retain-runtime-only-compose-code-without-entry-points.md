---
status: accepted
---

# Retain Runtime-only Compose code without entry points

Kaleido ships Compose Generator in the first public MVP as an explicit AndroidJunkCode-equivalent sub-capability with the same contract under Safe and Full. It accepts only a Consumer Project that already enables the Compose build feature, applies the Compose compiler plugin, and resolves Compose Runtime; Kaleido does not mutate those prerequisites. It generates deterministic Runtime-only internal Composable call graphs under `build/`, creates no UI, Preview, resource, component, navigation, startup, or ordinary Consumer Project call entry, and fails if static validation discovers one. Kaleido inventories compiler-lowered JVM output, applies exact non-shrinking retention rules that still allow R8 renaming and optimization, and proves mapped class/member presence in final Release DEX. This keeps the sub-capability useful as generated hardening content without turning it into a screen generator or widening the Consumer Project dependency surface, while explicitly limiting the guarantee to statically identifiable entry mechanisms rather than claiming absolute runtime unreachability.
