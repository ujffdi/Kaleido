# 29 — Implement Full Profile component generation

**What to build:** A Consumer Project using Full Profile can explicitly request bounded Android component and Manifest generation with deterministic identities, complete class/XML/Manifest rewriting, and no accidental activation when the option is absent.

**Blocked by:** 22 — Generate Safe ordinary Junk Code and resources; 24 — Close Kotlin, XML, and Protection Requirement references.

**Status:** resolved

- [x] Expose component generation only through explicit Full Profile configuration; Safe Profile continues to generate no components.
- [x] Generate bounded deterministic component classes and matching Manifest declarations using the same namespace, seed, collision, and inventory contracts as ordinary generation.
- [x] Generated component references participate in class-family planning, Manifest/XML semantic rewriting, protection closure, and raw/composed mappings.
- [x] Generated components avoid undeclared permissions, exported entry points, intent filters, startup registration, network access, telemetry, and consumer business dependencies unless each behavior is explicitly part of the typed Full configuration.
- [x] Invalid, colliding, incomplete, or unsupported component configuration fails before mutation.
- [x] Release fixtures demonstrate both explicitly configured generated components and their complete absence under Safe or unconfigured Full builds.
- [x] Runtime smoke tests verify that generated declarations do not break application installation or the consumer's normal launch path.

## Answer

Explicit Full Profile `activityCount` now produces bounded deterministic public `Activity` classes under the generated namespace and exact non-exported Manifest declarations. A dedicated component engine validates class/declaration completeness, collision closure, and the inert no-permission/no-entry-point/no-I/O contract before generated outputs are written. The classes flow through the existing PROJECT class-family rewrite, semantic Manifest rewrite, protection, raw mapping, R8 fixed-identity, and composed mapping paths; generated inventory records every original component identity and exported decision. Safe and unconfigured Full fixtures prove absence, configured minified Release fixtures prove mapping and Manifest closure, and collision/invalid-contract fixtures fail closed. The full 73-test suite and `validatePlugins` pass. The checked-in Full runtime fixture installed on the existing `Pixel_9_Pro` AVD and cold-started the rewritten Consumer Launcher Activity with `Status: ok`; both generated Activities were registered as `exported=false`, no class-loading/fatal errors appeared, and the app and emulator were stopped afterward.
