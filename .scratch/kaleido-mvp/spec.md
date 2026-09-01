# Kaleido Public MVP Implementation Specification

Triage: ready-for-agent

## Problem Statement

Android application teams need a build-hardening tool that can be adopted through the normal Android release build without replacing their build system or requiring them to assemble a chain of unrelated tools. The output must remain a valid, installable, signed Android App Bundle, while the transformations must be deterministic, auditable, reference-safe, and reproducible enough for release engineering and incident investigation.

The current Kaleido repository contains a template Sample App and planning documentation, but it does not yet contain the Gradle plugin, hardening pipeline, evidence model, compatibility fixtures, release automation, or public-project documentation needed to deliver that product. Existing open-source projects also cannot simply be combined as-is: AndroidJunkCode and XmlClassGuard do not provide a sufficiently clear license basis for source reuse, AabResGuard requires explicit Apache-2.0 provenance when algorithms are reused, and none of the projects supplies Kaleido's complete AGP 9 adoption, protection, mapping, reproducibility, signing, and evidence contract.

The MVP therefore needs one coherent implementation whose safe path is automatic, whose dangerous capabilities require explicit intent, and whose successful release output can be independently explained. Kaleido must harden a consumer application, not claim to evade store review, make applications unlinkable, hide malicious behavior, or guarantee approval by an app store.

## Solution

Build and publish Kaleido as an Apache-2.0 Android Gradle plugin with plugin ID `com.tongsr.kaleido`. A Consumer Project applies the plugin after the Android application plugin, keeps using its ordinary `bundle<ReleaseVariant>` task, and receives one atomically published hardened release AAB plus a complete Release Evidence Set.

The MVP implements a fixed Hardening Pipeline covering four Core Capability Set families: deterministic AndroidJunkCode-equivalent generation, reference-aware XmlClassGuard-equivalent class identity transformation, AABResGuard-equivalent final-bundle resource hardening, and deterministic R8 dictionary and mapping composition. Compose Generator is an optional sub-capability of the AndroidJunkCode family. It ships in the first public MVP, is disabled by default, and generates only bounded, internal, runtime-only Compose code with no UI, navigation, components, resources, startup hooks, or consumer-code references.

Kaleido exposes a convention-first adoption contract. With no configuration, each eligible release variant uses the Safe Profile and Safe Defaults v1. The Full Profile and individual Escape Hatches require explicit typed configuration. The implementation plans all identity-changing work before mutation, validates exact input digests, preserves resource IDs, composes mappings back to original consumer identities, canonicalizes the unsigned AAB, separates reproducible evidence from signing and publication evidence, and refuses to publish partial or unverifiable results.

The first public release is allowed only from one immutable Kaleido Release Candidate that passes the mandatory compatibility, topology, correctness, reproducibility, cache, performance, size, provenance, signing, documentation, and publication gates. The same bytes must be promoted through every gate; a product failure creates a new candidate rather than a waiver or an in-place replacement.

## User Stories

