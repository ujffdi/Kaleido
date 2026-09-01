# 20 — Validate the supported adoption topology

**What to build:** A Consumer Project receives a deterministic, fail-closed adoption decision before Kaleido mutates any release input, including clear acceptance of supported base-only AAB applications and actionable rejection of unsupported module or dynamic-code topologies.

**Blocked by:** 19 — Establish the packaged plugin and Consumer Project seam.

**Status:** resolved

- [x] Accept independently configured Android application projects and every variant whose exact build type is `release`, including flavored release variants.
- [x] Require minification for every eligible variant and fail when no eligible release variant exists.
- [x] Accept ordinary Android libraries, third-party dependencies, and native libraries as analysis-only inputs.
- [x] Reject non-application targets, Dynamic Features, Asset Packs, Instant Apps, unknown AAB modules, confirmed SplitInstall use, confirmed direct external-code loading, and representative pluginization or hotfix frameworks.
- [x] Perform topology and prerequisite validation before any generated or transformed output is written.
- [x] Emit stable `KLD-<DOMAIN>-NNN` diagnostics containing variant, stage, origin, target, reason, and repair guidance without absolute or user-home paths.
- [x] Unsupported topology errors cannot be suppressed, demoted, or bypassed through a global override.
- [x] Integration tests assert that rejected Consumer Projects publish no Kaleido final Bundle or partial Release Evidence Set.

## Answer

Implemented a three-gate adoption Module. `finalizeDsl` rejects declared
Dynamic Features, Asset Packs, or a missing exact `release` build type;
`onVariants` independently accepts every enabled flavored Release variant and
requires final AGP minification; an execution-time validator parses the
ordinary input AAB through bundletool before copying any Kaleido output.

The final-AAB gate accepts only a `REGULAR` Bundle with one non-instant
`base` `FEATURE_MODULE` and no runtime-enabled SDK module. It performs a
bounded streaming scan of final DEX for confirmed `DexClassLoader`,
`InMemoryDexClassLoader`, SplitInstall, Tinker, RePlugin, and VirtualAPK
signals. Ordinary project libraries, external dependencies, and native
payloads remain base-module analysis inputs and are accepted.

Verification:

- `./gradlew :kaleido-gradle-plugin:test` passed 18 unit and packaged TestKit
  tests, including two flavored Release variants and positive library,
  external-dependency, and native-payload coverage.
- Negative integration fixtures prove stable topology/minification/no-variant
  diagnostics and absence of a final `.aab` or Kaleido report.
- Model tests reject non-Regular, Dynamic Feature, Asset, Instant, ML, SDK,
  runtime-enabled SDK, and unknown module topologies.
- `./gradlew :kaleido-gradle-plugin:validatePlugins` passed.
