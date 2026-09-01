# Compose Generator contract evidence

Date: 2026-08-31

Scope: official Android, Kotlin, Compose, and R8 sources for the optional Kaleido Compose Generator. Facts below describe the toolchain; recommendations are explicitly marked as Kaleido policy inferences.

## Decision-ready summary

1. **Do not treat AGP 9 built-in Kotlin as Compose enablement.** AGP 9 compiles Kotlin without `org.jetbrains.kotlin.android`, but official Compose setup still requires the Compose compiler plugin in every module that uses Compose, `buildFeatures.compose = true`, and Compose library dependencies. Kaleido should require all three signals and fail without mutating the Consumer Project.
2. **Generate against Compose Runtime only.** `@Composable` and the compiler-targeted `Composer` API are in `androidx.compose.runtime:runtime`. The compiler/plugin is build tooling; it does not substitute for a runtime library on the compilation/runtime classpath. Runtime-only generated functions avoid assuming Material, Foundation, UI, Activity, Navigation, or tooling dependencies.
3. **Register generated Kotlin with the public variant source API.** For each eligible Release variant, wire the task output through `variant.sources.kotlin!!.addGeneratedSourceDirectory(taskProvider, taskOutput)` under `build/`.
4. **Retain by generated JVM class boundary, not source-level Compose descriptors.** Kotlin top-level functions compile as static methods on a file-facade class, and the Compose compiler adds implicit `Composer` and change-mask parameters. The exact lowered descriptor is compiler-version-dependent. Kaleido should inventory generated JVM classes after compilation and emit rules against those classes and their methods.
5. **Use an R8 keep rule that forbids shrinking but may allow renaming and optimization.** `-keep,allowobfuscation,allowoptimization class <generated-class> { *; }` retains otherwise-unreachable generated classes/members while allowing their names and implementations to be transformed. Omitting `allowshrinking` is essential. Validate the final DEX through the composed mapping rather than expecting source names.
6. **Do not generate `@Preview`.** `@Preview` comes from `androidx.compose.ui:ui-tooling-preview`, and Studio can execute and even deploy preview functions. It adds a toolkit dependency and creates an intentional tooling execution surface, contrary to a retained-but-non-entry-point junk-code contract.
7. **Make the evidence claim narrow.** Compilation, pre-R8 class shape, R8 retention, final-DEX presence, and absence from the Manifest/navigation inputs can be proven. Static analysis cannot prove that arbitrary runtime reflection, native code, downloaded configuration, or third-party code can never invoke a retained method.

## 1. AGP 9 and Compose prerequisites

### Official facts

