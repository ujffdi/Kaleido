# 25 — Integrate deterministic R8 dictionaries and composed mappings

**What to build:** A Consumer Project's R8 stage receives deterministic Kaleido dictionaries and rules, preserves the identities required by the class plan, and emits an auditable retrace-compatible original-to-final mapping.

**Blocked by:** 24 — Close Kotlin, XML, and Protection Requirement references.

**Status:** resolved

- [x] Generate canonical deterministic R8 dictionaries and rules from normalized variant inputs and domain-separated seed material.
- [x] Preserve class identities already fixed by the Kaleido class plan while allowing R8 optimization and obfuscation of the remaining eligible program.
- [x] Capture the raw R8 mapping and its relevant R8 version and mapping identity metadata.
- [x] Compose raw original-to-Kaleido and Kaleido-to-final mappings into one original-to-final retrace-compatible mapping.
- [x] Preserve residual signatures and enough metadata to identify input digests, composer identity, output digest, and compatible retrace tooling.
- [x] Represent protected or retained identities as evidence decisions rather than fabricated mapping rows.
- [x] Repeated builds with identical normalized inputs produce byte-identical dictionaries, rules, raw mappings where toolchain permits, and composed mappings.
- [x] Retrace tests recover original Java and Kotlin identities from final obfuscated stack traces through the composed mapping.

Implementation evidence: each Release variant now generates three 4,096-entry canonical
domain-separated dictionaries plus one deterministic rule file. Semantic Manifest/XML targets
are retained by exact class-plan identities while other generated classes and members consume
the dictionaries in real R8 9.2.14 output. `SingleArtifact.OBFUSCATION_MAPPING_FILE` is copied
byte-for-byte, then structurally composed with `KaleidoRawClassMapping.v1`; mapping-information
comments, including residual signatures, are retained while raw `pg_map_id`/`pg_map_hash` and
all input/output digests are recorded separately in canonical composition metadata. A validated
`com.android.tools.build:builder:9.2.1` Retrace recovers Java and built-in Kotlin owners from
real Release mappings. Repeated full builds prove byte-identical R8 inputs, raw mapping, and
composed mapping for identical inputs, while a changed seed changes the expected boundary.

Verification: `./gradlew :kaleido-gradle-plugin:test
:kaleido-gradle-plugin:validatePlugins --stacktrace` passes 48 tests with zero failures/errors.