1. As an Android application developer, I want to apply one Gradle plugin, so that I do not have to coordinate several independent hardening tools.
2. As an Android application developer, I want to keep using the normal release bundle task, so that Kaleido fits the build workflow my project already uses.
3. As an Android application developer, I want Kaleido to discover every exact `release` build-type variant, so that flavored release variants are protected consistently.
4. As an Android application developer, I want a useful no-configuration Safe Profile, so that initial adoption does not require security-tool expertise.
5. As an Android application developer, I want unsafe or behavior-changing options to require explicit configuration, so that adopting the plugin cannot silently broaden runtime behavior.
6. As an Android application developer, I want failures to occur before mutation whenever compatibility cannot be proven, so that my ordinary build outputs are not left in an ambiguous state.
7. As an Android application developer, I want one final bundle artifact per eligible variant, so that downstream release automation has a single authoritative input.
8. As an Android application developer, I want Kaleido to coexist with normal Android libraries, third-party dependencies, and native libraries, so that ordinary applications remain supported.
9. As an Android application developer, I want dependency bytecode to be analysis-only, so that Kaleido does not rewrite code it does not own.
10. As an Android application developer, I want actionable errors when my project topology is unsupported, so that I can correct adoption instead of debugging corrupted output.
11. As an Android application developer, I want applying Kaleido to a non-application module to fail clearly, so that plugin misuse is immediately visible.
12. As an Android application developer, I want separate application projects to be hardened independently, so that a repository containing multiple apps does not force them into a shared transformation state.
13. As a build engineer, I want plugin application order to be validated, so that Kaleido only configures a fully established Android application model.
14. As a build engineer, I want minification to be required for eligible variants, so that the R8 and mapping contract is always present.
15. As a build engineer, I want Dynamic Features, Asset Packs, Instant Apps, hotfix frameworks, and runtime plugin frameworks to be rejected, so that unsupported multi-module or dynamic-loading semantics cannot be silently damaged.
16. As a build engineer, I want confirmed direct external-code loading and SplitInstall use to be rejected, so that a successful build does not overstate class-reference closure.
17. As a build engineer, I want unknown extra AAB modules to fail validation, so that Kaleido never transforms a topology it did not model.
18. As a build engineer, I want configuration to be provider-based and configuration-cache-safe, so that Kaleido does not regress modern Gradle builds.
19. As a build engineer, I want signing secrets to be resolved only during execution, so that configuration snapshots and build caches cannot capture credentials.
20. As a build engineer, I want signing credentials to come from one complete source, so that a build cannot accidentally combine unrelated key material.
21. As a build engineer, I want exact-variant signing configuration to outrank broader sources, so that deliberate per-variant credentials are deterministic.
22. As a build engineer, I want incomplete signing sources to fail instead of falling through field by field, so that credential precedence remains auditable.
23. As a Consumer Project owner, I want deterministic ordinary Java/Kotlin-compatible junk code, so that repeated builds with the same normalized inputs produce the same generated program.
24. As a Consumer Project owner, I want the Safe Profile to generate nonzero ordinary code and resources, so that the default profile exercises every safe generation surface.
25. As a Consumer Project owner, I want generated code placed under a namespace-derived internal package, so that it does not collide with my public application packages.
26. As a Consumer Project owner, I want generated resource names to use a deterministic project-specific prefix, so that collisions are avoided without exposing raw seed material.
27. As a Consumer Project owner, I want generation counts to have explicit bounds, so that configuration cannot accidentally create unbounded source trees or bundles.
28. As a Consumer Project owner, I want the Safe Profile to avoid generated Activities, so that default adoption creates no new Android entry points.
29. As a Consumer Project owner, I want the Full Profile to make entry-point generation opt-in, so that any runtime-visible component is an explicit decision.
30. As a Consumer Project owner, I want native or metadata deletion, unused-string replacement, and language filtering to be Full-only options, so that lossy transformations never occur by default.
31. As a Consumer Project owner, I want the seed to be stable for an application ID and variant, so that unchanged release inputs remain reproducible.
32. As a Consumer Project owner, I want to provide an explicit seed through a Gradle Provider, so that CI can control generation without eager secret access.
33. As a security reviewer, I want the seed fingerprint to be derived with normalized Unicode, UTF-8, and SHA-256, so that equivalent seed input has a canonical interpretation.
34. As a security reviewer, I want domain-separated seed derivation for each capability, so that one generator cannot infer or reuse another generator's random stream.
35. As a security reviewer, I want reports and diagnostics to omit the raw seed, so that reproducibility metadata does not leak sensitive input.
36. As a Compose application developer, I want Compose Generator available in the first MVP, so that Compose-based junk-code generation is not deferred to a later product phase.
37. As a Compose application developer, I want Compose Generator to belong to the AndroidJunkCode capability family, so that it is configured and explained as generation rather than as a UI framework feature.
38. As a Compose application developer, I want Compose Generator disabled by default, so that non-Compose and conservatively configured projects are unchanged.
39. As a Compose application developer, I want enabling Compose Generator to require my project to already provide Compose support, so that Kaleido does not inject compiler plugins or runtime dependencies.
40. As a Compose application developer, I want generated composables to be internal and top-level, so that they do not enlarge my public source API.
41. As a Compose application developer, I want generated composables to have no UI, navigation, preview, resource, component, or startup behavior, so that the generator cannot alter the user experience.
42. As a Compose application developer, I want generated composables to use only pure computation, conditionals, and calls within their generated graph, so that they remain runtime-quiet.
43. As a Compose application developer, I want the generated call graph to be closed, deterministic, and acyclic, so that it is statically explainable and bounded.
44. As a Compose application developer, I want file and function counts to have documented limits, so that even extreme valid configurations remain tractable.
45. As a Compose application developer, I want generated Compose functions retained through R8 without a static entry point, so that the code exists in the final DEX without being wired into runtime behavior.
46. As a Compose application developer, I want the final DEX and mapping to prove that the generated lowered functions remain, so that the feature's result is evidence-backed.
47. As a Compose application developer, I want the documentation to avoid claiming absolute runtime unreachability, so that the product states only what static evidence can prove.
48. As an application security engineer, I want class renaming to begin from reference-driven application and generated-code roots, so that identity changes are based on actual program structure.
49. As an application security engineer, I want Kotlin/JVM class families to be transformed as a unit, so that nested, synthetic, companion, and metadata-related identities remain consistent.
50. As an application security engineer, I want dependency and protected classes left unchanged, so that Kaleido respects ownership and explicit preservation requirements.
51. As an application security engineer, I want legal deterministic class identities derived from the normalized seed and inputs, so that renaming is stable and collision-free.
52. As an application security engineer, I want all JVM type-bearing surfaces rewritten structurally, so that descriptors, signatures, annotations, records, nests, modules, and metadata remain coherent.
53. As an application security engineer, I want unsupported opaque references retained only when closure is proven, so that Kaleido never guesses at an unsafe rewrite.
54. As an application security engineer, I want Manifest and XML references handled through semantic registries, so that ordinary strings are never globally replaced.
55. As an application security engineer, I want R8 to handle the remaining program after Kaleido's planned class transformation, so that the responsibilities of both transformations are explicit.
56. As an application security engineer, I want deterministic R8 dictionaries and rules, so that R8 output participates in byte reproducibility.
57. As an application security engineer, I want original-to-final composed class mappings, so that production stack traces can be retraced from final names to source identities.
58. As a release engineer, I want every resource ID preserved, so that hardening cannot alter runtime identity semantics.
59. As a release engineer, I want the resource table, compiled XML references, resource paths, and ZIP entries transformed as one plan, so that no representation is left inconsistent.
60. As a release engineer, I want DEX, native libraries, and compiled-code transparency data preserved during resource rewriting, so that the bundle stage cannot silently mutate code.
61. As a release engineer, I want deduplication limited to byte-identical compatible unprotected payloads, so that optimization never merges resource IDs or semantic entries.
62. As a release engineer, I want protected or incompatible payloads kept separate, so that optimization cannot override the protection model.
63. As a release engineer, I want canonical ZIP and protobuf output for the unsigned bundle, so that independent workspaces can produce identical unsigned bytes.
64. As a release engineer, I want stale signatures, comments, and nondeterministic ZIP extras removed before re-signing, so that the final signature covers one canonical candidate.
65. As a release engineer, I want the final bundle signed only after all deterministic transformations finish, so that no post-signing mutation can invalidate evidence.
66. As a release engineer, I want certificate identity and signature coverage verified, so that a published bundle is bound to the intended upload key.
67. As a release engineer, I want bundletool validation to pass before publication, so that the hardened AAB is structurally consumable by Android tooling.
68. As a release engineer, I want optional code-transparency material preserved and verified, so that projects using that feature do not lose its integrity guarantees.
69. As a release engineer, I want one atomic publication step for the final AAB and evidence, so that downstream systems never observe a half-complete release.
70. As a security reviewer, I want protection requirements expressed by semantic dimensions, so that "keep this" has a precise meaning beyond a single class name.
71. As a security reviewer, I want reachability, original identity, descriptor closure, runtime attributes, resource names, and packaged paths modeled separately, so that protection can be minimal and complete.
72. As a security reviewer, I want finite automatic closure over declared protection requirements, so that necessary related identities are preserved without a global bypass.
73. As a security reviewer, I want each Escape Hatch to have an ID, reason, dimensions, and bounded exact or prefix matcher, so that exceptions are reviewable.
74. As a security reviewer, I want zero-match, conflicting, or unclosed Escape Hatches to fail, so that stale or misleading exceptions cannot pass unnoticed.
75. As a security reviewer, I want framework-specific exceptions backed by release fixtures, so that compatibility claims have executable evidence.
76. As a security reviewer, I want no raw ProGuard escape or generic engine callback, so that users cannot bypass Kaleido's closure and evidence model.
77. As a plugin maintainer, I want identity-changing work represented as immutable versioned plans, so that planning, execution, and validation are separate responsibilities.
78. As a plugin maintainer, I want plan inputs bound by exact digests, so that an executor cannot apply a valid plan to different bytes.
79. As a plugin maintainer, I want executors to implement exactly the planned mapping, so that execution contains no hidden renaming policy.
80. As a plugin maintainer, I want validators to emit immutable receipts, so that later stages can prove which input, plan, and output were used.
81. As a plugin maintainer, I want unknown major versions of internal plans rejected, so that incompatible cached or stale artifacts cannot be misread.
82. As a plugin maintainer, I want deterministic sorted serialization, so that equivalent plans have equivalent bytes and digests.
83. As a plugin maintainer, I want transformation algorithms to remain streaming or O(n log n), so that large applications do not trigger quadratic behavior.
84. As a plugin maintainer, I want safe degradation to happen only before mutation, so that unsupported cases either retain proven-safe identities or fail cleanly.
85. As a CI operator, I want configuration-cache reuse to be a release gate, so that Kaleido supports current Gradle execution models.
86. As a CI operator, I want no-clean up-to-date behavior to be a separate gate, so that task input declarations are verified independently of caching.
87. As a CI operator, I want build-cache reuse tested in clean and relocated workspaces, so that cached outputs are not tied to an absolute path.
88. As a CI operator, I want byte reproducibility tested in three independent workspaces, so that local task success is not mistaken for deterministic output.
89. As a CI operator, I want generation, analysis, planning, rewriting, and unsigned evidence to be cacheable only after proof, so that cache declarations follow demonstrated purity.
90. As a CI operator, I want signing, final validation, report assembly, and publication to remain noncacheable, so that credentials and mutable release state never enter shared caches.
91. As a CI operator, I want Kaleido to trust the Consumer Project's configured cache rather than introduce its own endpoint, so that cache governance stays with the project.
92. As a release investigator, I want a canonical JSON Artifact Report for every successful variant, so that I can identify exactly what Kaleido produced.
93. As a release investigator, I want deterministic evidence separated from signing and publication evidence, so that reproducibility claims have a clear boundary.
94. As a release investigator, I want one Release Evidence Set identifier to bind unsigned and signed bundle digests, mappings, evidence, and certificate identity, so that artifacts cannot be mixed across candidates.
95. As a release investigator, I want reports created only after successful validation and atomic publication, so that a report never represents a failed or partial build.
96. As a release investigator, I want raw Kaleido, raw R8, composed class, and resource mappings preserved, so that both auditing and crash retracing are possible.
97. As a release investigator, I want the composed mapping to be the default retrace input, so that operational tooling resolves directly from final bundle identities.
98. As a release investigator, I want mapping metadata to record the R8 version, input digests, composer identity, residual signatures, and retrace tool, so that mapping provenance is independently checkable.
99. As a release investigator, I want diagnostics to have stable codes, severity, stage, origin, target, reason, and repair guidance, so that failures are searchable and actionable.
100. As a release investigator, I want diagnostics and reports to redact credentials, raw seeds, user-home paths, host identities, timestamps, durations, and cache outcomes, so that evidence is shareable and reproducible.
101. As an open-source adopter, I want a finite immutable Compatibility Matrix for each Kaleido version, so that I know which AGP, Gradle, JDK, Build Tools, compile SDK, language, and Compose combinations were actually tested.
102. As an open-source adopter, I want semantic versioning to cover plugin behavior, DSL, defaults, diagnostics, report schemas, mappings, and compatibility claims, so that upgrades have predictable meaning.
103. As an open-source adopter, I want deprecated public behavior retained through a documented Bridge Release window, so that I can migrate without an abrupt break.
104. As an open-source adopter, I want new report readers to understand all minor schemas in their major family and the previous major family, so that historical evidence remains inspectable.
105. As an open-source adopter, I want old evidence to remain immutable, so that upgrades never rewrite history.
106. As an open-source adopter, I want installation, DSL, diagnostics, mappings, threat model, migration, performance, license, security, contribution, and release documentation, so that adoption does not depend on private knowledge.
107. As an open-source adopter, I want English documentation to be canonical and any Chinese documentation to stay synchronized, so that translations do not create conflicting contracts.
108. As an open-source adopter, I want the project distributed through the Gradle Plugin Portal with a matching GitHub source release, so that plugin binaries and source provenance are easy to verify.
109. As an open-source adopter, I want source artifacts, dependency verification, an SBOM, a signed tag, and a release manifest, so that the public release has a complete provenance trail.
110. As an open-source adopter, I want truthful attribution for reused Apache-2.0 AabResGuard algorithms and independent implementations for AndroidJunkCode and XmlClassGuard behavior, so that licensing risk is not hidden.
111. As a release approver, I want the same immutable candidate to pass every required gate, so that late rebuilds cannot invalidate earlier evidence.
112. As a release approver, I want product failures to require a new candidate, so that no failed candidate can be waived into publication.
113. As a release approver, I want infrastructure failures rerun only against the same candidate bytes with a recorded reason, so that reruns do not disguise product changes.
114. As a release approver, I want two independent human approvals before public publication, so that release authority is not concentrated in one automated job or person.
115. As a release approver, I want post-publication download, digest verification, and smoke testing, so that the artifact users can fetch is the artifact that passed the gates.
116. As a release approver, I want a bad published version handled by advisory and a new patch version, so that immutable public artifacts are never overwritten.

