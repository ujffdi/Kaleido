# Define the adoption interface and Profiles

Type: grilling
Status: resolved
Blocked by: 05, 16

## Question

What is the minimum plugin application and DSL surface for a zero-configuration Safe Profile, an explicit Full Profile, per-capability controls, keep rules, deterministic inputs, and actionable diagnostics?

## Answer

The Adoption Contract has one stable plugin ID, one optional managed DSL, and one execution path:

```kotlin
plugins {
    id("com.android.application")
    id("com.tongsr.kaleido")
}

kaleido {
    profile.set(KaleidoProfile.FULL)
    seed.set(providers.gradleProperty("kaleido.seed"))

    generation { /* declarative code/resource, Activity, and Compose intent */ }
    resources { /* Full-only destructive resource intent */ }
    protection { /* typed, bounded Escape Hatches */ }
    signing { /* atomic Provider-backed upload identity */ }
}
```

`com.android.application` must be applied first. The Consumer Project runs its normal `bundle<ReleaseVariant>` command; the Interface exposes no engine enable switches, upstream types, AGP artifacts, task names/order, arbitrary callbacks, raw ProGuard Escape Hatches, or manual orchestration. All declarations normalize into a versioned immutable Adoption Plan per eligible Release variant before the fixed Hardening Pipeline executes.

Applying the plugin without a `kaleido {}` block is complete Safe Profile adoption, not a no-op. Safe Defaults v1 run every capability family with generated package `<namespace>.kaleido.generated`, four packages, four ordinary classes per package, four methods per class, eight layouts, sixteen XML drawables, and thirty-two strings. Generated Activity count is zero, Compose Generator is disabled, and the resource prefix is the deterministic `kld_<8-char-hash>_` derived from the root seed. Mandatory ordinary class/method/layout/drawable/string counts remain at least one.

The default root seed is derived by a versioned stable policy from normalized application ID and exact variant identity; time, random state, locale, and host paths are excluded. One nonblank `Provider<String>` may replace the root seed. Kaleido internally derives variant/capability/name-domain sub-seeds; no per-engine seed Interface exists.

`profile` defaults to `SAFE`. `FULL` unlocks but never implicitly enables generated Activities, file deletion, confirmed-unused-string replacement, or language filtering. Declaring a Full-only property under Safe fails. Both Profiles always run the Core Capability Set and share the same Protection Requirements and fail-closed behavior.

The generation Interface exposes only stable declarative dimensions: generated package base, package/class/method counts, generated-resource prefix and layout/drawable/string counts, Full-only Activity count per package, and the explicit Compose opt-in. Activity generation always produces non-exported Activities without intent filters and coordinated application-child Manifest entries. The Compose block's detailed source/dependency/retention properties remain owned by its dedicated contract; neither Profile enables it implicitly.

Resource entry/directory/path obfuscation and within-base byte-identical resource merging are fixed mandatory behavior with no public disable switch. Full-only typed controls may delete selected native libraries, delete permitted `META-INF` metadata, replace strings from an explicit confirmed-unused input file with Kaleido's fixed replacement, and retain the default language configuration plus explicitly named locales. Arbitrary AAB-path deletion is absent. Zero-match destructive declarations, illegal paths/locales, or operations against a Protection Requirement fail.

The protection Interface has type-specific `classes("stable-id")` and `resources("stable-id")` declarations. Each Escape Hatch requires a unique stable ID, a sealed exact or bounded selector, applicable Protection Requirement dimensions, and a nonblank reason. Requirements remain additive; global patterns, arbitrary matchers, raw rule text, zero matches, and conflicts fail as defined by the protection contract.

Upload signing is one atomic identity: keystore file, store password, key alias, key password, store type, and expected upload-certificate SHA-256. Resolution selects the first applicable complete source in this order: exact-variant explicit declaration, top-level explicit declaration, complete environment convention, complete Gradle-property convention. Exact variants are named literally and validated; wildcard variant overrides and per-variant Profile/capability overrides are absent. A selected or partially present source never fills missing fields from a lower source.

The environment convention is `KALEIDO_UPLOAD_STORE_FILE`, `KALEIDO_UPLOAD_STORE_PASSWORD`, `KALEIDO_UPLOAD_KEY_ALIAS`, `KALEIDO_UPLOAD_KEY_PASSWORD`, `KALEIDO_UPLOAD_STORE_TYPE`, and `KALEIDO_UPLOAD_CERT_SHA256`. The Gradle-property convention uses the corresponding `kaleido.uploadSigning.storeFile`, `.storePassword`, `.keyAlias`, `.keyPassword`, `.storeType`, and `.certificateSha256` names. Secrets remain lazy Providers resolved only during non-cacheable signing execution and never enter diagnostics or evidence.

