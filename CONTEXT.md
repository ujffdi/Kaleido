# Kaleido

Kaleido is an Android build-hardening product delivered as a Gradle plugin. This context defines the product language shared by its maintainers and adopters.

## Language

**Kaleido**:
The Gradle plugin product developed in this repository.
_Avoid_: Kaleido app, sample app

**Consumer Project**:
The Android Application project that applies Kaleido and owns its isolated Release variants and Release Evidence Sets, even when its Gradle build contains other unrelated Application projects.
_Avoid_: Accessor, client app, host app

**Sample App**:
The Android application maintained in this repository to demonstrate and exercise Kaleido from a Consumer Project's perspective.
_Avoid_: Main app, product app

**MVP**:
The first publicly releasable Kaleido version whose scope will be fixed by the current decision map.
_Avoid_: Demo, complete product

**Reference Consumer**:
A real Consumer Project used to prove Kaleido against production-shaped build requirements without contributing project-specific behavior to the plugin.
_Avoid_: Template app, target app

**Build Hardening**:
A reproducible and auditable build-time transformation that raises the cost of inspecting or tampering with an Android release while preserving its intended behavior.
_Avoid_: Review evasion, anti-detection, guaranteed approval

**Artifact Report**:
The canonical per-Release-variant JSON output that separates deterministic inputs, transformations, mappings, and unsigned-content evidence from the observed signing, validation, and publication evidence for that invocation.
_Avoid_: Build log, analytics report

**Release Evidence Set**:
The indivisible publication result containing the validated final Release AAB, Kaleido's raw and composed mappings, and the Artifact Report; the AAB is not publishable when any required evidence is missing or inconsistent.
_Avoid_: Side files, optional reports

**Kaleido Release Dossier**:
The immutable evidence bundle that proves one Kaleido plugin version passed its Compatibility Matrix, product acceptance, provenance, documentation, and public-publication gates; it is not evidence for any particular Consumer Project AAB.
_Avoid_: Release Evidence Set, CI logs, test summary

**Kaleido Release Candidate**:
The immutable source revision, version, dependency locks, toolchain, publication bytes, and digests evaluated by every release gate before one Kaleido plugin version may be published.
_Avoid_: Latest main, rebuilt artifact, mostly green build

**Reproducibility Boundary**:
The signed-content-independent set of Kaleido outputs that must be byte-identical for identical normalized declared inputs, Consumer artifacts, seed fingerprint, and exact toolchain; it ends at the transformed unsigned Release AAB and excludes expected final-signature variability.
_Avoid_: Reproducible signed AAB, same build result

**Protection Requirement**:
A declared or inferred constraint that preserves one or more runtime-relevant properties of a class, member, resource, or packaged path throughout the Hardening Pipeline.
_Avoid_: Keep flag, whitelist

**Escape Hatch**:
A bounded Consumer Project declaration that supplies Protection Requirements for a dynamic reference Kaleido cannot close automatically; it never bypasses integrity validation or unsupported-topology rejection.
_Avoid_: Force option, ignore failure, global bypass

**Adoption Contract**:
The stable Consumer Project interface for applying Kaleido, selecting a Profile, and declaring Release inputs while the Hardening Pipeline and capability implementations remain hidden.
_Avoid_: Engine configuration, task API, integration script

**Compatibility Matrix**:
The immutable per-Kaleido-release list of exact AGP, Gradle, runtime JDK, Build Tools, compileSdk, and Kotlin-mode combinations whose complete Release behavior is supported by automated evidence.
_Avoid_: AGP 9 support, likely compatible, N-1 assumption

**Bridge Release**:
A non-preview Kaleido minor that still supports both sides of a planned major migration long enough for a Consumer Project to remove deprecations before changing its toolchain or public Interface usage.
_Avoid_: Compatibility shim, permanent legacy mode, automatic migration

**Capability Parity**:
Coverage of an upstream tool's documented built-in behavior and declarative configuration dimensions without requiring source-compatible implementation or exposing arbitrary executable generator callbacks.
_Avoid_: Source copy, API clone

**Core Capability Set**:
The four mandatory Kaleido capability families delivered by one plugin: AndroidJunkCode-equivalent code/resource generation, including its opt-in Compose Generator sub-capability; XmlClassGuard-equivalent class and Manifest/XML reference processing; AABResGuard-equivalent final-AAB resource obfuscation and optimization; and deterministic R8 dictionary/mapping handling.
_Avoid_: Optional feature set, interchangeable menu

**Safe Profile**:
Kaleido's default operating policy, which runs every family in the Core Capability Set while restricting generation to content that adds no user-enterable components or observable runtime side effects.
_Avoid_: Basic mode, limited mode

**Full Profile**:
An explicit operating policy that runs every family in the Core Capability Set and exposes AndroidJunkCode Capability Parity, including explicitly configured component and Manifest generation, while retaining Kaleido's reporting and safety controls.
_Avoid_: Unsafe mode, compatibility mode

**Hardening Pipeline**:
The ordered, automatic set of Kaleido transformations and validations applied by a normal Release AAB build.
_Avoid_: Obfuscation script, manual guard task

**Compose Generator**:
An opt-in sub-capability of AndroidJunkCode-equivalent generation, shipped in the first publicly releasable MVP, that contributes deterministic Compose Runtime-only compiler-lowered code retained in final Release DEX without a Kaleido-declared UI, Preview, component, navigation, startup, or ordinary bytecode entry point.
_Avoid_: Compose template, generated page