## Implementation Decisions

### Product and module boundaries

- The MVP is an Android Gradle plugin for public Android application teams. The Sana application is a Reference Consumer and release fixture, not a hidden product dependency or the only supported consumer.
- The public license is Apache-2.0.
- The public adoption surface is deliberately narrow: one plugin ID, one convention-first extension, and the Consumer Project's ordinary release bundle task.
- The implementation must be divided into deep capability modules with small boundaries: Gradle adoption and variant orchestration; immutable domain models and schemas; deterministic generation; protection analysis; class rewrite planning/execution/validation; R8 dictionary and mapping composition; bundle resource planning/execution/validation; signing, evidence, and atomic publication; compatibility fixtures and release verification.
- Internal module names and source layout are implementation choices. Their public behavior, serialized schemas, diagnostics, reports, defaults, and compatibility promises are not.
- Kaleido will not expose a generic transformation-engine SPI, arbitrary executable callbacks, or a raw task API as part of MVP. Domain-specific typed configuration is preferred over shallow extensibility.
- The plugin must replace or finalize the Android Gradle Plugin's single bundle artifact through supported variant artifact APIs. It must not require a parallel user-facing assemble or publish command.
- Public APIs, defaults, diagnostics, report schemas, evidence schemas, mapping formats, and the Compatibility Matrix are versioned surfaces. Internal task names, implementation classes, plan cache keys, and private orchestration are not public surfaces.

