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
- Adds a tested macOS arm64 development configuration and public documentation.
- Adds independent Safe and Full+Compose Sample Apps as executable bilingual
  documentation.
- Adds a small Gradle Plugin Portal workflow based on plugin tests, the public
  Sample build, `validatePlugins`, validation-only publication, and publication.
