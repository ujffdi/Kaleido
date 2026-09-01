# 24 — Close Kotlin, XML, and Protection Requirement references

**What to build:** Kaleido safely transforms realistic Kotlin/JVM identity families and semantic XML/Manifest references while typed Protection Requirements and bounded Escape Hatches preserve only the declared dimensions and fail on unresolved closure.

**Blocked by:** 23 — Complete the first class and Manifest rewrite tracer.

**Status:** resolved

- [x] Extend structural rewriting across descriptors, generic signatures, annotations, exceptions, stack frames, records, nests, permitted subclasses, modules, bootstrap constants, method handles/types, and Kotlin Metadata.
- [x] Plan nested, synthetic, companion, default-implementation, façade, multifile, lambda, coroutine, and other structurally associated Kotlin/JVM identities as complete families.
- [x] Preserve required `$` family relationships and close every rewritten definition/reference pair.
- [x] Rewrite only registered type-bearing Manifest and XML structures; opaque ordinary strings remain untouched.
- [x] Implement additive Protection Requirement dimensions for reachability, original identity, descriptor closure, runtime attributes, resource name, and packaged path.
- [x] Implement bounded exact-name and prefix Escape Hatches with stable ID, human reason, selected dimensions, finite closure, and evidence output.
- [x] Reject global/raw-ProGuard bypasses, unbounded matchers, zero-match rules, conflicts, ambiguity, and unclosed protection state.
- [x] Unsupported opaque structures may be retained only before mutation when the complete affected closure is proven; otherwise the build fails.
- [x] Release fixtures cover representative Java/Kotlin frameworks, reflection declarations, semantic XML references, protected identities, and negative closure cases.

## Answer

Extended the plan-first class module from the Java tracer to complete supported
JVM and Kotlin identity closure. ASM `ClassRemapper` owns every standard JVM
type-bearing surface while Kotlin's official `kotlin-metadata-jvm:2.4.10` API
strictly reads, structurally updates, writes, and reparses class, file facade,
multifile, synthetic/lambda, type, signature, nested, companion, and sealed
metadata. `$` families and Kotlin-declared associations are closed before name
allocation; malformed or unsupported targeted metadata fails before class
mutation.

Added a source-preserving semantic XML overlay task. It parses only the fixed
custom View, Fragment, Navigation, Data Binding, `tools:context`, behavior, and
layout-manager registry, writes changed copies under `build/`, and registers
them through public AGP Sources APIs. Its immutable intent joins Manifest sites
in the same class plan. Opaque attributes and ordinary strings are never
rewritten; the final AAB fixture proves the compiled XML element changed while
an identical `android:tag` string remained unchanged.

The public Protection Interface now exposes type-specific class/resource
Escape Hatches with stable IDs, exact or bounded-prefix selectors, nonblank
reasons, and independent reachability, original-identity, descriptor,
runtime-attribute, resource-name, and packaged-path dimensions. Declarations
normalize into the Adoption Plan. Class matching and descriptor closure produce
canonical plan evidence and generated `.keep` rules consumed by R8. Exact
`Class.forName`/`ClassLoader.loadClass` literals and managed native declarations
infer minimal protection. Resource declarations are resolved against Consumer
and generated inventories for the later Bundle stage.

Global/raw/invalid selectors, missing declarations, duplicate IDs, zero-match
class/resource rules, ambiguous XML overlays, target/mapping drift, and
`tools:discard` conflicts fail with `KLD-PROTECTION-001`. Protection remains
additive and cannot admit unsupported topology.

Verification:

- `./gradlew :kaleido-gradle-plugin:test` passed all 45 tests, including 24
  packaged Consumer tests for built-in Kotlin companion/data-class metadata,
  semantic compiled XML closure, exact/prefix protection, reflection/JNI
  inference, R8 rules, dependency exclusion, opaque-string preservation, and
  fail-closed negative declarations.
- `./gradlew :kaleido-gradle-plugin:validatePlugins` passed.