### Adoption contract and configuration

- The plugin ID is `com.tongsr.kaleido` and may be applied only after `com.android.application`.
- Each application project that applies Kaleido is processed independently. Every variant whose exact build type is `release` is eligible, including flavored variants.
- Minification is mandatory for every eligible variant. A project with no eligible release variant fails adoption validation.
- The public extension contains typed sections for profile, seed provider, generation, resources, protection, and signing. Compose configuration is nested inside generation and exposes `enabled`, `fileCount`, and `functionsPerFile`.
- With no DSL configuration, the Safe Profile and Safe Defaults v1 apply.
- The Full Profile does not weaken validation. It only unlocks explicitly configured higher-risk capabilities: generated Activities, native or metadata deletion, unused-string replacement, and language filtering.
- Dynamic Features, Asset Packs, Instant Apps, non-application targets, pluginization or hotfix frameworks, confirmed direct external-code loading, confirmed SplitInstall use, and unknown AAB modules are unsupported and fail closed. MVP provides no override for these topology failures.
- Ordinary base-only AAB applications may contain Android libraries, dependencies, and native libraries. Dependency code and resources may be analyzed for references but are not rewritten.
- Topology and configuration validation must finish before any Kaleido mutation begins.
- Configuration uses lazy Gradle providers and serializable value models. No Project, task, variant, service, resolved file collection, or credential object may be captured in configuration-cache state.

### Profiles, seed, and deterministic generation

- Safe Defaults v1 generate under a namespace-derived package ending in `kaleido.generated`.
- Safe Defaults v1 generate four packages, four ordinary classes per package, four methods per class, eight layouts, sixteen XML drawables, and thirty-two strings. They generate no Activities and disable Compose Generator.
- Ordinary generated output counts must remain nonzero after validation. Configurable count ranges must be finite and checked before source generation.
- Generated resource names use `kld_`, followed by an eight-character deterministic hash fragment and a generated suffix. Collision checks include Consumer Project and generated names.
- The default seed is a versioned derivation from normalized application ID and complete variant identity. A consumer may override it with a lazy `Provider<String>`.
- Seed input is normalized with Unicode NFC, encoded as UTF-8, and fingerprinted with SHA-256. Raw seed text is never emitted.
- Every capability derives its own pseudo-random stream from a domain-separated label, the seed fingerprint, the schema/default version, and the relevant normalized inputs.
- Generation is deterministic, produces stable sorted inventories, uses canonical text formatting, and does not depend on clock time, host state, traversal order, absolute paths, cache hits, or worker scheduling.
- Generated ordinary code and resources must compile and merge through standard Android tasks. Kaleido does not maintain a parallel compiler or resource packager.

### Compose Generator

- Compose Generator ships in the first public MVP as an optional AndroidJunkCode sub-capability, not as a separate Core Capability Set family.
- It is disabled by default. Enabling it requires the Consumer Project to have the Compose build feature, Compose compiler plugin, and Compose Runtime already resolvable. Kaleido adds none of them.
- The default enabled configuration generates four files and four composable functions per file.
- `fileCount` accepts 1 through 64, `functionsPerFile` accepts 1 through 32, and their product may not exceed 512.
- Generated composables are internal, top-level, runtime-only functions. Their exact compiled façade, function inventory, and Composer-lowered signatures are recorded for later verification.
- The generated call graph is closed, deterministic, and acyclic. Bodies may contain deterministic pure computations, local conditionals, and calls to other generated functions in that graph.
- Generated Compose code may not reference consumer symbols or use Compose UI, Foundation, Material, Activity Compose, Navigation, tooling, Preview, resources, Android components, startup hooks, state/effects, coroutines, I/O, logging, networking, reflection, or platform APIs.
- Kaleido supplies targeted retention that forbids shrinking of the exact generated compiled inventory while allowing optimization and obfuscation. No static runtime entry point is generated.
- The raw and composed mappings plus final DEX inspection must prove that the expected lowered functions remain.
- Documentation must state that the generator creates no deliberate runtime entry and no static reference from consumer code; it must not claim absolute runtime unreachability.

### Protection model

- A Protection Requirement is a typed declaration over one or more independent dimensions: reachability, original identity, descriptor closure, runtime attributes, resource name, and packaged path.
- Kaleido computes only finite closure required by the declared dimensions and verified references. Protection does not imply an all-dimensions keep unless explicitly requested.
- An Escape Hatch is a typed, bounded exception with a stable ID, human reason, selected dimensions, and an exact-name or bounded-prefix matcher.
- Global matchers, raw ProGuard text, arbitrary callbacks, and unbounded regular expressions are not accepted as Escape Hatches.
- Every Escape Hatch is validated against the variant inventory. Zero-match, conflicting, ambiguous, invalid, or unclosed entries fail.
- A broad but valid bounded Escape Hatch may produce a warning and must be represented in the evidence set.
- Known framework requirements are encoded as versioned typed adapters or guidance only when a release fixture proves their behavior. Unknown reflection or opaque references are not guessed.
- Protected content is hashed before and after every relevant transformation. A protected-name, protected-path, protected-attribute, or protected-byte mismatch is an error.

### Plan-first transformation architecture

