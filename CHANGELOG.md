# Changelog

## 0.1.0 — release candidate

- Implements the plugin, tests, and ordinary/Activity generators in Kotlin.
  Safe and Full Junk Code emit `.kt` sources compiled by AGP built-in Kotlin;
  Java-only Consumer `src/` remains supported without Kaleido applying a Kotlin
  plugin.
- Implements the Safe and Full deterministic Android release pipeline, optional
  Runtime-only Compose generation, typed protection and escape hatches, class and
  resource transformations, mapping composition, final-AAB signing, and atomic
  Release Evidence Sets.
- Adds exact macOS arm64 A3 compatibility, static bundletool, reproducibility, performance,
  provenance, SBOM, documentation, and immutable-publication gates.
- Adds independent Safe and Full+Compose Sample Apps as executable bilingual
  documentation; both are inputs to the packaged-marker A3 compatibility
  workflow.
- The version becomes supported only after the exact candidate passes the
  mandatory macOS arm64 A3, performance, Portal, and static
  post-publication gates. Linux, Windows, and device installation remain
  unclaimed forward evidence.
