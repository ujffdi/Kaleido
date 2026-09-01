# Define supported project topology and rejection behavior

Type: grilling
Status: resolved
Blocked by: 04, 07

## Question

How does Kaleido recognize supported one-Application-module Consumer Projects with ordinary dependencies, and how does it reject or isolate unsupported topologies and dynamic runtime mechanisms before they cause a broken release?

## Answer

A Consumer Project is the current `com.android.application` Project that applies Kaleido and owns its isolated Release variants and Release Evidence Sets. It is not the whole Gradle build: unrelated Application projects may coexist and may apply Kaleido independently. Kaleido never enumerates or reads mutable state from other Projects merely to prove uniqueness, preserving Gradle Isolated Projects compatibility.

The MVP requires `com.android.application` to be applied before Kaleido. Applying Kaleido to an Android library, dynamic feature, asset pack, test-only project, or project without the Application plugin fails immediately. The target Application must declare no Dynamic Features or Asset Packs. Compose Generator additionally requires `buildFeatures.compose == true` when explicitly enabled.

Kaleido processes every enabled Application variant whose build type name is exactly `release`, including arbitrary product-flavor combinations. Each owns isolated declared inputs, generated directories, mappings, final AAB, and Artifact Report. Other build types are ignored; applying Kaleido with no enabled Release variant fails rather than succeeding as a no-op. Every target Release variant must have minification enabled.

Supported ordinary dependencies include local Android/JVM/Kotlin libraries, external AAR/JAR artifacts, project/module/file dependencies, ordinary Gradle scopes and platforms, annotation/KAPT/KSP-generated compiled inputs, and native libraries. Kaleido records resolved origins and signals but does not certify safety by coordinate or maintain a dependency allowlist. It transforms only Application-module and Kaleido-generated classes; dependency classes remain analysis-only and native entries are byte-preserved.

Confirmed structural incompatibilities, applied hotfix/plugin Gradle plugins, and direct Application-module references to external code-loading or SplitInstall interfaces fail; no generic force override admits them. Dependency-only dynamic-loading, reflection, JNI, and native-load signals are reported and routed to the Protection Requirement/Escape Hatch policy; native-library presence alone remains supported. Absence of known signals is never reported as proof that dynamic loading is absent.

Before Bundle Rewrite, the candidate must be a `REGULAR` AAB whose complete module map is exactly one `FEATURE_MODULE` named `base`; any asset, dynamic feature, ML/AI, SDK dependency, unknown, or other module fails even when configuration-time checks were empty. Tests and fixtures do not enter Release transformation.

Evidence: [`agp9-project-topology-detection.md`](../research/agp9-project-topology-detection.md).

## Comments

- Decision round 1: the MVP supports exactly one `com.android.application` module in the Gradle build, and that module applies Kaleido. Multiple Application modules are rejected even when only one applies Kaleido; ordinary library modules remain allowed.
- Decision round 1: supported ordinary dependencies include local Android/JVM/Kotlin libraries, external AAR/JAR artifacts, ordinary Gradle dependency scopes and platforms, annotation/KAPT/KSP-generated compiled inputs, and native-library dependencies. Kaleido transforms only Application-module and its own generated classes; dependency classes remain analysis-only and native libraries are byte-preserved.
- Decision round 1: any product-flavor combination is supported only when its build type is exactly `release`. Each flavored Release variant owns isolated inputs, generated files, mappings, final AAB, and Artifact Report; debug and custom release-like build types are outside the MVP.
- Decision round 1: Dynamic Feature, Asset Pack, Instant App, and multiple Application-module topologies fail without a force override. Tests and test fixtures are ignored by the Release transform, but a separate test Application module still violates the one-Application rule. Unknown non-base functional/asset modules discovered in the final AAB fail before Bundle Rewrite.
- Evidence for round 2: [`agp9-project-topology-detection.md`](../research/agp9-project-topology-detection.md) separates configuration-time, variant-time, artifact-time, and heuristic evidence. It proves that build-wide Application-project enumeration would violate Isolated Projects, while current-project Application identity plus a Regular/base-only final AAB is a supported topology seam.
- Superseding decision round 2: the round-1 build-wide one-Application rule is withdrawn. A Consumer Project is the current `com.android.application` Project that applies Kaleido; each target Release AAB must be Regular and strictly base-only. Other unrelated Application projects may coexist in the Gradle build and may apply Kaleido independently without cross-Project state access.
- Decision round 2: the MVP requires `com.android.application` to be applied before Kaleido. Wrong current-project Android plugin types or absence of the Application plugin fail immediately; Kaleido does not use `afterEvaluate` or build-wide lifecycle inspection to infer later plugin application.
- Decision round 2: confirmed structural incompatibilities, applied hotfix/plugin Gradle plugins, and direct Application-module references to external code-loading/SplitInstall interfaces fail without a generic force override. Dependency-only dynamic-loading, reflection, JNI, and native-load signals are reported and routed to the keep/escape-hatch policy; native-library presence alone is supported.
- Superseding decision from the keep/escape-hatch model: the round-2 phrase “without a generic force override” is withdrawn. Unsupported topology always fails and cannot be admitted by an Escape Hatch or global force option.
- Decision round 2: resolved dependency coordinates and origins are evidence, not a safety allowlist. Project, module, and file artifacts that merge into the base module remain ordinary dependencies unless an explicit unsupported policy fires; Artifact Report records scan scope and signals without claiming dynamic behavior is absent.
- Decision round 3: applying Kaleido with no enabled Application variant whose build type is exactly `release` fails with an actionable inventory of discovered build types/variants. A successful no-op is forbidden because it would publish no Release Evidence Set while appearing protected.