- Identity-changing transformations use three phases: inventory and planning, exact execution, and closure validation. Mutation policy must not be embedded in executors.
- Class rewriting consumes a canonical inventory and produces an immutable, versioned `ClassRewritePlan.v1`. Bundle rewriting produces an immutable, versioned `BundleRewritePlan.v1`. Validators produce immutable `TransformReceipt.v1` records.
- Plans include normalized inputs, exact input digests, target inventories, mappings, protection decisions, collision decisions, expected outputs, and schema identity. Receipts bind the plan digest, input digests, output digests, and validation results.
- Repeated fields are sorted canonically. Unknown major schema versions are rejected. Readers may ignore only explicitly allowed unknown minor fields.
- Executors verify all plan input digests before writing and execute exactly the planned mapping. Any input drift, unplanned output, missing planned output, or collision fails.
- Safe degradation is permitted only during planning and before mutation. A candidate may be retained unchanged only when all affected references and protection dimensions are proven closed; otherwise the build fails.
- Algorithms must use streaming or bounded-memory processing where practical and remain O(n log n) or better. Any necessary higher-complexity step requires an explicit bound and performance evidence.

### Class, Manifest, XML, and R8 transformation

- Class rewrite roots are application-owned and Kaleido-generated program classes discovered through references and configuration. Dependencies and protected identities remain unchanged.
- Required Kotlin/JVM identity families are planned together, including nested and synthetic classes, companions, default implementations, lambda and coroutine artifacts when structurally associated, façade and multifile relationships, and metadata-linked identities.
- Target names are deterministic, legal JVM identities derived from domain-separated seed material and normalized class identity. Family suffix relationships such as `$` nesting are preserved where required for structural consistency.
- There is no historical-map input in MVP. Repeated builds derive mappings from current normalized inputs rather than carrying mutable rename history.
- Rewriting covers all structural type surfaces used by supported bytecode: class/member descriptors, generic signatures, annotations and type annotations, exceptions, inner/enclosing metadata, nest host/members, permitted subclasses, records, modules, bootstrap and constant-dynamic references, method handles/types, stack frames, and Kotlin Metadata.
- Opaque strings are never globally replaced. Manifest and compiled or source XML references are rewritten only through a semantic registry of known type-bearing attributes and structures.
- If a structurally relevant surface cannot be parsed or rewritten, Kaleido may retain the complete affected closure only when safety is proven before mutation; otherwise it fails.
- The rewritten class/Manifest output is the input to the standard AGP/R8 stage.
- Kaleido generates deterministic R8 dictionaries and rules. The rules preserve the already transformed identities required by the class plan while allowing R8 to process the remaining program.
- Kaleido records the raw original-to-Kaleido mapping before R8 and the raw R8 mapping afterward. It then composes them into a retrace-compatible original-to-final mapping.
- Protected or retained identities are represented as evidence decisions, not fabricated mapping rows.

### Final AAB resource transformation

- Kaleido owns the final post-AGP AAB resource transformation and final signing step because resource-table names, compiled XML references, resource paths, ZIP entries, signatures, and canonicalization must be changed as one closed operation.
- The resource plan inventories base-module resource table entries, compiled XML references, resource payload paths, ZIP entries, protection requirements, and collision domains.
- Every numeric resource ID is preserved. Kaleido may change only eligible symbolic entry names and packaged resource paths according to the plan.
- Resource table protobuf entries, reference-bearing protobuf values, compiled XML, resource payload paths, and matching ZIP entries are updated consistently.
- Dependency-owned and protected resource identities remain unchanged.
- DEX entries, native libraries, and code-transparency payloads are byte-preserved through the bundle-resource stage.
- Deduplication is permitted only for byte-identical, format-compatible, unprotected base-module resource payloads. It may share payload bytes or paths only where all table and XML references remain valid; it never merges resource IDs or deletes logical resource entries.
- Incompatible or protected duplicates remain separate and may produce a stable warning.
- The unsigned AAB uses deterministic ZIP entry ordering, fixed timestamps, a versioned compression policy, canonical protobuf serialization where rewritten, and no stale signature files, archive comments, or nondeterministic extra fields.
- Final closure validation rejects dangling resource-table references, missing or orphaned resource paths, path collisions, unplanned entries, unexpected modules, protected-content drift, or changes to preserved code/native payloads.

### Hardening Pipeline and publication semantics

- The variant pipeline has ten ordered stages: validate adoption/configuration/R8/signing; generate sources/resources/Manifest fragments/optional Compose; compile and merge; analyze and apply pre-R8 class rewriting while recording the raw Kaleido map; synchronize Manifest references and generate deterministic R8 inputs; run AGP/R8 to obtain the ordinary AAB, raw R8 map, AGP signature, and optional code-transparency data; compose class mappings; rewrite and canonicalize final-bundle resources while emitting the resource map and unsigned candidate; sign and verify the candidate; atomically publish the final AAB and Release Evidence Set.
- Every stage consumes immutable outputs from the prior stage and produces either a fully validated immutable artifact or an error. Stages do not mutate upstream artifacts in place.
- The ordinary AGP-produced AAB is an intermediate input, not the public final artifact.
- The unsigned canonical hardened AAB is the Reproducibility Boundary. Signing and publication bind that exact digest but are outside byte-for-byte reproducibility claims.
- One eligible variant publishes one standard final bundle artifact. No second "hardened bundle" must be manually selected by the consumer.
- A failed pipeline publishes no final bundle, Artifact Report, or partial Release Evidence Set. An early configuration failure may have diagnostics without any report file.
- Atomic publication first validates a staging directory, then exposes the final bundle and complete evidence together. Interrupted or failed publication leaves the prior successful outputs intact or exposes no outputs for a clean build.

### Signing contract

- Signing configuration is selected atomically from the first complete source in this precedence: exact variant DSL, top-level DSL, complete `KALEIDO_UPLOAD_*` environment source, complete `kaleido.uploadSigning.*` Gradle-property source.
- Fields from different signing sources are never merged. A partially present higher-precedence source fails with a diagnostic rather than falling through.
- Credential providers and keystore files are resolved only during execution. Secrets, passwords, key aliases where sensitive, credential paths, and provider objects are excluded from cacheable inputs, reports, diagnostics, build scans under Kaleido's control, and serialized configuration state.
- The expected signing certificate identity is captured as a non-secret digest. The final AAB must verify complete signature coverage and match that identity.
- Where code transparency is present, its preserved material and relationship to the final bundle are explicitly validated and reported.

### Mappings, diagnostics, reports, and evidence

