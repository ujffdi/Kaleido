# Characterize AndroidJunkCode integration gaps

Type: task
Status: resolved
Blocked by: 03

## Question

In an isolated Gradle/Android technical Spike against the fixed AndroidJunkCode 2.0.0 artifact, characterize the exact gaps between unchanged upstream behavior and Kaleido's contract: output determinism, locale sensitivity, declared task inputs, configuration-cache reuse, build-cache correctness, full generation configuration, and Consumer Project source preservation. Produce reproducible evidence for whether private adapter configuration can close every gap; if not, identify the smallest upstream change required and reopen the integration choice between an authorized fork and an independent generator. This task measures and decides from observed behavior; it does not implement a production Kaleido adapter.

## Answer

See [`android-junk-code-2.0.0.md`](../spikes/android-junk-code-2.0.0.md). The fixed artifact builds successfully and automatically on AGP 9.2.1/Gradle 9.4.1, reuses Configuration Cache for the tested built-in configuration, generates the documented Java/resource/Manifest/ProGuard shapes, and leaves Consumer Project source unchanged. It fails reproducibility and Build Cache reuse, and changing an `@Internal` custom generator callback can leave stale output while the task reports `UP-TO-DATE`. Private adapter configuration cannot close these internal gaps, so the direct-dependency strategy is rejected unless upstream publishes a corrected release.
