# Kaleido

Kaleido is an Apache-2.0 Gradle plugin for deterministic Android release
hardening. It applies only to minified `com.android.application` Release
variants and publishes a signed AAB together with mappings and a closed Release
Evidence Set. It is not a store-review evasion tool and makes no approval,
anti-analysis, or absolute-unreachability guarantee.

The public plugin id is `com.tongsr.kaleido`. Start with the runnable
[`samples/kaleido-sample`](samples/kaleido-sample) Safe example; the packaged
marker is used by its settings file. Build with a supported candidate repository
and provide one complete upload-signing source:

```shell
gradle -p samples/kaleido-sample bundleRelease \
  -PmatrixPluginRepository=/path/to/candidate-repository \
  -PmatrixAgp=9.2.1 -PmatrixKaleido=0.1.0-dev
```

Never put production passwords in source control. See the canonical public
documentation:

- [Adoption, profiles, DSL, and signing](docs/public/adoption.md)
- [Compatibility and performance gates](docs/public/compatibility-and-performance.md)
- [Evidence, diagnostics, mappings, and retrace](docs/public/evidence-and-diagnostics.md)
- [Threat model and security boundaries](docs/public/security-model.md)
- [Upgrade and immutable release policy](docs/public/upgrade-and-release.md)
- [License and provenance](THIRD_PARTY_NOTICES.md)
- [Security reporting](SECURITY.md), [contributing](CONTRIBUTING.md), and
  [changelog](CHANGELOG.md)

There is no supported public release until every mandatory A3/A4, runtime,
performance, provenance, documentation, approval, Portal, and post-publication
gate has passed for the same immutable candidate bytes.