- Each successful variant emits four canonical mapping artifacts: raw Kaleido class mapping, raw AGP/R8 mapping, composed original-to-final class mapping, and resource mapping.
- The composed class mapping is retrace-compatible and is the documented default for crash analysis. Raw mappings remain audit inputs.
- Mapping metadata records schema, variant identity, Kaleido version, R8 version and mapping identity when available, source and intermediate mapping digests, composer identity, residual signatures, output digest, and compatible retrace tool information.
- Diagnostics have `ERROR`, `WARNING`, or `INFO` severity and stable codes in the form `KLD-<DOMAIN>-NNN`.
- Diagnostics carry project identity, complete variant identity, pipeline stage, origin, target, reason, and actionable repair guidance when applicable.
- Errors include invalid configuration or topology, unclosed reference/protection state, plan/input/output mismatch, protected-content drift, missing evidence, signature or certificate mismatch, bundle validation failure, code-transparency failure, and reproducibility conflict.
- Warnings are limited to pre-mutation safe retain/skip decisions, valid broad Escape Hatches, incompatible dedup candidates kept separate, and unconfirmed dynamic-loading signals. Errors cannot be suppressed or demoted.
- Diagnostic identity and meaning are stable independently of Gradle's Problems API. Kaleido may additionally surface compatible problems without making that API the evidence contract.
- Ordinary paths are normalized to project-relative form. Credentials, raw seeds, credential paths, user-home paths, host/user identity, wall-clock timestamps, durations, worker order, and cache outcomes are redacted or omitted.
- Every successful variant emits one canonical UTF-8, LF-terminated JSON Artifact Report.
- Artifact Report v1 has top-level sections for schema, identity, deterministic evidence, publication evidence, diagnostics, and proof limitations.
- Identity includes Kaleido version, project and variant identity, toolchain and Compatibility Matrix row, profile/default version, normalized input identity, and Release Evidence Set ID.
- Deterministic evidence binds generated trees, immutable plans and receipts, R8 dictionaries and rules, raw and composed mappings, resource map, transformed unsigned AAB, protected-content proofs, and their digests.
- Publication evidence binds the same unsigned digest to the signed AAB digest, signing certificate digest, signature validation, bundle validation, optional code-transparency validation, publication target, and public release manifest when applicable.
- The Release Evidence Set ID is a SHA-256 digest of a canonical manifest containing complete variant identity, unsigned and signed AAB digests, mapping digests, deterministic-evidence digest, and certificate digest. It excludes time, host/path state, and its own digest.
- Proof limitations explicitly distinguish static closure, runtime-quiet generation, reproducible unsigned bytes, and signing verification from claims Kaleido does not make.

### Reproducibility, incrementality, and caching

- Byte reproducibility covers the generated source/resource tree, immutable plans and receipts, deterministic R8 dictionary/rules, raw and composed mappings, resource map, canonical unsigned AAB, and deterministic portion of the report/evidence set.
- Reproducibility requires the same normalized source/resource inputs, dependencies, application/variant identity, effective DSL and Safe Defaults version, seed fingerprint, toolchain, Compatibility Matrix row, and Kaleido version.
- Signed AAB bytes are not generally claimed reproducible. Publication evidence must nevertheless prove that the signed AAB contains and signs the exact reproducible unsigned candidate.
- Configuration-cache correctness, no-clean up-to-date correctness, build-cache portability, and byte reproducibility are four independent properties and four independent gates.
- Generation, inventory/protection analysis, plan creation, dictionary/rule generation, class/Manifest rewrite, mapping composition, resource planning/rewrite, and unsigned deterministic evidence may be cacheable only after their declared inputs and path independence are proven.
- Credential resolution, signing, final validation, report assembly that includes publication evidence, and atomic publication are noncacheable.
- Kaleido uses the Consumer Project's configured local or remote Gradle build cache and introduces no Kaleido-operated cache service.
- Incrementality is task-level in MVP: unchanged complete inputs may reuse an output; partial per-file mutation is not promised unless a module can prove identical closure and evidence semantics.
- Cache format changes naturally miss through implementation and schema fingerprints. Kaleido does not migrate or reinterpret old private cache entries.

### Compatibility and upgrade contract

- Every public Kaleido version publishes one finite immutable Compatibility Matrix. Only exact rows that passed release gates are supported.
- The mandatory first-public-MVP rows are AGP 9.2.1 with Gradle 9.4.1 and AGP 9.3.2 with Gradle 9.5.0, both on Linux x86_64 with JDK 17, Android Build Tools 36, and compile SDK 36.
- Each mandatory row covers built-in Kotlin, Java-only, and Compose Consumer Projects. Additional operating systems, JDK 21, preview tools, and extra AGP/Gradle pairs are forward-looking, nonblocking evidence unless promoted to an exact supported row.
- Semantic versioning governs the plugin/DSL, default behavior, Compatibility Matrix, diagnostics, report and evidence schemas, and mapping contract.
- Additive compatible behavior may arrive in a minor version. Breaking public behavior requires a major version.
- Deprecations remain functional for at least the next non-preview minor release and ninety days, and are removed only in a major version. A Bridge Release supplies migration diagnostics and replacement guidance before removal.
- Artifact Report schemas use a `major.minor` family and URI-identified dialect. A reader supports every minor in its own major and the immediately previous major.
- Historical evidence is immutable. A new reader may create a separately identified derived view but may not rewrite old evidence or reuse its identity as though unchanged.
- Compatibility, deprecation, schema, and migration diagnostics have distinct stable code groups.

### Public release, provenance, and maintenance

- The project must contain canonical English installation, quick-start, profile/default, DSL, compatibility, diagnostics, report/evidence, mapping/retrace, threat-model, performance/size, migration, license/provenance, security, contribution, changelog, and release-note documentation before publication.
- Chinese documentation is optional. If published, it must be generated or reviewed against the canonical English contract and must not introduce divergent requirements.
- AndroidJunkCode-equivalent and XmlClassGuard-equivalent behavior is independently implemented without copying source, templates, or generated output because their usable source-license basis is absent or ambiguous.
- AabResGuard algorithms may be selectively reused under Apache-2.0 only with content-based attribution, clear records of adapted components and changes, preserved notices, and source-level provenance.
- The release includes LICENSE, content-based NOTICE and third-party notices, a machine-readable dependency and source inventory, dependency verification metadata, source artifacts, a CycloneDX 1.7 SBOM, a signed source tag, and an immutable release manifest.
- The project must not claim SLSA provenance unless the release process actually satisfies the claimed level and emits the required attestations.
- The first distribution channels are the Gradle Plugin Portal and a matching GitHub source release. Maven Central and Google Play distribution are not part of MVP.
- Plugin validation and a publication dry run occur before promotion. After Portal publication, automation downloads the public plugin, verifies hashes against the release manifest, and runs a marker-based consumer smoke test.
- Every release gate operates on one immutable Kaleido Release Candidate. A product failure requires a new versioned candidate. A proven infrastructure failure may rerun only the same bytes and must record the cause.
- Two independent human approvals are required after automated evidence is complete and before public promotion.
- Public versions are immutable. A defective publication is handled by advisory, deprecation guidance where supported, and a new patch version; it is never overwritten.

