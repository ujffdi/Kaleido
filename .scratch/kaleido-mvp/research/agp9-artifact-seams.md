# AGP 9 supported artifact seams for Kaleido

## Scope and baseline

This report answers which **public** Android Gradle Plugin APIs can place Kaleido's work into an AGP 9 Release AAB build without editing a Consumer Project's checked-in sources. The repository baseline inspected for this ticket is AGP 9.2.1 with Gradle 9.4.1. API conclusions below therefore target the AGP 9.2 API surface, not an older Transform API or internal AGP task classes.

The supported entry point is `ApplicationAndroidComponentsExtension`: select Release variants, use `beforeVariants` only for decisions that alter task creation/build flow, then use `onVariants` to lazily wire sources, tasks, and artifacts. AGP explicitly says that artifact steps belong in `Component.artifacts`, and that `Provider` values must not be eagerly read during configuration ([AndroidComponentsExtension](https://developer.android.com/reference/tools/gradle-api/9.2/com/android/build/api/variant/AndroidComponentsExtension), [Component](https://developer.android.com/reference/tools/gradle-api/9.2/com/android/build/api/variant/Component)).

## Answer at a glance

| Need | Public AGP 9.2 seam | Position in build | Important boundary |
|---|---|---|---|
| Generate Java/Kotlin source | `variant.sources.java/kotlin.addGeneratedSourceDirectory(...)` | Before compilation | Generated directory is a variant source overlay; use a task output under `build/`, not `src/` |
| Generate Android resources/assets | `variant.sources.res/assets.addGeneratedSourceDirectory(...)` | Before merge/AAPT2 | Adds or overlays project sources; it is not a dependency-inclusive merged-resource transform |
| Generate Manifest declarations | `variant.sources.manifests.addGeneratedManifestFile(...)` | Before Manifest merge | Generated manifest has highest priority |
| Rewrite final merged Manifest | `SingleArtifact.MERGED_MANIFEST` + `wiredWithFiles(...).toTransform(...)` | After Manifest merge, before packaging | Supported for manifest/XML changes that must see the merged result |
| Instrument methods/classes without changing class identity | `variant.instrumentation.transformClassesWith(...)` | After compilation, before dex/R8 consumption | Per-class ASM visitor; suitable for instrumentation, not a whole-program class/file renamer |
| Transform or rename the complete class artifact | `variant.artifacts.forScope(PROJECT/ALL).use(task).toTransform(ScopedArtifact.CLASSES, ...)` | After compilation, before dex/R8 | Task receives jars and directories and must merge them into one output jar correctly |
| Add R8 rules and dictionaries | `variant.proguardFiles.add(provider)` and/or generated `variant.sources.keepRules` directory | Before R8 | `proguardFiles` can be appended but not queried during configuration; `keepRules` is incubating in AGP 9.2 |
| Consume R8 mapping | `SingleArtifact.OBFUSCATION_MAPPING_FILE` via `get` or `toListenTo` | After R8 | Optional artifact: not produced when minification is off; transforming it cannot change R8's already-completed naming |
| Transform final AAB | `SingleArtifact.BUNDLE` + `wiredWithFiles(...).toTransform(...)` | **After AGP finalizes/signs the AAB** | Any byte-changing transform invalidates the existing JAR signature and must produce a newly valid final signature |
| Validate/report automatically | Artifact transform as an in-chain gate, or `toListenTo(...)` as an automatic finalizer | At selected artifact boundary | `toListenTo` reacts to production without requiring a new user command; it is a finalizer, not an upstream transform |

## 1. Generated source, resources, and Manifest fragments

`Sources` exposes Java, Kotlin, Android resources (`res`), Java resources, assets, JNI libraries, shaders, ML models, AIDL, baseline profiles, Manifest files, and the new keep-rules source type. `SourceDirectories.addGeneratedSourceDirectory` takes a `TaskProvider` and one of that task's `DirectoryProperty` outputs; AGP chooses the conventional output location and establishes the task dependency. Generated directories are added at the variant overlay with the highest priority ([Sources](https://developer.android.com/reference/tools/gradle-api/9.2/com/android/build/api/variant/Sources), [SourceDirectories](https://developer.android.com/reference/tools/gradle-api/9.2/com/android/build/api/variant/SourceDirectories)). The official recipe demonstrates exactly this wiring and requires modeled task inputs/outputs for correct up-to-date and cache behavior ([addGeneratedSourceFolder recipe at the inspected commit](https://github.com/android/gradle-recipes/tree/5e822e2c5e02e3f3ff6ef3ec99dd30eb4b555c27/addGeneratedSourceFolder)).

Consequences for this research question:

- Kaleido can generate Java/Kotlin classes and Android resources entirely under a task-owned build directory. No copy into `src/main` is needed.
- Generated `res` is a valid way to add new values, drawables, layouts, and similar inputs before AAPT2. Because it is the highest-priority overlay, it can also replace a known project resource with a generated version.
- `Sources.res` models the Consumer Project's source directories, not the already merged, dependency-inclusive resources. It therefore does not by itself expose every XML/resource brought by AAR dependencies.
- A task that tries to consume `sources.res.all` and then registers its own generated output back into that same collection risks a circular dependency. `static` avoids consuming generated outputs, but then necessarily omits resources from other generators. A whole-resource rewriter needs a different seam or an explicitly constrained input set.
- Manifest generation has a dedicated file API: `variant.sources.manifests.addGeneratedManifestFile(taskProvider, outputProperty)`. This is the clean seam for generated Activity/provider/service declarations in Full Profile because the generated Manifest participates in normal merging and gets highest priority ([ManifestFiles](https://developer.android.com/reference/tools/gradle-api/9.2/com/android/build/api/variant/ManifestFiles)).

## 2. Merged Manifest and ordinary XML resources are different seams

AGP exposes the final merged application Manifest as transformable `SingleArtifact.MERGED_MANIFEST`. A task with an input and output `RegularFileProperty` can be wired through `wiredWithFiles(...).toTransform(...)`; downstream AGP tasks receive the transformed Manifest. Google's recipe uses this exact mechanism after standard Manifest merging and then validates the result ([transformManifest recipe](https://github.com/android/gradle-recipes/tree/5e822e2c5e02e3f3ff6ef3ec99dd30eb4b555c27/transformManifest), [SingleArtifact](https://developer.android.com/reference/tools/gradle-api/9.2/com/android/build/api/artifact/SingleArtifact)). This is the supported location for rewriting Manifest class names after all Consumer Project and dependency manifests have been merged.

AGP 9.2's public `SingleArtifact`, `MultipleArtifact`, and `ScopedArtifact` sets contain no `MERGED_RES`, linked-resources, compiled-resources, or resource-table artifact. The public resource choices are therefore:

1. operate before merge/AAPT2 through generated source overlays;
2. limit transformation to the separately exposed merged Manifest;
3. operate on the final `SingleArtifact.BUNDLE`, where resources and compiled XML are already packaged;
4. use AGP internal artifacts/tasks, which is outside this ticket's definition of a supported seam and would couple the plugin to implementation details.

This means complete XmlClassGuard-style rewriting of all merged layout XML and AabResGuard-style rewriting of the compiled resource table cannot be placed at a public, pre-signing merged-resource seam in AGP 9.2. Source overlays can cover controlled project resources; a dependency-inclusive or compiled-resource rewrite reaches the public API only at the final AAB.

## 3. Class transformation

AGP provides two public mechanisms with different shapes.

### ASM instrumentation

`variant.instrumentation.transformClassesWith(factory, scope, parameters)` runs a registered `AsmClassVisitorFactory` on either project classes or project plus dependency classes. Parameters are Gradle inputs and must be annotated accordingly; the factory must be serializable and safe for asynchronous calls ([Instrumentation](https://developer.android.com/reference/tools/gradle-api/9.2/com/android/build/api/variant/Instrumentation), [AsmClassVisitorFactory](https://developer.android.com/reference/tools/gradle-api/9.2/com/android/build/api/instrumentation/AsmClassVisitorFactory), [InstrumentationParameters](https://developer.android.com/reference/tools/gradle-api/9.2/com/android/build/api/instrumentation/InstrumentationParameters)). The official ASM recipe shows the intended integration ([asmTransformClasses](https://github.com/android/gradle-recipes/tree/5e822e2c5e02e3f3ff6ef3ec99dd30eb4b555c27/asmTransformClasses)).

This API is a good fit for adding/removing instructions, methods, annotations, or other changes that preserve the identity and output location of each visited class. It is not a whole-program file graph API: the factory is invoked per class, and no public callback controls the output path for a renamed class. A class-renaming feature that must rename the class entry and update all bytecode references should not be based solely on this seam.

### Scoped class artifact transformation

`ScopedArtifact.CLASSES` is the complete set of `.class` directories and jars used to make dex files. `Scope.PROJECT` excludes imported projects and external dependencies; `Scope.ALL` includes them. `ScopedArtifactsOperation.toTransform` supplies both jar and directory inputs and requires the task to combine them into one output `RegularFile`; the API warns that the task owns duplicate/META-INF merge behavior ([ScopedArtifact](https://developer.android.com/reference/tools/gradle-api/9.2/com/android/build/api/artifact/ScopedArtifact), [ScopedArtifacts](https://developer.android.com/reference/tools/gradle-api/9.2/com/android/build/api/variant/ScopedArtifacts), [ScopedArtifactsOperation](https://developer.android.com/reference/tools/gradle-api/9.2/com/android/build/api/variant/ScopedArtifactsOperation)). Google's transform recipe confirms that these are the classes later used to create dex files ([transformAllClasses](https://github.com/android/gradle-recipes/tree/5e822e2c5e02e3f3ff6ef3ec99dd30eb4b555c27/transformAllClasses)).

This is the public seam capable of a coordinated class/file remap. The transformation task, rather than AGP, must preserve or deliberately resolve:

- class-entry paths and every bytecode type/member reference;
- Kotlin metadata and other class metadata affected by renaming;
- service descriptors and relevant Java resources (`ScopedArtifact.JAVA_RES` is separate);
- duplicate entries and `META-INF` policy when combining inputs;
- scope semantics—`PROJECT` cannot rewrite references stored only in dependencies, while `ALL` rewrites third-party inputs as part of this application build.

Manifest and resource references are outside `ScopedArtifact.CLASSES`, so a class rename is safe only when its corresponding Manifest/XML/string/reflection handling is coordinated through their own seams.

## 4. R8 rules, dictionaries, and mapping

AGP 9.2 exposes two ways to supply rules:

- `Variant.proguardFiles` is a `ListProperty<RegularFile>`. It is initialized from the Android DSL; it cannot be queried during configuration, but a plugin may append a provider-backed generated file ([Variant.proguardFiles](https://developer.android.com/reference/tools/gradle-api/9.2/com/android/build/api/variant/Variant#proguardFiles)).
- `Sources.keepRules` is an incubating `SourceDirectories.Flat` in AGP 9.2, so a generated keep-rule directory can be registered through the same task-output source API ([Sources](https://developer.android.com/reference/tools/gradle-api/9.2/com/android/build/api/variant/Sources)).

R8's own parser recognizes `-obfuscationdictionary`, `-classobfuscationdictionary`, `-packageobfuscationdictionary`, `-applymapping`, and `-printmapping` directives ([R8 `ProguardConfigurationParser`](https://r8.googlesource.com/r8/+/refs/heads/main/src/main/java/com/android/tools/r8/shaking/ProguardConfigurationParser.java#453)). Therefore a deterministic dictionary generator can expose a task-owned rule file plus its dictionary inputs through either supported AGP rule seam. Determinism still depends on modeling every seed/configuration value and upstream input; the presence of a dictionary directive alone does not prove byte-for-byte reproducibility.

The produced mapping is public `SingleArtifact.OBFUSCATION_MAPPING_FILE`. It can be consumed through `artifacts.get(...)` or automatically observed through `toListenTo(...)`. AGP notes that minification artifacts are not always produced; a listener is not invoked when the artifact does not exist ([OutOperationRequest.toListenTo](https://developer.android.com/reference/tools/gradle-api/9.2/com/android/build/api/artifact/OutOperationRequest)). `ApplicationVariant.isMinifyEnabled` is the finalized per-variant fact a plugin can use when choosing whether mapping-dependent behavior applies ([CanMinifyCode](https://developer.android.com/reference/tools/gradle-api/9.2/com/android/build/api/variant/CanMinifyCode)).

Although `OBFUSCATION_MAPPING_FILE` is marked transformable, a post-R8 mapping-file transform changes only the published text artifact. It cannot retroactively alter names already written into dex. Naming inputs must enter through R8 rules (`proguardFiles`/`keepRules`); mapping consumption and verification happen after R8.

## 5. Bundle transformation and signing order

`SingleArtifact.BUNDLE` is documented as “the final Bundle ready for consumption at Play Store” and is publicly `Transformable` ([SingleArtifact.BUNDLE](https://developer.android.com/reference/tools/gradle-api/9.2/com/android/build/api/artifact/SingleArtifact.BUNDLE)). `wiredWithFiles(input, output).toTransform(SingleArtifact.BUNDLE)` therefore gives a normal task an automatic place in the bundle artifact chain, and downstream consumers see its output.

The crucial ordering fact is that the initial producer of this public artifact is AGP's `FinalizeBundleTask`. The AGP source describes that task as applying final touches including signing, performs code-transparency signing when configured, signs/compresses the intermediary bundle, and then registers `finalBundleFile` as `SingleArtifact.BUNDLE` ([AGP `FinalizeBundleTask`](https://android.googlesource.com/platform/tools/base/+/refs/tags/studio-2026.1.2/build-system/gradle-core/src/main/java/com/android/build/gradle/internal/tasks/FinalizeBundleTask.kt#61)). Consequently:

```text
internal intermediary bundle
  -> AGP FinalizeBundleTask (code transparency, compression, upload-key signing)
  -> public SingleArtifact.BUNDLE
  -> third-party BUNDLE transform(s)
  -> final public BUNDLE seen by listeners/consumers
```

A byte-changing `BUNDLE` transform is therefore post-signing. Since app bundles are signed as JARs with the upload key, changing signed entries makes the prior signature no longer validate; Android's publishing guidance requires the upload-signed bundle submitted to Play ([Android app signing](https://developer.android.com/studio/publish/app-signing)). A final-AAB resource rewriter must either produce a correctly re-signed output itself or arrange for signing after its transform. AGP 9.2 exposes no public `INTERMEDIARY_BUNDLE` artifact that permits a third-party transform immediately before `FinalizeBundleTask`.

The public variant-side `SigningConfig` exposes signing-scheme switches but not keystore credentials; the Android DSL `SigningConfig` does expose store file/password/alias/password. A plugin can observe/configure DSL state through `finalizeDsl`, but taking credentials into a post-bundle task changes signing ownership and has configuration-cache/security consequences ([variant SigningConfig](https://developer.android.com/reference/tools/gradle-api/9.2/com/android/build/api/variant/SigningConfig), [DSL SigningConfig](https://developer.android.com/reference/tools/gradle-api/9.2/com/android/build/api/dsl/SigningConfig), [DslLifecycle](https://developer.android.com/reference/tools/gradle-api/9.2/com/android/build/api/variant/DslLifecycle)). This report establishes the seam and ordering; it does not choose Kaleido's signing-ownership policy.

## 6. Automatic validation and Artifact Report wiring

There are three public wiring patterns, each with different semantics:

1. `artifacts.get(type)` supplies a lazy provider and automatically depends on that artifact's producer, but the consuming task still needs some reason to be scheduled.
2. A transform is in the producer chain. A validation transform can fail before publishing its output; if it copies an already signed AAB byte-for-byte, the signature remains intact.
3. `artifacts.use(task).wiredWith(input).toListenTo(type)` registers the task as a finalizer of the final producer. It runs automatically when the artifact is produced, requires no extra command, and is skipped if the artifact is absent. Google's listener recipe confirms this intended “react without changing the command” behavior ([listenToArtifacts](https://github.com/android/gradle-recipes/tree/5e822e2c5e02e3f3ff6ef3ec99dd30eb4b555c27/listenToArtifacts), [OutOperationRequest](https://developer.android.com/reference/tools/gradle-api/9.2/com/android/build/api/artifact/OutOperationRequest)).

For evidence, the following tools provide independent, first-party checks that can be task inputs/outputs rather than ad-hoc `doLast` work:

- bundle structure and semantic validation through bundletool's `ValidateBundleCommand` ([bundletool source](https://github.com/google/bundletool/blob/master/src/main/java/com/android/tools/build/bundletool/commands/ValidateBundleCommand.java));
- signature verification using the JDK `jarsigner -verify` behavior ([jarsigner specification](https://docs.oracle.com/en/java/javase/21/docs/specs/man/jarsigner.html));
- mapping presence/content checks only for minified variants;
- Artifact Report generation as a declared output of the last relevant transformation or listener task.

`toListenTo` is sufficient to make a failed validation fail the Gradle invocation, but it is a Gradle finalizer: the producer may already have written an artifact before the validation runs. If consumers require “no final file appears unless valid,” that is a policy/implementation decision for an in-chain transform and output publication strategy, not a different AGP seam.

## 7. Configuration-cache and build-cache implications

Public Variant/Artifact APIs remove the need for task-name matching and `afterEvaluate`, but configuration-cache compatibility still depends on Kaleido's own task model.

Required shape:

- Register tasks lazily with `TaskProvider`; wire values through `Property`, `RegularFileProperty`, `DirectoryProperty`, and provider-backed collections. Do not call `get()` or resolve configurations during configuration. AGP's component contract explicitly requires lazy linkage ([Component](https://developer.android.com/reference/tools/gradle-api/9.2/com/android/build/api/variant/Component)).
- Give each task complete annotated inputs and outputs. Instrumentation parameters likewise require Gradle input annotations. This is what makes up-to-date checking and cache keys correct ([Gradle incremental build inputs and outputs](https://docs.gradle.org/9.4.1/userguide/incremental_build.html), [InstrumentationParameters](https://developer.android.com/reference/tools/gradle-api/9.2/com/android/build/api/instrumentation/InstrumentationParameters)).
- Do not retain `Project`, task instances, resolved configurations, file streams, XML parser objects, or other live runtime state in task fields/actions. Gradle documents these as unsupported configuration-cache patterns; task actions must use declared properties or injected services ([configuration-cache requirements](https://docs.gradle.org/9.4.1/userguide/configuration_cache_requirements.html)).
- Represent external state used for reproducibility—seed, dictionaries, allowlists, tool versions, feature flags—as value/file inputs. A task that generates random-looking content must derive it solely from declared inputs; ambient time, nondeterministic iteration order, or undeclared environment values defeat both reproducibility and caching.
- Mark a task `@CacheableTask` only when identical declared inputs truly produce identical bytes and output paths do not leak into content. Gradle distinguishes task-output caching from configuration caching; supporting one does not prove the other ([build cache concepts](https://docs.gradle.org/9.4.1/userguide/build_cache_concepts.html)).
- Treat keystore passwords/private-key access as sensitive signing inputs, not ordinary reportable configuration. A post-BUNDLE re-signing design must prove how credentials reach execution without logging or embedding them into the Artifact Report/configuration model; the public artifact API does not solve that concern.

Minimum compatibility evidence for any chosen design is two consecutive Release AAB builds with `--configuration-cache --configuration-cache-problems=fail`, where the second build reuses the stored configuration, plus task validation and repeat builds that compare every output Kaleido claims is reproducible. This validates Kaleido's implementation; it should not be inferred merely from using public AGP APIs.

## 8. Build-order model and decisions this research unlocks

The supported public pipeline is:

```text
generated Java/Kotlin + generated res + generated Manifest + generated R8 rules
                  |            |              |
             compilation   res/AAPT2      Manifest merge
                  |                           |
        Instrumentation or ScopedArtifact.CLASSES   MERGED_MANIFEST transform
                  |                           |
                  +--------> R8/dex + mapping <-----+
                                  |
                        internal bundle packaging
                                  |
                    AGP finalization and signing
                                  |
                    public SingleArtifact.BUNDLE
                                  |
                   optional final-BUNDLE transform
                                  |
                    listener validation/reporting
```

Facts that later decision tickets must account for:

- AndroidJunkCode-style generation and Manifest additions fit cleanly into public generated-source APIs.
- Merged Manifest rewriting and whole class-artifact transformation have public pre-dex/package seams.
- Complete merged layout/resource-table rewriting does **not** have a public pre-signing artifact seam in AGP 9.2.
- Final AAB transformation is public, but it occurs after AGP signing. A byte-changing transform necessarily entails an explicit signing-ownership design.
- R8 rules/dictionaries are inputs; mapping is an optional post-R8 output. They must not be conflated into one transformation stage.

Those constraints narrow the architecture but do not decide whether Kaleido should constrain resource behavior to pre-AAPT2 source overlays, own final-AAB re-signing, or accept AGP-internal coupling. That choice belongs to a later Wayfinder decision ticket.

## 9. Can the three upstream plugins be used directly?

This section evaluates the upstream implementations against the four technical constraints in the ticket. It does not replace the separate provenance/licensing decision.

| Upstream implementation | AGP 9 public API only | Runs inside normal AAB build | No Consumer Project source rewrite | Configuration-cache/reproducibility ready unchanged | Direct-use result |
|---|---|---|---|---|---|
| AndroidJunkCode 2.0.0 | **Yes, structurally** | **Yes**, for configured variants | **Yes** | **Not established; reproducibility is no** | Closest technical fit, but not eligible unchanged for Kaleido's deterministic/auditable promise |
| AABResGuard upstream plugin | **No** | Coupled to old task names; produces a separately named guarded AAB | **Yes** | **No** | Core algorithms may be adapted under their license; Gradle plugin/task layer must be replaced |
| XmlClassGuard 1.2.7 | **No** | Main class guard is a manual task, not an artifact-chain transform | **No** | **No** | Cannot be used directly for Kaleido's pipeline; behavior must be reimplemented at artifact seams |

### AndroidJunkCode 2.0.0

The 2.0.0 plugin is already expressed with `ApplicationAndroidComponentsExtension.onVariants`, `Sources.java/res.addGeneratedSourceDirectory`, `SingleArtifact.MERGED_MANIFEST.toTransform`, and `variant.proguardFiles.add`. Its generation output is under `build/generated`, so its basic integration shape matches the AGP 9 public seams identified above and is automatically pulled into the selected variant's normal build ([AndroidJunkCode plugin at 2.0.0 commit](https://github.com/qq549631030/AndroidJunkCode/blob/cfcd9eed0b8d5a938033a9268a20e58e059b3039/library/src/main/kotlin/cn/hx/plugin/junkcode/plugin/AndroidJunkCodePlugin.kt)).

It does not, unchanged, meet Kaleido's reproducibility contract. Generation uses process-global `Random.Default` with no configured seed and also depends on the default locale for resource prefixes, so identical declared Gradle inputs can produce different output bytes across executions/environments ([JunkUtil](https://github.com/qq549631030/AndroidJunkCode/blob/cfcd9eed0b8d5a938033a9268a20e58e059b3039/library/src/main/kotlin/cn/hx/plugin/junkcode/utils/JunkUtil.kt)). Its extensibility callbacks (`Action` generators) are marked `@Internal` while affecting generated outputs, so Gradle cannot include them in up-to-date/build-cache keys ([JunkCodeConfig](https://github.com/qq549631030/AndroidJunkCode/blob/cfcd9eed0b8d5a938033a9268a20e58e059b3039/library/src/main/kotlin/cn/hx/plugin/junkcode/ext/JunkCodeConfig.kt)).

Configuration-cache storage with only the default null callbacks remains **unverified** in this research. With user-supplied callback objects, serializability and cache-key correctness are not demonstrated. Therefore “uses public AGP 9 APIs” is confirmed, but “configuration-cache-safe and reproducible as-is” is not.

### AABResGuard

The archived upstream Gradle plugin imports the legacy `AppExtension`/`ApplicationVariant`, uses `afterEvaluate` plus `applicationVariants`, locates `bundle<Variant>`, `package<Variant>Bundle`, and `sign<Variant>Bundle` by task name, and manually wires `dependsOn` relationships ([AabResGuardPlugin](https://github.com/bytedance/AabResGuard/blob/e8f3a5d361ce61a3d4fa8bafb9d030bbe459c400/plugin/src/main/kotlin/com/bytedance/android/plugin/AabResGuardPlugin.kt)). Its bundle resolver reads AGP task properties reflectively/by name rather than consuming a public artifact ([BundleResolution](https://github.com/bytedance/AabResGuard/blob/e8f3a5d361ce61a3d4fa8bafb9d030bbe459c400/plugin/src/main/kotlin/com/bytedance/android/plugin/internal/BundleResolution.kt)). AGP 9's removal of the old Variant API makes this integration layer ineligible for direct use ([AGP 9.0 release notes](https://developer.android.com/build/releases/agp-9-0-0-release-notes)).

Its task stores `Project`, `ApplicationVariant`, a mutable extension, signing data, and paths as ordinary fields; reads project/build state during `@TaskAction`; declares no corresponding input/output properties; and explicitly disables up-to-date behavior. That shape violates Gradle's configuration-cache and incremental-task requirements ([AabResGuardTask](https://github.com/bytedance/AabResGuard/blob/e8f3a5d361ce61a3d4fa8bafb9d030bbe459c400/plugin/src/main/kotlin/com/bytedance/android/plugin/tasks/AabResGuardTask.kt), [Gradle requirements](https://docs.gradle.org/9.4.1/userguide/configuration_cache_requirements.html)).

The resource-analysis/repackaging/signing engine is separable from the obsolete Gradle adapter, and the upstream commands already re-sign after changing the bundle. Technically, that engine can inform a new provider-backed `SingleArtifact.BUNDLE` transform, but its old bundletool dependency, deterministic output, current signing compatibility, and configuration-cache-safe parameter model require explicit porting tests. Directly applying the old plugin is not a viable AGP 9 seam.

### XmlClassGuard 1.2.7

The upstream plugin also imports `AppExtension`/`ApplicationVariant`, runs under `afterEvaluate`, creates tasks eagerly, and connects only its ConstraintLayout-ID scan to specifically named AndResGuard/AABResGuard tasks. The actual `xmlClassGuard<Variant>` task is merely created; it is not inserted into AGP's bundle artifact chain ([XmlClassGuardPlugin](https://github.com/liujingxing/XmlClassGuard/blob/0c36e1dc7dcfa2acdaf5ad2982341f50438cebb9/plugin/src/main/java/com/xml/guard/XmlClassGuardPlugin.kt)).

More importantly, `XmlClassGuardTask` walks application/dependency Android projects, rewrites layout/navigation/XML/Manifest files in place, renames class files/directories, and rewrites Java/Kotlin source text ([XmlClassGuardTask](https://github.com/liujingxing/XmlClassGuard/blob/0c36e1dc7dcfa2acdaf5ad2982341f50438cebb9/plugin/src/main/java/com/xml/guard/tasks/XmlClassGuardTask.kt), [Mapping rename implementation](https://github.com/liujingxing/XmlClassGuard/blob/0c36e1dc7dcfa2acdaf5ad2982341f50438cebb9/plugin/src/main/java/com/xml/guard/model/Mapping.kt)). It therefore directly contradicts both “normal AAB build, no extra task” and “do not modify Consumer Project sources.” Tasks retain and call `Project` at execution and do not model the rewritten source tree as declared outputs, so direct configuration-cache compatibility is also ruled out by Gradle's documented requirements.

### Decision-ready technical conclusion

- **Directly compose all three upstream Gradle plugins:** cannot satisfy the stated AGP 9/public-API/automatic/no-source-rewrite/configuration-cache envelope.
- **Use AndroidJunkCode 2.0.0 unchanged:** technically reaches the correct public AGP seams, but leaves deterministic generation, callback input modeling, and configuration-cache proof unresolved; it cannot establish Kaleido's full product contract unchanged.
- **Reuse AABResGuard's licensed engine behind a new Kaleido task:** compatible in principle with the public final-BUNDLE seam, provided the adapter, task model, bundletool integration, signing, validation, and reproducibility behavior are rebuilt and tested.
- **Use XmlClassGuard unchanged:** incompatible by design. Capability Parity requires a new class-artifact + merged-Manifest + constrained resource strategy, with the missing pre-signing merged-resource seam handled as an explicit architecture decision.
