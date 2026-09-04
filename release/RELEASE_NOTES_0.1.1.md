# Kaleido 0.1.1 release notes

Kaleido 0.1.1 is a compatibility patch that makes Artifact Report version
provenance trustworthy. The plugin now embeds its publication version in
`META-INF/kaleido/version.properties` and records that exact value in both
`artifact-report.txt` and `release-evidence-set-manifest.properties`, regardless
of the Consumer Project's own `version`.

Publication fails closed with `KLD-PUBLICATION-001` when the embedded version is
missing, blank, or `unspecified`, without replacing a prior successful Release
Evidence Set. The report filename, canonical UTF-8/LF key/value format, Schema
1.0, and `releaseEvidenceSetId` calculation are unchanged.

Development and release validation use AGP 9.2.0, Gradle 9.4.1, JDK 17, Build
Tools 36.0.0, and compileSdk 36 on macOS arm64. Static Bundle, APK, DEX, mapping,
signing, bundletool, and checksum validation does not constitute device or
emulator runtime validation.