## Testing Decisions

### Testing philosophy and primary seam

- Good tests assert external behavior and evidence, not private task names, internal class layouts, incidental archive ordering before canonicalization, or implementation-specific library calls.
- The primary and highest-value seam is a real Consumer Project applying `com.tongsr.kaleido`, invoking its ordinary release bundle task, and observing either one valid atomically published hardened AAB with a complete Release Evidence Set or one stable actionable failure with no partial publication.
- That Consumer Project seam is the default for integration, regression, compatibility, cache, reproducibility, and release tests. New lower seams are allowed only for deterministic algorithms that would be prohibitively slow or ambiguous to diagnose solely through a complete Android build.
- Internal lower seams are limited to the existing immutable file-artifact boundaries: adoption/configuration model, `ClassRewritePlan.v1`, `BundleRewritePlan.v1`, `TransformReceipt.v1`, canonical mappings, and canonical report/evidence documents.
- Plan/executor tests supply serialized plans and real miniature bytecode, resource-table, XML, or ZIP inputs, then assert receipts and structural outputs. They do not reach into executor-private collections or traversal order.
- Algorithm unit tests cover pure normalization, seed derivation, naming, collision resolution, closure, mapping composition, canonical serialization, digesting, and bounded-match semantics. Their assertions are based on documented inputs and outputs.
- The current repository has only template Android unit and instrumentation tests; it has no substantive prior Kaleido testing harness. Therefore the real Consumer Project fixtures and immutable artifact schemas established by this spec become the testing prior art for subsequent capabilities.

### Consumer fixtures and behavior coverage

- Provide marker-consumed temporary Maven repository fixtures so tests consume the packaged plugin in the same way as external users rather than applying implementation classes directly.
- Maintain at least three primary Consumer Project fixtures: Java-only Safe Profile, built-in Kotlin Safe Profile, and minified Full Profile with default Compose Generator enabled.
- Maintain a Sample App fixture that runs on every supported Compatibility Matrix row and demonstrates the public adoption contract without private test-only configuration.
- Maintain the Sana Reference Consumer on the two mandatory rows using dedicated test signing credentials and no production secrets.
- Primary fixtures include ordinary libraries, third-party dependencies, native libraries, representative Manifest/XML references, Kotlin metadata surfaces, resource qualifiers, resource aliases/references, and byte-identical and incompatible resource payloads.
- Full+Compose fixtures assert exact generated source inventory bounds, compilation, mapping presence, Composer-lowered DEX retention, absence of generated components/resources/startup hooks, and absence of consumer-code references to generated composables.
- Class-rewrite fixtures cover nested and synthetic classes, companions, default implementations, file façades and multifile classes, annotations, generic signatures, records, nests, sealed/permitted types where supported, method handles, bootstrap constants, and Kotlin Metadata.
- Resource fixtures cover table entry renaming, compiled XML references, file paths, qualifiers, aliases, styles, drawables, string references, protected resources, byte-identical dedup candidates, incompatible duplicates, and collision domains.
- Signing fixtures cover each complete source and precedence position, exact-variant override, top-level fallback, environment fallback, Gradle-property fallback, partial higher-precedence failure, wrong certificate, corrupted signature, and secret redaction.

### Negative and fail-closed coverage

- Test application of Kaleido to a non-application target, wrong plugin order, no release variant, release without minification, invalid count bounds, invalid Compose prerequisites, and incomplete signing configuration.
- Test Dynamic Features, Asset Packs, Instant Apps, extra AAB modules, confirmed SplitInstall, confirmed external-code loading, and representative hotfix/plugin frameworks. Each must fail before Kaleido mutation and publish no final evidence set.
- Test unresolved reflection, unsupported opaque bytecode/XML surfaces, malformed Kotlin Metadata, plan input-digest drift, executor output drift, rename/resource collision, and unknown plan major versions.
- Test zero-match, conflicting, ambiguous, unbounded, and unclosed Escape Hatches. Test that no error can be demoted or suppressed.
- Test dangling Manifest, bytecode, resource-table, compiled-XML, and ZIP-path references after intentional fixture corruption.
- Test protected class/resource/path/attribute/byte changes and confirm each produces a stable error and no final artifact.
- Test unexpected DEX, native, or code-transparency byte changes during the resource stage.
- Test signature coverage, certificate identity, bundletool validation, code-transparency validation when enabled, report/evidence digest mismatch, and atomic-publication interruption.
- Test that failures before report creation leave no stale report from the current invocation and do not overwrite a prior successful publication.

### Determinism, cache, and reproducibility gates

- Configuration-cache gate: run each mandatory fixture twice with configuration cache enabled, require reuse on the second invocation, and reject configuration-time credential resolution or unsupported captured state.
- Up-to-date gate: run each fixture twice without build cache, require Kaleido deterministic tasks to be up-to-date on the second invocation, and require final noncacheable publication validation to behave correctly.
- Build-cache gate: fill a cache, rebuild from a clean checkout, and rebuild from a relocated checkout. Cacheable deterministic outputs must be reused and remain byte-identical; signing and publication must execute outside the cache.
- Byte-reproducibility gate: build the same normalized variant in three independent workspaces and compare every artifact inside the Reproducibility Boundary byte for byte.
- Reproducibility tests vary absolute workspace path, file discovery order, worker count, locale, timezone, and cache hit/miss state while keeping normalized contract inputs fixed.
- Complementary tests change exactly one declared input, seed, default/schema version, toolchain row, dependency, protection rule, or profile option and assert that affected fingerprints and outputs change without cross-variant contamination.
- Canonical JSON, protobuf, mapping, text, and ZIP tests include golden artifacts at public schema boundaries. Goldens are reviewed as contract changes and are never updated merely to make a failing test pass.
- Property tests cover legal-name generation, collision freedom, one-to-one class mapping, resource-ID preservation, closed reference graphs, deterministic sorting, canonical round trips, mapping composition, and bounded Escape Hatch matching.

