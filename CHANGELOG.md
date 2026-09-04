# Changelog

## 0.1.1

- Sources `pluginVersion` from the published Kaleido plugin JAR rather than the
  Consumer Project, so Consumer `version` values can no longer enter the
  Artifact Report or Release Evidence Set manifest.
- Fails publication with `KLD-PUBLICATION-001` when the embedded plugin version
  is missing, blank, or `unspecified`, preserving the prior successful evidence
  instead of publishing a set with a false version.
- Aligns the internal Artifact Report contract with the shipped canonical
  UTF-8/LF key/value `artifact-report.txt` format and keeps Schema 1.0 and the
  existing `releaseEvidenceSetId` calculation unchanged.

## 0.1.0 — released

- Published successfully to the Gradle Plugin Portal. Its Artifact Report
  incorrectly sourced `pluginVersion` from the Consumer Project, which could
  record `unspecified`; 0.1.1 corrects the provenance without rewriting 0.1.0.

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