- AGP 9 enables built-in Kotlin by default, so an Android application module no longer needs `org.jetbrains.kotlin.android` merely to compile Kotlin. Built-in Kotlin replaces only that plugin. [Android Developers: Migrate to built-in Kotlin](https://developer.android.com/build/migrate-to-built-in-kotlin)
- Official Compose setup for Kotlin 2.0+ separately requires applying `org.jetbrains.kotlin.plugin.compose` to each module that uses Compose. The plugin version matches the Kotlin version. [Android Developers: Set up the Compose Compiler Gradle plugin](https://developer.android.com/develop/ui/compose/setup-compose-dependencies-and-compiler)
- The same official setup sets `android.buildFeatures.compose = true` and adds chosen Compose library dependencies. [Android Developers: Set up Compose dependencies](https://developer.android.com/develop/ui/compose/setup-compose-dependencies-and-compiler)
- AGP 9.2 exposes `ApplicationExtension.buildFeatures`, and `BuildFeatures.compose` is a public nullable Boolean whose default is `false`. [AGP 9.2 `ApplicationExtension`](https://developer.android.com/reference/tools/gradle-api/9.2/com/android/build/api/dsl/ApplicationExtension), [AGP 9.2 `BuildFeatures`](https://developer.android.com/reference/tools/gradle-api/9.2/com/android/build/api/dsl/BuildFeatures)

### Kaleido policy inference

When Compose Generator is enabled, validate the current Consumer Project after Android DSL finalization and before variant task execution:

- it is already an AGP 9 Application project with Kotlin compilation available;
- `buildFeatures.compose == true`;
- `org.jetbrains.kotlin.plugin.compose` is applied to that Project; and
- the target Release variant's compile classpath resolves Compose Runtime.

No single signal is sufficient. In particular, built-in Kotlin proves only Kotlin compilation, the Compose build-feature flag does not prove the runtime artifact is resolvable, and a runtime dependency does not prove compiler lowering is configured. Kaleido should diagnose the missing signal and must not apply plugins or add dependencies on the Consumer Project's behalf.

## 2. Minimum compile/runtime dependency

### Official facts

- `androidx.compose.runtime.Composable` is published by artifact `androidx.compose.runtime:runtime`. The annotation changes the type of a function and gives it an implicit composable context. [Compose `Composable` API](https://developer.android.com/reference/kotlin/androidx/compose/runtime/Composable)
- `androidx.compose.runtime.Composer`, also in `androidx.compose.runtime:runtime`, is the interface targeted by the Compose Kotlin compiler plugin and used by compiler-generated helpers. [Compose `Composer` API](https://developer.android.com/reference/kotlin/androidx/compose/runtime/Composer)
- The compiler's design documentation states that `Composer` is passed as an implicit parameter to every composable function and that compiler-generated code inserts calls to it. [AndroidX source: How Composition Works](https://android.googlesource.com/platform/frameworks/support/+/show/refs/heads/androidx-test-uiautomator-release/compose/runtime/design/how-compose-works.md)
- Official setup describes the compiler plugin and Compose library dependencies as separate setup steps. It never states that enabling the compiler supplies the runtime artifact. [Compose compiler/dependencies setup](https://developer.android.com/develop/ui/compose/setup-compose-dependencies-and-compiler)

### Kaleido policy inference

The minimum portable source contract is generated Kotlin that imports only `androidx.compose.runtime` APIs. A deterministic graph of top-level/internal `@Composable` functions may call other generated composables but should not refer to UI, Foundation, Material, Activity Compose, Navigation Compose, or tooling APIs. This makes `androidx.compose.runtime:runtime` the only Compose library Kaleido needs to require; Kaleido does not add it.

The compiler plugin is not a runtime dependency. A project with Compose compilation configured but without a resolved Runtime artifact must fail with an actionable diagnostic rather than relying on an incidental transitive dependency.

## 3. Public AGP registration of generated Kotlin

### Official facts

- AGP tells plugin authors to use public extension points rather than `afterEvaluate`, AGP task instances, guessed task names, or explicit dependencies on AGP tasks. [Android Developers: Write Gradle plugins](https://developer.android.com/build/extend-agp)
- For built-in Kotlin, official AGP migration guidance gives the exact variant API shape for generated Kotlin: `variant.sources.kotlin!!.addGeneratedSourceDirectory(TASK_PROVIDER, TASK_OUTPUT)`. [Android Developers: Migrate to built-in Kotlin](https://developer.android.com/build/migrate-to-built-in-kotlin)
- The generated source directory is determined and wired by AGP from the task output property. [AGP `SourceDirectories` API](https://developer.android.com/reference/tools/gradle-api/9.2/com/android/build/api/variant/SourceDirectories)

### Kaleido policy inference

Use one cacheable, variant-isolated generation task whose declared directory output is under `build/`. Register it only for eligible Release variants through `variant.sources.kotlin`. Do not add the directory to source sets globally, alter `src/`, guess `compile...Kotlin` task names, or add an explicit dependency on compiler tasks.

## 4. Kotlin and Compose compiled shapes

### Official facts

- Kotlin top-level declarations in a file such as `Generated.kt` compile to static methods on a JVM file-facade class named `GeneratedKt` by default; `@file:JvmName` can choose a stable facade name. [Kotlin: Calling Kotlin from Java](https://kotlinlang.org/docs/java-to-kotlin-interop.html#package-level-functions)
- `internal` is a Kotlin module-visibility rule: declarations are visible throughout the same compilation module. It is not a guarantee of a special JVM container shape. [Kotlin visibility modifiers](https://kotlinlang.org/docs/visibility-modifiers.html)
- Compose compiler documentation says each composable receives an implicit Composer context. AndroidX compiler transformation tests show lowered functions with added `Composer` and integer change/default-mask parameters; bytecode tests likewise assert descriptors containing `androidx/compose/runtime/Composer`. [How Composition Works](https://android.googlesource.com/platform/frameworks/support/+/show/refs/heads/androidx-test-uiautomator-release/compose/runtime/design/how-compose-works.md), [AndroidX compiler signature tests](https://android.googlesource.com/platform/frameworks/support/+/a7dfd45bc35277c250306e5957c49c041b5c13f8/compose/compiler/compiler-hosted/integration-tests/src/test/java/androidx/compose/compiler/plugins/kotlin/ComposerParamSignatureTests.kt)

### Kaleido policy inference

Generate deterministic files with an explicit `@file:JvmName` and top-level `internal @Composable` functions. Use internal visibility to avoid a public Kotlin API promise, not as a retention mechanism. Do not encode source-level signatures into R8 rules: Compose compiler versions can change synthetic parameters, masks, wrappers, and helper classes. Inspect the compiled output or use the known generated facade-class inventory, then retain every member of those classes.

Generated functions should have no platform side effects, no parameters sourced from the application, no stateful global initializers, and no references from application Activities, navigation/routes, Manifest components, resources, or startup providers. Calls may exist only inside the generated subgraph.

## 5. R8 retention semantics

### Official facts

- `-keep` preserves matched classes and members and, without modifiers, prevents optimization. `allowoptimization` allows optimization while the elements are not removed; `allowobfuscation` allows renaming; `allowshrinking` allows removal when unused. [Android Developers: Add keep rules](https://developer.android.com/topic/performance/app-optimization/add-keep-rules)
- `keepclassmembers` alone does not retain the containing class; it preserves members only if the class survives. [Add keep rules](https://developer.android.com/topic/performance/app-optimization/add-keep-rules)
- R8 provides `-whyareyoukeeping` to diagnose the path retaining an item. [Android Developers: Troubleshoot optimization rules](https://developer.android.com/topic/performance/app-optimization/troubleshooting-rules)

### Kaleido policy inference

For each post-Kaleido/pre-R8 generated JVM class, emit the semantic equivalent of:

```proguard
-keep,allowobfuscation,allowoptimization class com.example.generated.KldCompose_* { *; }
```

Do **not** include `allowshrinking`. The rule deliberately permits R8 renaming and optimization but requires each generated class and member to survive. It should be generated from exact compiled class inventory rather than a broad user package wildcard. If the final contract requires byte-for-byte or one-source-method/one-DEX-method shape preservation, `allowoptimization` would be too permissive; the current product requirement is retention, not unchanged implementation.

Record the rule provenance and use Kaleido's composed class mapping to locate renamed output in final DEX. A method-name assertion against pre-R8 source names is invalid when `allowobfuscation` is enabled.

## 6. `@Preview` and UI toolkit implications

### Official facts

- `@Preview` belongs to artifact `androidx.compose.ui:ui-tooling-preview`, not Compose Runtime. It applies to parameterless composable methods and its parameters are read by Studio. [Compose `Preview` API](https://developer.android.com/reference/kotlin/androidx/compose/ui/tooling/preview/Preview)
- Official dependency setup lists `ui-tooling-preview` as an implementation dependency and `ui-tooling` as a debug dependency for Android Studio Preview support; these are separate from Runtime and ordinary UI/Foundation/Material choices. [Compose setup](https://developer.android.com/develop/ui/compose/setup-compose-dependencies-and-compiler)
- Android Studio executes preview code through Layoutlib, and “Run Preview” can deploy it to a device as a new Activity in the project app. [Android Developers: Preview your UI](https://developer.android.com/develop/ui/compose/tooling/previews)

### Kaleido policy inference

Exclude `@Preview` entirely. It would require an extra artifact that a valid runtime-only Compose project may not have and would create an IDE/device-invokable tooling surface. Also exclude UI/Foundation/Material nodes from MVP-generated code unless the contract intentionally expands its prerequisite matrix. “Compose Generator” can truthfully mean compiler-lowered composable runtime code; it need not create a visible screen.

## 7. Acceptance evidence and proof limits

### Automatable evidence

For a minified Release fixture with Compose Generator enabled, collect and verify:

1. **Prerequisites:** the effective Compose flag, compiler-plugin presence, and resolved Runtime component/version are recorded without adding or changing dependencies.
2. **Generation:** deterministic file list, content hashes, facade names, function counts, seed domain, and output directory under `build/`; the Consumer Project `src/` tree hash remains unchanged.
3. **Compilation:** generated facade classes appear in the compiled Application-module class artifact and contain compiler-lowered methods referring to `androidx.compose.runtime.Composer`. This proves Compose compilation happened rather than the source merely being emitted.
4. **R8 retention:** exact Kaleido-owned keep rules are present with no `allowshrinking`; R8 mapping/composed mapping resolves every generated facade to its final name; optional `-whyareyoukeeping` fixture output attributes retention to Kaleido's rule.
5. **Final DEX:** parse all `classes*.dex` from the final base-only AAB and confirm the mapped generated classes and expected retained member inventory exist. This proves final retention even when renamed.
6. **No Android entry point:** confirm no generated class appears as an application/component/provider/receiver/service/activity/instrumentation name or intent-filter target in the final merged Manifest; confirm Kaleido emitted no Manifest contribution for Compose.
7. **No declared navigation/tooling surface:** verify generated sources and compiled constant/reference graph contain no Navigation APIs, route strings, `ComponentActivity`/`setContent`, `@Preview`, UI-tooling, or application-class references; verify the generated subgraph has no incoming call edge from non-generated Application classes in the analyzed bytecode.
8. **Reporting:** Artifact Report states enabled/disabled, prerequisite evidence, generated and retained counts, source hashes, generated class mappings, keep-rule provenance, dependency coordinates used, and the scanner scope/limitations.

### What this does not prove

- Absence of a static Manifest/navigation/call edge does not prove that runtime reflection, JNI, downloaded code/configuration, serialization, or a third-party framework can never discover or invoke a retained method.
- DEX presence does not prove that a method is unreachable; it proves only retention.
- Successful compilation does not prove device execution safety or rendering, and no rendering should occur because the generated graph has no supported entry point.
- A dependency scan cannot prove that no transitive artifact contains navigation or reflection behavior.

Therefore the release claim should be: **Kaleido generates compiler-lowered Compose Runtime code, retains its inventoried classes/members in final DEX, and introduces no Kaleido-declared Android, navigation, preview, or ordinary bytecode entry edge.** It must not claim absolute runtime unreachability.
