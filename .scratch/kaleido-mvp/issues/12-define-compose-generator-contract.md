# Define the optional Compose Generator sub-capability contract

Type: grilling
Status: resolved
Blocked by: 05, 06, 13

## Question

Within AndroidJunkCode-equivalent generation, when explicitly enabled, what Compose source shapes, resources, dependencies, retention rules, reachability, configuration inputs, unsupported-project diagnostics, and Artifact Report evidence does the Compose Generator guarantee without introducing unintended user-visible runtime behavior?

## Answer

Compose Generator is a disabled-by-default, explicitly enabled sub-capability of AndroidJunkCode-equivalent generation with the same contract under Safe and Full. Enabling it requires `buildFeatures.compose == true`, the Compose compiler plugin applied to the Consumer Project, and Compose Runtime resolvable on each target Release compile classpath. Kaleido diagnoses every missing prerequisite and never applies a plugin, changes compiler configuration, or adds a dependency on the Consumer Project's behalf.

Its complete public surface is:

```kotlin
kaleido {
    generation {
        compose {
            enabled.set(true)
            fileCount.set(4)
            functionsPerFile.set(4)
        }
    }
}
```

The block inherits the generation package base and root seed. `fileCount` accepts `1..64`, `functionsPerFile` accepts `1..32`, and their product may not exceed `512`; defaults are four and four. Invalid declarations fail without clamping. Templates, imports, dependency coordinates, graph depth, per-variant behavior, callbacks, and UI/navigation/component configuration are not exposed.

For every eligible Release variant, Kaleido registers an isolated generated Kotlin directory under `build/` through the public AGP variant source API and uses package `<generation.packageBase>.compose`. The contract version, root seed, and exact variant identity deterministically derive file names, explicit JVM facade names, internal top-level `@Composable` function names, a closed acyclic call graph, and source content. Identical declared inputs and toolchain reproduce identical source hashes, and the Consumer Project's `src/` tree remains unchanged.

Generated source imports only Compose Runtime. Bodies may contain pure computation, conditional branches, and calls within the generated graph; they contain no Compose UI/Foundation/Material/Activity/Navigation/tooling APIs, `@Preview`, Android resources, Manifest contributions, components/routes, state/effect APIs, coroutines, I/O, logging, networking, reflection, Android platform calls, Consumer Project symbols, or other startup/runtime entry mechanisms. Full does not unlock any broader Compose behavior.

After compilation, Kaleido inventories the actual generated JVM facades, logical functions, and methods with Compose `Composer` lowering instead of treating compiler-specific synthetic descriptors as stable API. It emits exact Kaleido-owned R8 rules that omit `allowshrinking` while permitting obfuscation and optimization, then resolves every inventoried class and member through the composed mapping and verifies that each remains in final Release DEX. Any ordinary incoming bytecode edge, exact generated-symbol reference, Manifest/Navigation/Preview/startup reference, incomplete lowering or mapping, or missing final class/member fails; an Escape Hatch cannot weaken these rules.

Failures occur at the earliest reliable stage: Adoption Plan finalization handles build-feature, compiler-plugin, and scale errors; execution-time classpath resolution handles missing Runtime; compiled-artifact validation handles generation/lowering; R8 and final-artifact validation handle mappings, forbidden entries, and DEX retention. All are hard failures.

The Artifact Report records enablement, resolved scale, prerequisite evidence, Runtime component/version, relative generated paths and hashes, JVM facade/logical-function inventory, lowering evidence, retention-rule provenance/hash, composed mappings, final DEX locations and retained counts, forbidden-entry scan results, and proof limitations, without embedding complete generated source. Contract tests cover Safe and Full minified Release success, every missing prerequisite, all scale boundaries including `512` success and `513` failure, repeated-input source-hash identity, forbidden incoming edges, post-R8 retention under renaming/optimization, and absence from Manifest, Navigation, Preview, resources, and startup entries.

The bounded public guarantee is: Compose Generator produces deterministic Compose Runtime-only code lowered by the Compose Compiler; Kaleido retains every inventoried class and member in final Release DEX and creates no statically identifiable Kaleido Android, Navigation, Preview, or ordinary bytecode entry point. It does not claim that static analysis proves arbitrary Consumer Project reflection, JNI, downloaded code/configuration, or third-party runtime behavior can never invoke a retained symbol.

See [`ADR-0007`](../../../docs/adr/0007-retain-runtime-only-compose-code-without-entry-points.md).

## Comments