### Compatibility, runtime, performance, and size gates

- Every exact Compatibility Matrix row must build the packaged plugin through marker resolution for Java Safe, Kotlin Safe, and minified Full+Compose fixtures.
- The mandatory AGP 9.2.1/Gradle 9.4.1 and AGP 9.3.2/Gradle 9.5.0 rows receive exhaustive positive, boundary, and negative coverage. Optional rows receive only their declared smoke coverage and cannot silently become supported.
- Every row validates the final AAB with the pinned bundletool version and generates APK sets for representative device specifications.
- Mandatory rows install and start controlled fixture APKs and execute a core smoke path on controlled devices. This validates package launch, resources, native loading where present, and absence of generated startup behavior.
- Sana runtime checks use test infrastructure and test credentials only. They do not require production signing or publication access.
- Performance measurements run on dedicated Linux x86_64 workers after two warmups and five measured samples; the median is compared against the same project without Kaleido.
- Safe Profile clean-build overhead must not exceed the larger of 20 percent or 45 seconds. Full Profile with default Compose Generator must not exceed the larger of 30 percent or 90 seconds.
- Warm no-clean overhead must not exceed the larger of 10 percent or 15 seconds. Peak memory overhead must not exceed the larger of 25 percent or 512 MiB.
- Sana bundle growth must not exceed the larger of 1 percent or 1 MiB for Safe Profile, 2 percent or 2 MiB for Full with default Compose, and 5 percent or 5 MiB for the maximum 512-function Compose configuration.
- Sample App bundle growth must not exceed 2 MiB for Safe Profile and 4 MiB for Full with default Compose. The published plugin JAR must not exceed 10 MiB, and newly introduced resolved dependencies must not exceed 50 MiB.
- Performance tests include adversarial large class/resource inventories and collision patterns to detect quadratic behavior. A threshold failure is a product failure, not a waivable warning.

### Provenance, documentation, and publication gates

- Automated license tests inventory source origins, resolved dependencies, generated notices, SBOM entries, source artifacts, and release manifest entries.
- A content-similarity audit checks AndroidJunkCode-equivalent and XmlClassGuard-equivalent implementation, templates, and generated samples for accidental copying.
- AabResGuard-derived algorithm tests verify required copyright/license notices, adapted-component inventory, and change records.
- Documentation tests build every published installation and DSL example against the candidate and validate links, schema examples, diagnostic references, Compatibility Matrix rows, and retrace instructions.
- Security review verifies the threat model, secret redaction, dependency verification, credential isolation, provenance statements, and absence of unsupported store-evasion claims.
- Publication rehearsal runs plugin validation and a Portal dry run against the exact candidate. The final public job accepts only the candidate digests already approved.
- Post-publication verification resolves the plugin from the public Portal, verifies hashes against the release manifest, builds a clean smoke Consumer Project, validates its final AAB and evidence, and compares the public source release/tag to the manifest.
- Two independent reviewers attest that automated evidence, provenance, documentation, and candidate identity are complete before the publication credential can be used.

## Out of Scope

- This specification does not itself implement, publish, sign, or release Kaleido. It defines the implementation target and acceptance contract.
- Store-review evasion, repetitive-content circumvention, application unlinkability, anti-analysis guarantees, malicious-behavior concealment, and any promise of Google Play or other store approval are explicitly out of scope.
- AGP 8 support, APK hardening, Dynamic Features, Asset Packs, Instant Apps, runtime pluginization, hotfix frameworks, and direct external-code loading are outside the MVP support envelope.
- A general-purpose bytecode/resource transformation engine, public engine SPI, arbitrary executable extension callback, and raw ProGuard bypass are out of scope.
- Compose UI generation, previews, screens, navigation, resources, Activities, services, receivers, providers, startup hooks, state/effects, or deliberate runtime invocation are out of scope. Compose Generator remains runtime-only AndroidJunkCode content.
- Rewriting third-party dependency code or resources, changing numeric resource IDs, semantic resource merging, and cross-protection-boundary deduplication are out of scope.
- Historical rename-map migration, partial per-file incremental rewriting, Kaleido-operated remote cache services, and cache-entry migration are out of scope.
- Absolute runtime-unreachability proofs, byte-identical signed AAB guarantees, and false or unverified supply-chain assurance claims are out of scope.
- Maven Central and Google Play distribution are out of scope for the first public MVP.
- User accounts, cloud control planes, telemetry collection, paid plans, licensing servers, and enterprise policy management are out of scope.
- Additional Compatibility Matrix rows, macOS or Windows support, JDK 21, preview Android tools, and extra AGP/Gradle pairs remain nonblocking research evidence until a later release explicitly promotes them.
- Production Sana credentials, production publication credentials, and any credentials invented by an implementation agent are out of scope.

## Further Notes

- This specification synthesizes the decision-complete Kaleido MVP map and accepted architecture decisions. It is the handoff contract for implementation agents and is labeled `ready-for-agent`; it is not evidence that the described product already exists.
- The current repository baseline is a template Sample App with domain and architecture documentation. It has no Kaleido plugin implementation, compatibility fixture suite, CI/release pipeline, public license/provenance package, or Git metadata. Implementation must establish those capabilities rather than infer that template behavior satisfies the spec.
- The exact implementation sequence may be incremental, but each intermediate slice must preserve the final public contract. A sensible dependency order is adoption and schemas; deterministic generation; Compose Generator; protection and class planning; class/Manifest rewriting and mapping composition; final-bundle resource planning and rewriting; signing/report/evidence publication; then complete compatibility and public-release gates.
- The highest testing seam has already been fixed by the accepted decisions: a real Consumer Project performs its normal release bundle build and receives one atomic Release Evidence Set. Lower seams exist only at immutable versioned artifact boundaries needed to test deterministic transformations precisely.
- Public publication depends on external prerequisites that an implementation agent must not invent: an authoritative Git remote, Gradle Plugin Portal identity and credential, public release repository, test signing assets, controlled CI runners/devices, Sana Reference Consumer access, and two independent human approvers.
- If a future implementation discovery contradicts a public decision in this specification, the agent must stop and return the conflict to planning. It must not silently narrow the Core Capability Set, remove Compose Generator from the first MVP, weaken a fail-closed rule, widen a license claim, or downgrade a release gate.
