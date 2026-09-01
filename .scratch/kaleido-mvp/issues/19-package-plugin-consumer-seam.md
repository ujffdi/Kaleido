# 19 — Establish the packaged plugin and Consumer Project seam

**What to build:** A Consumer Project can resolve the packaged `com.tongsr.kaleido` plugin through a temporary Maven repository, apply it after the Android application plugin, run its ordinary release bundle task, and observe Kaleido through the same public seam future capabilities will use.

**Blocked by:** None — can start immediately.

**Status:** resolved

- [x] Publish the plugin implementation and marker into an isolated temporary Maven repository used by tests; tests do not apply implementation classes directly.
- [x] A minimal Java-only Android application applies the packaged plugin after `com.android.application` and completes its ordinary `bundleRelease` invocation.
- [x] Kaleido discovers the complete release variant identity through supported AGP 9 variant APIs without using internal AGP APIs.
- [x] The plugin establishes one provider-based final Bundle artifact seam that later tickets can finalize without introducing a second user-facing build command.
- [x] Applying Kaleido before the Android application model exists or to an obviously incompatible target produces a stable Kaleido diagnostic rather than an incidental exception.
- [x] The test fixture and plugin build run with JDK 17 and the initial AGP 9.2.1/Gradle 9.4.1 development anchor.
- [x] Existing Sample App behavior remains unchanged when Kaleido is not applied.

## Answer

Implemented a Java 17 `java-gradle-plugin` module that publishes both the
`com.tongsr.kaleido` marker and implementation to an isolated test Maven
repository. The packaged marker is consumed by a Java-only AGP 9.2.1 / Gradle
9.4.1 TestKit fixture; no implementation-class or composite-build shortcut is
used.

The plugin requires `com.android.application` to have been applied, discovers
Release component identity through `ApplicationAndroidComponentsExtension`,
and installs one provider-wired `SingleArtifact.BUNDLE` transform task. A
second fixture proves that applying Kaleido first fails with stable
`KLD-ADOPTION-001` fields and repair guidance.

Verification:

- `./gradlew :kaleido-gradle-plugin:test` passed both packaged-Consumer cases.
- `./gradlew :kaleido-gradle-plugin:validatePlugins` passed after declaring
  path-insensitive AAB input normalization.
- The Sample App does not apply Kaleido and exposes no Kaleido task; no file
  under `app/` was changed. Its pre-existing release baseline remains blocked
  independently because its AndroidX versions require compileSdk 37 while the
  project declares 36.1.
