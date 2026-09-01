# Establish the MVP compatibility baseline

Type: research
Status: resolved
Blocked by:

## Question

Determine the defensible AGP 9, Gradle, JDK, Kotlin, and Android build-tools compatibility matrix for Kaleido's MVP, including which combinations can be supported through public APIs and exercised with Gradle TestKit.

## Answer

See [`version-compatibility.md`](../research/version-compatibility.md). The evidence supports AGP 9.0.1–9.3.2 only as a candidate range paired with each line's documented Gradle baseline; JDK 17, Build Tools 36.0.0, compileSdk 36, and AGP built-in Kotlin form the common baseline. The minimum supported AGP remains conditional on the required public Artifact APIs, and every advertised combination must become a release-blocking TestKit/CI row.
