# AndroidJunkCode 2.0.0 integration Spike

Date: 2026-08-31

## Question

Can the fixed AndroidJunkCode 2.0.0 artifact satisfy Kaleido's automatic AGP 9 Release AAB, source-preservation, reproducibility, configuration-cache, build-cache, and full-configuration guarantees through private adapter configuration alone?

## Fixed inputs

- AndroidJunkCode tag `2.0.0`, commit `cfcd9eed0b8d5a938033a9268a20e58e059b3039`.
- AGP 9.2.1, Gradle 9.4.1, Amazon Corretto JDK 17.0.14.
- Isolated copy of the Kaleido Sample App; the repository checkout was not modified.
- Release minification enabled and generation reduced to two packages, two Activities per package, two other classes per package, three methods per class, three drawables, and three strings.
- Every Gradle validation command used `--configuration-cache --configuration-cache-problems=fail --build-cache`.

The isolated fixture disabled Release lint after a transient Maven TLS failure and used AndroidX versions compatible with its installed compileSdk 36.1. Those fixture-only changes do not alter AndroidJunkCode task behavior.

## Observations

### Automatic Release AAB and source preservation pass

`clean :app:bundleRelease` completed through generated sources/resources, Manifest merge, Java/Kotlin compilation, R8, bundle packaging, and signing. The plugin generated 19 files: eight Java files, four layout files, three drawables, strings, a resource keep file, a ProGuard file, and a generated Manifest containing four Activities.

The aggregate `app/src` hash was identical before and after every run:

```text
74530c7e2dfa76143462e70aad19fd7bfcec34292b7ee3730790b002057c631b
```

The generated ProGuard file retained both generated packages, so the generated classes survived Release R8 in this fixture.

### Configuration Cache passes for the tested built-in configuration

The first successful `clean :app:bundleRelease` stored a Configuration Cache entry. Repeating the identical command reported:

```text
Reusing configuration cache.
Configuration cache entry reused.
```

This proves reuse for the tested built-in configuration. It does not prove that arbitrary user callback objects are serializable.

### Reproducibility fails

Two identical clean builds regenerated the same 19 logical files with different names and contents:

```text
first generated tree:  4a0e54b1c9c72a99fbcade122aba04a53b24d70f0bd03c6e642387ab46cc17ce
second generated tree: a56196ab7ae2af16f80a544ef5151612d4973f30e651e0d0a7daba4e65e45435
```

Their AAB hashes also differed:

```text
first AAB:  b16dac41e80f6cf626a2034f5ee7027af51976aeeda152d4e00e4ba7d18f1eb1
second AAB: b614681124ff506785aa3240e0f89ae0a3728f271ab24f58aa4340669f873c96
```

The generated-tree difference alone proves AndroidJunkCode contributes nondeterministic bytes; AAB hash differences are not used as the sole proof because bundle packaging/signing may have additional variability.

### Build Cache reuse is unavailable

`:app:generateReleaseJunkCode` executed after both clean builds even with `--build-cache`; it was never restored `FROM-CACHE`. The upstream task is not declared `@CacheableTask`, consistent with its nondeterministic implementation.

A no-clean repeat correctly marked the task `UP-TO-DATE`, but that exposes the callback-input defect below.

### Custom callback inputs are stale

After a successful build, the fixture added a deterministic `classNameCreator` callback that requested `StableClass0` and `StableClass1`. Running `:app:generateReleaseJunkCode` without cleaning reported:

```text
> Task :app:generateReleaseJunkCode UP-TO-DATE
stable_class_files=0
```

The generated tree remained byte-identical to the previous output. Running `clean :app:generateReleaseJunkCode` with the same callback then produced four `StableClass*.java` files.

This demonstrates an incorrect up-to-date key: callback behavior changes generated output, but the callback properties are annotated `@Internal` and do not participate in Gradle input snapshots.

## Verdict

AndroidJunkCode 2.0.0 satisfies automatic AGP 9 integration, normal Release AAB participation, built-in generation coverage, and Consumer Project source preservation. It does not satisfy Kaleido's reproducibility or build-cache contract, and its callback extension surface can return stale output under normal Gradle up-to-date checking.

A private Kaleido adapter cannot repair process-global random generation, locale-sensitive naming, task annotations, or callback input modeling inside the fixed upstream binary. The smallest viable upstream-level repair requires source changes: a declared stable seed, locale-independent naming, fully modeled declarative generator inputs, and cacheable deterministic task outputs. Therefore the direct-dependency candidate is rejected unless the owner publishes a corrected release; Kaleido must choose between an authorized maintained fork and an independent generator.
