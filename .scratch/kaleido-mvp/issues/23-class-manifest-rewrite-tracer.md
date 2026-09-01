# 23 — Complete the first class and Manifest rewrite tracer

**What to build:** A release build can deterministically rename a representative application-owned Java class, update its semantic Manifest references, pass the rewritten program to R8, and emit a validated original-to-Kaleido mapping through immutable plan and receipt artifacts.

**Blocked by:** 22 — Generate Safe ordinary Junk Code and resources.

**Status:** resolved

- [x] Inventory application-owned and Kaleido-generated class roots while treating dependency and protected classes as non-rewrite targets.
- [x] Produce a deterministic, immutable `ClassRewritePlan.v1` containing exact input digests, mappings, protection decisions, collision decisions, expected outputs, and sorted fields.
- [x] Generate legal deterministic target identities from domain-separated seed material and resolve collisions before mutation.
- [x] Execute exactly the planned mapping after rechecking every input digest; unplanned, missing, drifting, or colliding outputs fail.
- [x] Rewrite representative Java class definitions and structural references plus known Manifest class attributes without arbitrary string replacement.
- [x] Emit a canonical raw original-to-Kaleido mapping and `TransformReceipt.v1` binding plan, inputs, outputs, and validation results.
- [x] Reject unknown plan major versions and close the representative program/Manifest reference graph after execution.
- [x] A real Consumer Project bundles successfully and runtime smoke coverage confirms the renamed Manifest target still resolves.

## Answer

Added a two-stage public-AGP pipeline that avoids the Manifest/class compilation
cycle. A pre-compilation Manifest stage parses only the versioned semantic
registry and emits an immutable rewrite intent. The PROJECT-scoped post-compile
class stage inventories compiled directories/JARs, adds Kaleido-generated roots
and class-family closure, resolves deterministic collision-free identities, and
executes the exact intent plus generated mappings through ASM before R8.

`ClassRewritePlan.v1` and `TransformReceipt.v1` are deterministic protobuf
artifacts. They bind the Adoption Plan, original/transformed Manifest, every
class artifact and class digest, rewrite/protected/untouched decisions, Manifest
sites, expected outputs, plan/output hashes, applied count, digest recheck, and
closure validation. The human raw mapping is separately canonical and sorted.
Unknown plan majors, duplicate/misnamed classes, input drift, missing outputs,
collisions, unsupported targeted Kotlin Metadata, and untouched-byte changes
fail with `KLD-CLASS-001`.

Dependency classes never enter the transform because the seam is
`ScopedArtifacts.Scope.PROJECT`. Exact original-name Protection Requirements
leave both class and Manifest identity unchanged. ASM remaps structured JVM
types and output paths; no arbitrary string replacement is used. The semantic
Manifest registry rewrites component class attributes and deliberately excludes
`activity-alias@name`, generic metadata, and opaque strings.

Verification:

- `./gradlew :kaleido-gradle-plugin:test` passed all 39 tests, including
  canonical protobuf/unknown-major, collision extension, Manifest registry,
  dependency exclusion, protection, final protobuf Manifest, and final DEX
  old/new identity closure.
- `./gradlew :kaleido-gradle-plugin:validatePlugins` passed.
- The checked-in Java runtime-smoke fixture built a signed, minified Release APK
  through the packaged plugin. On the existing `Pixel_9_Pro` AVD it installed
  successfully and cold-started the rewritten Activity with `Status: ok`;
  `topResumedActivity` matched the raw mapping target and logcat contained no
  class-loading, Activity-instantiation, or fatal exception. The fixture was
  uninstalled and the emulator started for the check was stopped afterward.
