# Third-party notices

Kaleido is distributed under Apache-2.0. Runtime dependencies are not shaded
into the plugin JAR; Gradle resolves them as separately identified components.
The release SBOM and dependency inventory bind their exact versions and hashes.

## Algorithm provenance

- AabResGuard, revision `e8f3a5d361ce61a3d4fa8bafb9d030bbe459c400`,
  copyright ByteDance Ltd., Apache-2.0. Kaleido adapts resource-name/path and
  canonical Bundle-rewrite concepts, with an independently authored Java/AGP 9
  implementation and the material changes recorded in `NOTICE` and the
  machine-readable provenance manifest.
- AndroidJunkCode `2.0.0`, revision
  `cfcd9eed0b8d5a938033a9268a20e58e059b3039`, is behavioral research only.
  Kaleido copies no upstream source, templates, or generated fragments.
- XmlClassGuard `1.2.7`, revision
  `198cea9ccd87129d8ffb6ec5f258190a3b3ee8a1`, has no discovered reusable
  license and is behavioral research only. Kaleido copies and distributes none
  of its source or artifacts.

## Resolved plugin dependencies

- Android bundletool and aapt2-proto — Apache-2.0.
- ASM — BSD-3-Clause.
- Kotlin metadata, Kotlin standard library, and JetBrains annotations —
  Apache-2.0.
- Guava, failureaccess, listenablefuture, AutoValue annotations, Error Prone
  annotations, J2ObjC annotations, Gson, Dagger, javax.inject, and jose4j —
  Apache-2.0.
- Protocol Buffers Java and JSR-305 — BSD-3-Clause.
- Checker Framework qualifiers and SLF4J — MIT.

The CycloneDX SBOM is authoritative for exact candidate versions and artifact
digests. Project names and trademarks belong to their respective owners; no
endorsement is claimed.