- Product decision: generated Compose code must be retained in the final Release DEX through Kaleido-owned retention rules, while remaining unreachable from Activities, navigation, routes, and runtime call paths.
- Product decision: enabling the Compose Generator requires an already Compose-enabled Consumer Project. If that prerequisite is absent, Kaleido fails the build with an actionable diagnostic and does not add Compose plugins, compiler configuration, or dependencies automatically.
- Research evidence: [`compose-generator-contract-evidence.md`](../research/compose-generator-contract-evidence.md) confirms that AGP 9 built-in Kotlin alone does not enable Compose; the module still needs the Compose build feature, Compose compiler plugin, and a resolvable Compose Runtime dependency. It also establishes the public generated-Kotlin variant API, Compose lowering shape, R8 keep semantics, Preview dependency/execution surface, and the limits of static reachability proof.
- Decision round 1: enablement requires `buildFeatures.compose == true`, the Compose compiler plugin on the Consumer Project, and Compose Runtime resolvable on the target Release compile classpath. Kaleido reports each missing prerequisite and never applies plugins or adds compiler configuration or dependencies on the Consumer Project's behalf.
- Decision round 1: generated source depends only on `androidx.compose.runtime`, uses explicitly named JVM facades and top-level `internal @Composable` functions, and forms a deterministic closed generated call graph. It does not assume Compose UI, Foundation, Material, Activity, or Navigation artifacts.
- Decision round 1: the generator emits no `@Preview`, Activity, route, Manifest contribution, Android resource, startup component, or side-effecting API use. Generated functions may call only inside the generated subgraph and must have no ordinary Consumer Project call edge.
- Decision round 1: Kaleido inventories the post-compilation generated JVM classes and emits exact retention rules that omit `allowshrinking` while allowing obfuscation and optimization. Every inventoried generated class and member must resolve through the composed mapping and remain present in final Release DEX.
- Decision round 1: the evidence claim is deliberately bounded: Kaleido guarantees no Kaleido-declared Manifest, Navigation, Preview, or ordinary bytecode entry edge, but does not claim that static analysis can prove absolute runtime unreachability against arbitrary reflection, JNI, downloaded configuration/code, or third-party behavior.
- Decision round 2: the public `generation { compose { ... } }` block exposes only `enabled`, `fileCount`, and `functionsPerFile`. It inherits the generation package base and root seed; templates, dependency coordinates, imports, call-graph depth, per-variant settings, and executable callbacks are not public inputs.
- Decision round 2: enabling Compose uses defaults of four generated files and four Composable functions per file. `fileCount` accepts `1..64`, `functionsPerFile` accepts `1..32`, and the product of both may not exceed `512`; invalid or excessive declarations fail rather than clamp.
- Decision round 2: each Release variant receives an isolated generated-source directory under `build/` and package `<generation.packageBase>.compose`. File names, explicit JVM facade names, function names, call graph, and source content derive from the root seed, exact variant identity, and contract version; identical declared inputs and toolchain must reproduce identical source hashes, and Consumer Project `src/` remains untouched.
- Decision round 2: Kaleido owns a fixed acyclic generated call-graph algorithm. Generated bodies may contain pure computation, conditional branches, and calls inside the generated graph, but no state/effect APIs, coroutines, I/O, logging, networking, reflection, Android platform calls, or Consumer Project symbol references.
- Decision round 2: any ordinary non-generated bytecode call edge, exact generated-symbol reference, Manifest/Navigation reference, Preview surface, or startup entry involving generated Compose code fails. An Escape Hatch cannot weaken this contract; runtime mechanisms that static analysis cannot exclude remain explicitly outside the proof claim.
- Decision round 2: Safe and Full use the same Compose Generator contract, defaults, and limits. Neither Profile enables it implicitly, and Full does not unlock Compose Activities, pages, resources, navigation, or broader dependencies.
- Decision round 3: Compose failures occur at the earliest reliable stage. Adoption Plan finalization rejects a disabled Compose build feature, missing compiler plugin, or invalid scale; execution-time classpath resolution rejects missing Compose Runtime; compiled-artifact validation rejects absent code or lowering; R8/final-artifact validation rejects incomplete mapping, missing retained classes or members, or forbidden entry references. Every case is a hard failure rather than a warning.
- Decision round 3: the Compose section of the Artifact Report records enablement, resolved scale, prerequisite results, resolved Runtime component/version, relative generated-source paths and hashes, JVM facade and logical-function inventory, lowering evidence, retention-rule provenance/hash, composed mappings, final DEX locations and retained counts, forbidden-entry scan results, and proof limitations. It does not embed complete generated source.
- Decision round 3: every logical generated function must correspond to an actually compiled JVM method with Compose `Composer` lowering, but compiler-specific synthetic parameters and exact descriptors are not a stable public contract. Kaleido inventories actual compiled output and verifies the corresponding post-R8 members.
- Decision round 3: contract tests cover Safe and Full minified Release success; separate missing build-feature/compiler-plugin/Runtime failures; default, lower, upper, `512`, and `513` scale boundaries; repeated-input source-hash determinism; rejection of ordinary incoming symbol edges; final-DEX retention through R8 rename/optimization; and absence from Manifest, Navigation, Preview, resources, and startup entries.
- Decision round 3: the public guarantee is: Compose Generator produces deterministic code that depends only on Compose Runtime and is lowered by the Compose Compiler; Kaleido keeps its inventoried classes and members in final Release DEX and creates no statically identifiable Kaleido Android, Navigation, Preview, or ordinary bytecode entry point. This guarantee does not claim that arbitrary Consumer Project runtime mechanisms can never dynamically invoke those symbols.