Managed declarations are order-independent. Duplicate singleton declarations, unknown exact variants, Profile violations, conflicts, and invalid values fail rather than using last-write-wins behavior. Stable diagnostics always identify a code, Consumer Project, variant, declaration path or detected source, and actionable repair; errors cannot be suppressed or demoted, standard Gradle log levels control verbosity, and the Artifact Report is mandatory.

See [`ADR-0006`](../../../docs/adr/0006-expose-convention-first-adoption-contract.md).

## Comments

- Product input: Compose Generator is configured as an AndroidJunkCode-equivalent generation sub-capability, must be an explicit configuration choice, and remains disabled when omitted; it is not enabled merely by selecting Safe Profile or Full Profile.
- Interface evidence: three independent `codebase-design` candidates compared a minimal managed DSL, an explicit capability graph, and typed progressive-disclosure clauses. The accepted direction combines conventional Gradle discovery with an immutable per-variant Adoption Plan behind the seam.
- Decision round 1: the Adoption Contract is `com.tongsr.kaleido`, one optional `kaleido {}` managed DSL, and the normal `bundle<ReleaseVariant>` command. It exposes product intent rather than upstream engines, AGP artifacts, task ordering, callbacks, or manual orchestration.
- Decision round 1: applying the plugin with no DSL selects Safe Profile, runs all four capability families, and leaves Compose Generator disabled. Zero configuration means zero behavior DSL, not absence of upload-signing material; missing signing Providers fail only when a target Release build executes.
- Decision round 1: the default root seed is derived by a versioned stable policy from normalized application ID and exact variant identity. A single explicit `Provider<String>` may replace it; capability/variant naming domains derive isolated sub-seeds internally and per-engine seeds are not public.
- Decision round 1: top-level behavior applies to every eligible Release variant. The MVP permits only complete signing-input overrides for exact variant names; wildcard variant selection and per-variant Profile/capability overrides are excluded.
- Decision round 1: diagnostics have stable codes, variant and declaration context, resolved target counts, and actionable fixes. Errors cannot be suppressed or demoted, Artifact Report emission is mandatory, and verbosity uses normal Gradle logging rather than a diagnostics DSL.
- Decision round 2: `profile` defaults to Safe. Full only unlocks Full-only declarations and never enables them implicitly; using one under Safe fails instead of being ignored or silently changing Profile. The four capability families have no public enable/disable switches.
- Decision round 2: `generation` exposes stable package/class/method counts, generated-resource prefix and layout/drawable/string counts, Full-only Activity count, and a Compose opt-in whose detailed properties remain owned by the Compose contract. Safe conventions are nonzero for its mandatory ordinary code/resource outputs; Full Activity count remains zero until explicitly configured.
- Decision round 2: resource entry/directory/path transformation and within-base duplicate merging are fixed behavior. Only bounded file deletion, confirmed-unused-string input, and retained-language selection are public Full-only resource declarations, and none can override a Protection Requirement.
- Decision round 2: Escape Hatches use type-specific class/resource blocks with a unique stable ID, sealed selector and dimension types, and a required reason. Arbitrary matchers and raw ProGuard text are not Kaleido Protection declarations.
- Decision round 2: upload signing is one atomic identity containing store file, passwords, alias, store type, and expected certificate SHA-256. Resolution precedence is a complete exact-variant declaration, then a complete top-level declaration, then one complete convention set; a selected layer cannot inherit missing fields from a lower layer.
- Decision round 2: the managed DSL is order-independent and Provider-backed. Duplicate singleton declarations, unknown exact variants, Profile violations, and conflicting values fail rather than using last-write-wins behavior; every Release variant consumes a versioned immutable Adoption Plan.
- Decision round 3: Safe Defaults v1 derive the generated package from `<namespace>.kaleido.generated`, use `4` packages, `4` ordinary classes per package, `4` methods per class, `8` layouts, `16` XML drawables, and `32` strings, keep Activity count at zero, keep Compose disabled, and derive a collision-resistant `kld_<8-char-hash>_` resource prefix from the root seed. Each mandatory ordinary generation count remains at least one.
- Decision round 3: zero-DSL upload signing first selects the complete `KALEIDO_UPLOAD_*` environment-variable set, otherwise the complete `kaleido.uploadSigning.*` Gradle-property set. A partially present higher-priority set fails and never mixes secrets or identity fields across sources; secrets resolve only in the signing task.
- Decision round 3: Full-only file deletion is limited to typed native-library and permitted `META-INF` metadata selectors. Confirmed-unused string replacement consumes an explicit file Provider and uses a fixed replacement; language filtering always retains the default configuration plus explicit locales. Zero-match, protected-target, illegal-path, and unknown-locale declarations fail.
