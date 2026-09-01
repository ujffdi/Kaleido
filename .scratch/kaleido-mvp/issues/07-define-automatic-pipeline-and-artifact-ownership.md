# Define the automatic pipeline and artifact ownership

Type: grilling
Status: resolved
Blocked by: 03, 04, 05, 14

## Question

What ordered Hardening Pipeline runs during a normal Release AAB build, which stage owns each transformed artifact, and how are task dependencies, signing, caching, reproducibility, and final output location made unambiguous?

## Answer

A normal Release `bundle<Variant>` invocation automatically traverses one public AGP artifact chain; Kaleido exposes no manual guard orchestration and publishes one indivisible Release Evidence Set.

1. Validate the Release variant, R8 requirement, declared configuration, and provider-backed signing prerequisites.
2. Generate AndroidJunkCode-equivalent Java/resources/Manifest inputs and explicitly enabled Compose Generator sources under `build/`, then register them through AGP variant source APIs.
3. Let AGP compile and merge the Consumer Project and generated inputs.
4. Analyze supported non-bytecode references, deterministically rename eligible Application-module classes through the scoped class artifact, and emit the raw Kaleido class mapping.
5. Rewrite `MERGED_MANIFEST` with that mapping, generate isolated deterministic R8 dictionaries and coordinated retention rules, and make R8 preserve Kaleido-transformed target names.
6. Let AGP/R8 minify, dex, emit its raw mapping, build the ordinary AAB, add optional code transparency, and apply the initial upload-key JAR signature.
7. Compose original-to-Kaleido and Kaleido-to-R8 mappings into the Consumer Project's original-to-final class mapping while retaining both raw mappings.
8. Transform the public `SingleArtifact.BUNDLE` through a deterministic Bundle Rewrite module: rewrite compiled XML with the class mapping, transform ResourceTable names/paths and configured optimizations, emit resource mapping, strip invalidated JAR signature metadata, byte-preserve all DEX/native/CT entries, and output an unsigned candidate AAB.
9. Run a non-cacheable Finalize Kaleido Bundle module with Kaleido-owned provider-backed signing inputs; sign the candidate, verify complete JAR coverage and expected upload certificate, run `bundletool validate`, verify code-transparency content/identity when present, and check the Release Evidence Set.
10. Move the validated final AAB into the standard downstream `SingleArtifact.BUNDLE` output atomically on the same filesystem and publish its raw/composed mappings and Artifact Report under `build/outputs/kaleido/<variant>/`. The AGP-signed input remains an internal artifact, never a second publishable bundle.

Every Kaleido stage hands downstream stages immutable, versioned, machine-readable file outputs declared through Gradle properties; BuildService memory is never authoritative state. Generation, analysis, class/Manifest transformation, dictionary/mapping work, and unsigned Bundle Rewrite may claim Build Cache only after repeated-build proof. R8 remains AGP-owned. Re-signing, validation, and publication are explicitly non-cacheable, and reproducibility compares transformed unsigned content separately from expected signing variability.

The MVP never accesses AGP internal signing credential providers and never regenerates code transparency. It resolves Consumer-supplied signing providers only during execution, excludes secrets from every output and diagnostic, preserves and hashes every DEX, `lib/**/*.so`, and existing CT JWT across Bundle Rewrite, and fails closed on any protected-code change. `bundletool build-apks` remains a release-acceptance/CI gate rather than part of every developer bundle build.

Evidence: [`agp9-artifact-seams.md`](../research/agp9-artifact-seams.md) and [`final-bundle-signing-and-code-transparency.md`](../research/final-bundle-signing-and-code-transparency.md).

## Comments

- Fixed pipeline skeleton: generate additional code/resources and Manifest contributions; compile and merge; coordinate class renaming with Manifest/XML references and deterministic R8 dictionary inputs; run R8 and retain its mapping; build the AAB; apply final-AAB resource obfuscation/optimization; re-sign and verify; publish the final AAB, mappings, and Artifact Report.
- Ticket 14 fixes ownership of the final-AAB transform and re-signing. This ticket must still define the exact ordering and mapping hand-off between XmlClassGuard-equivalent processing and R8.
- Decision round 1: Kaleido deterministically renames eligible Application-module classes before R8 through the scoped class artifact, emits its own class mapping, and supplies coordinated rules that preserve those transformed names while R8 handles the remaining classes.
- Decision round 1: one deep final `BUNDLE` transform seam contains a deterministic, cacheable Bundle Rewrite module followed by a provider-backed, non-cacheable Finalize Kaleido Bundle module that signs, validates, and atomically publishes only a valid result.
- Decision round 1: stages exchange immutable, versioned, machine-readable file artifacts declared as Gradle inputs and outputs; BuildService state is never the authoritative mapping or report source.
- Decision round 1: Kaleido replaces the downstream identity of the normal `SingleArtifact.BUNDLE`; the AGP-signed input is internal, the validated Kaleido AAB is the sole publishable bundle at the standard output location, and mappings/reports live under `build/outputs/kaleido/<variant>/`.
- Decision round 2: retain the raw Kaleido class mapping, raw R8 mapping, composed original-to-final class mapping, and resource mapping. The composed mapping is the Consumer Project's primary lookup while raw mappings remain audit and diagnosis evidence; Compose Generator classes participate in the same chain.
- Decision round 2: transform `MERGED_MANIFEST` before packaging with the Kaleido class mapping, preserve those transformed names through R8, rewrite compiled layout/navigation/XML only in the final Bundle Rewrite, and validate the packaged Manifest against the same mapping. Unmapped, conflicting, or unprovably safe references fail closed.
- Decision round 2: deterministic generation, analysis, transformation, dictionary, mapping, and unsigned Bundle Rewrite stages become cacheable only after repeated-build proof. R8 remains AGP-owned; re-signing, final validation, and atomic publication are explicitly non-cacheable, and reproducibility compares transformed unsigned content separately from signing variability.
- Decision round 2: the final AAB, raw stage mappings, composed mapping, and Artifact Report form one Release Evidence Set. Missing or inconsistent evidence prevents publication even when the candidate AAB itself validates.
- Evidence for round 3: [`final-bundle-signing-and-code-transparency.md`](../research/final-bundle-signing-and-code-transparency.md) proves the post-BUNDLE upload-signing boundary, code-transparency coverage, public credential limitation, and available pre-publication gates from AGP, Android, bundletool, Gradle, and JDK primary sources.
- Decision round 3: Kaleido owns explicit provider-backed AAB re-signing inputs and never accesses AGP internal resolved credential providers. Secrets resolve only in the non-cacheable signing execution path, never enter outputs or diagnostics, and missing inputs or an unexpected upload-certificate fingerprint fail the Release build; exact DSL names belong to the adoption-interface decision.
- Decision round 3: the MVP preserves rather than regenerates code transparency. Bundle Rewrite must byte-preserve every DEX, `lib/**/*.so`, and existing CT JWT, compare protected paths and hashes before/after, validate CT content and expected identity when present, and fail closed on any change without accepting a CT private key.
- Decision round 3: every normal `bundleRelease` verifies full JAR signature coverage, expected upload-certificate fingerprint, `bundletool validate`, conditional CT content/identity, Release Evidence Set completeness, and same-filesystem atomic publication. `bundletool build-apks` is a heavier release-acceptance/CI gate rather than part of every developer bundle build.
