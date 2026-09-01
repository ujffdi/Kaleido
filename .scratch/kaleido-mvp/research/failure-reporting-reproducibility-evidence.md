# Failure, reporting, and reproducibility evidence

This note supports ticket 08. The repository baseline is Gradle 9.4.1 and AGP 9.2.1. Sections labelled **Official fact** describe first-party Gradle, Android, or JDK contracts; sections labelled **Kaleido policy inference** are proposed engineering consequences, not behavior guaranteed by those tools.

## 1. Configuration Cache, up-to-date checks, and Build Cache are different proofs

**Official facts**

- Configuration Cache stores the result of configuration: the task graph, configured task state, and dependency information. A hit skips configuration. It does **not** store task outputs; Build Cache does that. Configuration Cache fingerprints configuration-time files, properties, environment variables, system properties, `ValueSource` values, and build logic that were actually observed ([Gradle Configuration Cache](https://docs.gradle.org/9.4.1/userguide/configuration_cache.html)).
- Up-to-date checks compare a task's declared inputs and outputs with the previous execution in the same workspace. Build Cache uses substantially the same declared-input model but can restore outputs from an earlier execution after local outputs have been removed, including from another checkout or machine. A custom task must opt in with `@CacheableTask`; complete inputs/outputs and non-overlapping output ownership are required ([incremental build](https://docs.gradle.org/9.4.1/userguide/incremental_build.html), [Build Cache](https://docs.gradle.org/9.4.1/userguide/build_cache.html), [Build Cache concepts](https://docs.gradle.org/9.4.1/userguide/build_cache_concepts.html)).
- File inputs default to absolute-path sensitivity when no path sensitivity is declared. `RELATIVE` directories and `NONE` single files are the ordinary relocatable choices when absolute location does not affect output. Environment variables are not automatically tracked as task inputs, and file encoding/locale-like ambient state can change tool output unless fixed or declared ([task best practices](https://docs.gradle.org/9.4.1/userguide/best_practices_tasks.html), [common caching problems](https://docs.gradle.org/9.4.1/userguide/common_caching_problems.html)).
- Configuration-cache task state cannot retain live `Project`, `Gradle`, streams, classloaders, or other unsupported runtime objects. Work must flow through declared Gradle properties/providers, immutable parameters, injected services, and explicitly declared shared `BuildService` usage ([Configuration Cache requirements](https://docs.gradle.org/9.4.1/userguide/configuration_cache_requirements.html)).

**Kaleido policy inference**

- Every Kaleido task should own disjoint variant-scoped outputs and model the immutable Adoption Plan, schema/policy versions, normalized seed inputs, tool versions, source artifacts, protection requirements, and plan files as declared properties. Do not use task names, current time, default locale/charset, process-global state, unordered collection traversal, or checkout-absolute paths as hidden inputs.
- Configuration Cache compatibility is a whole task-graph property, while Build Cache eligibility is decided per deterministic stage. Passing one does not imply passing the other.

## 2. Verification matrix

**Official facts**

Gradle's cache-debugging guide recommends: run twice without Build Cache and expect output-producing work to become `UP-TO-DATE`; then populate an empty local Build Cache, clean, run again, and expect eligible work `FROM-CACHE`; finally repeat from a second checkout to test relocatability. `-Dorg.gradle.caching.debug=true` exposes cache keys and per-input hashes for diagnosis ([debugging Build Cache misses](https://docs.gradle.org/9.4.1/userguide/build_cache_debugging.html)). Configuration Cache reports whether an entry was stored or reused, and `--configuration-cache-problems=fail` turns compatibility problems into failures ([enabling Configuration Cache](https://docs.gradle.org/9.4.1/userguide/configuration_cache_enabling.html)).

**Kaleido policy inference**

Use independent gates rather than one repeated command:

1. Two identical executions without `clean` and without Build Cache: deterministic output tasks become `UP-TO-DATE`.
2. Two identical invocations with `--configuration-cache --configuration-cache-problems=fail`: the second invocation reuses configuration.
3. Populate an empty local Build Cache, remove outputs with `clean`, and rebuild with `--build-cache`: every Kaleido stage declared cacheable is `FROM-CACHE`.
4. Populate in checkout A and restore in checkout B at a different absolute path: cacheable outputs are relocatable and byte-identical.
5. Re-execute clean builds with Build Cache disabled in separate directories, locales/time zones, and supported toolchain environments; compare canonical output manifests and SHA-256 values. This is the actual reproducibility proof.
6. Mutate one declared DSL/property/file/toolchain dimension at a time. The affected configuration entry/task key must invalidate, every semantically affected downstream task must execute, and unrelated variants/stages must remain reusable.

## 3. Reproducible archive and signing boundary

**Official facts**

- Gradle defines strong reproducibility as byte-for-byte identical outputs from the same sources across machine and time. Its archive tasks address two common sources by disabling preserved file timestamps and enabling reproducible file order by default in Gradle 9+, but Gradle also identifies SDK/toolchain identity, encoding, timestamps, absolute paths, and unstable iteration as remaining inputs to control ([reproducible build guidance](https://docs.gradle.org/9.4.1/userguide/best_practices_security.html), [archive file order API](https://docs.gradle.org/9.4.1/kotlin-dsl/gradle/org.gradle.api.tasks.bundling/-abstract-archive-task/is-reproducible-file-order.html), [common caching problems](https://docs.gradle.org/9.4.1/userguide/common_caching_problems.html)). These defaults apply to Gradle `AbstractArchiveTask`; they do not automatically canonicalize a custom ZIP/AAB writer.
- Android documents `jarsigner` as a supported way to sign an AAB ([Android command-line build guidance](https://developer.android.com/build/building-cmdline#sign_cmdline)). JDK `jarsigner` selects default signature algorithms from key type and size, and those defaults may change between JDK releases. TSA options add a signed time stamp. The JCA `Signature` API permits providers/algorithms to consume `SecureRandom` for signing ([JDK 21 jarsigner](https://docs.oracle.com/en/java/javase/21/docs/specs/man/jarsigner.html), [JCA Signature](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/security/Signature.html)). None of these contracts promises byte-identical signed JAR/AAB output across keys, providers, JDKs, or executions.

**Kaleido policy inference**

- Define the transformed **unsigned** AAB plus deterministic mappings/plans/receipts as the byte-reproducible boundary. Canonicalize entry paths/order, timestamps, compression policy, protobuf serialization, comments/extras, and relative report paths in Kaleido's own writer; pin Gradle, AGP, JDK, bundletool, Kotlin/Compose/R8, and policy/schema identities in evidence.
- Treat re-signing as non-cacheable and outside the byte-reproducibility promise. The report may compare unsigned-content hash, signed-AAB hash, certificate identity, signature algorithms/provider/JDK, and whether TSA was used; signed-byte equality is only an observed fixture result, never an unconditional product guarantee.

## 4. Cacheability and sensitive data

**Official facts**

- Cacheable tasks must have repeatable outputs for identical declared inputs. A volatile or non-repeatable step should be split from expensive deterministic work; `@DisableCachingByDefault` documents that a task is not cacheable by default ([Build Cache](https://docs.gradle.org/9.4.1/userguide/build_cache.html), [common caching problems](https://docs.gradle.org/9.4.1/userguide/common_caching_problems.html)).
- Configuration Cache serializes all task state reachable from scheduled tasks, not only annotated inputs. Sensitive values realized into task state can therefore enter the encrypted cache entry. Gradle recommends lazy providers such as `providers.environmentVariable(...)`, execution-time resolution, restricted cache/key access, and user-home credential storage; possession of both the project cache and encryption key permits reading it ([Configuration Cache security](https://docs.gradle.org/9.4.1/userguide/configuration_cache.html#config_cache:secrets)).
- A configured remote Build Cache uploads the declared outputs of cacheable tasks. Gradle has no per-output distinction between local-only and remote-safe data within one cacheable task. Official guidance recommends clean, trusted CI writers and read-only developer access because Gradle cannot prevent modified/corrupt outputs from being uploaded during task execution ([Build Cache configuration](https://docs.gradle.org/9.4.1/userguide/build_cache.html), [Build Cache use cases](https://docs.gradle.org/9.4.1/userguide/build_cache_use_cases.html)).

**Kaleido policy inference**

- Generation, dictionary/rule generation, plan construction, class rewrite, mapping composition, unsigned bundle rewrite, and other deterministic transforms may become `@CacheableTask` only after repeated, clean, relocated proof. Signing, final signature/certificate validation, final evidence-set validation, and atomic publication remain explicitly non-cacheable; signing must also execute rather than rely on a stale up-to-date outcome.
- Pass keystore location and passwords through lazy provider-backed properties, resolve only inside signing execution, retain no resolved secret in task fields/services after use, and never write it to output. Non-cacheability does not by itself make Configuration Cache safe.
- A mapping or Artifact Report produced by a cacheable task is eligible for upload to every configured remote cache. Kaleido must therefore either (a) classify those artifacts as non-secret and document that the organization's Build Cache is within the Release Evidence Set's trust boundary, or (b) generate sensitive human mappings/reports in separate non-cacheable tasks. No secret, absolute credential path, password-derived value, private key material, environment dump, or source payload should enter a cacheable output. This is a product/security choice, not something Gradle decides for the plugin.

## 5. What task/build events prove

**Official facts**

Gradle's public Tooling API `TaskSuccessResult` reports `isUpToDate()` and `isFromCache()`. These mean only that Gradle skipped execution because local snapshots matched, or restored outputs from a cache ([TaskSuccessResult](https://docs.gradle.org/9.4.1/javadoc/org/gradle/tooling/events/task/TaskSuccessResult.html)). Only APIs explicitly documented as public are supported; internal build-operation types/listeners are not stable plugin seams ([public Gradle APIs](https://docs.gradle.org/9.4.1/userguide/public_apis.html)).

**Kaleido policy inference**

- Task outcomes/build events can prove configuration reuse, up-to-date avoidance, cache restoration, execution/failure ordering, and invalidation observations for that invocation.
- They cannot prove that a task's declared inputs are complete, that independently executed outputs are byte-identical, that a remote cache entry is trustworthy, that a signed AAB is reproducible, or that the final published artifact matches source. Prove those with independent clean executions, canonical content manifests/hashes, structural/signature validation, and explicit toolchain evidence. Kaleido should not depend on internal Build Operations to construct its Artifact Report.

## 6. Stable diagnostics without secret leakage

**Official facts**

- Gradle logging exposes `error`, `warn`, `lifecycle`, `info`, and `debug` levels. Its `Logger` API explicitly warns that logging credentials, tokens, or sensitive environment values above debug is a security vulnerability ([Gradle logging](https://docs.gradle.org/9.4.1/userguide/logging.html), [Logger API](https://docs.gradle.org/9.4.1/javadoc/org/gradle/api/logging/Logger.html)).
- The Problems API can attach an ID/group, contextual label, details, locations, solutions, documentation, severity, and exception, and Gradle can emit an HTML problems report. In the baseline it is marked `@Incubating`, so it is useful for structured integration but not a stable cross-version product contract by itself ([Problems API](https://docs.gradle.org/9.4.1/userguide/reporting_problems.html), [ProblemSpec](https://docs.gradle.org/9.4.1/kotlin-dsl/gradle/org.gradle.api.problems/-problem-spec/index.html)).

**Kaleido policy inference**

- Define Kaleido's stable contract independently: immutable diagnostic code, severity, project, exact variant, normalized declaration/artifact location, short cause, and actionable repair. Render the same sanitized record to exceptions/logs and, when compatible, the incubating Problems API. Never make wording, stack trace, Problems HTML layout, or log level the machine-readable identity.
- Be stricter than Gradle's minimum warning: never log signing secrets even at debug. Redact secret values, credential-bearing URIs, user-home/keystore absolute paths, environment/property dumps, command lines containing passwords, and exception messages from third-party signers before any console, report, or cache output.
- Hard Release invariants should throw/fail and cannot be demoted by verbosity. `info`/`debug` may add counts and hashes but not behavior-changing suppression. Reports should use stable relative paths and deterministic ordering, omit wall-clock time from the reproducible section, and separate deterministic unsigned evidence from observed signing/publication facts.

## Decision-ready summary

1. Require three independent green gates: Configuration Cache reuse, local/relocated Build Cache reuse for explicitly cacheable stages, and clean byte-for-byte reproducibility of the unsigned evidence boundary.
2. Cache only stages whose identical declared inputs repeatedly produce identical non-secret outputs. Keep signing, final verification, and publication non-cacheable and execution-time secret-bound.
3. Treat remote cache eligibility of raw/composed/resource mappings and reports as an explicit trust-boundary decision; a cacheable task cannot mark one output local-only.
4. Use task outcomes only as reuse/invalidation evidence, never as the reproducibility proof.
5. Own stable `KLD-...` diagnostics and redaction independently of Gradle's incubating Problems API, while optionally bridging to it on supported Gradle versions.

## Related repository evidence

- [`agp9-artifact-seams.md`](./agp9-artifact-seams.md) already establishes public AGP artifact wiring and the need for declared task inputs/outputs.
- [`final-bundle-signing-and-code-transparency.md`](./final-bundle-signing-and-code-transparency.md) establishes provider-backed signing inputs and the AAB/JAR verification surface.
- [`transformation-algorithm-evidence.md`](./transformation-algorithm-evidence.md) establishes Kaleido's deterministic unsigned ZIP/protobuf policy.
