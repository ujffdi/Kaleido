---
status: accepted
---

# Use plan-first identity-preserving transforms

Kaleido performs a closed-world, two-pass pre-R8 Class Rewrite and an ID-preserving final-AAB Bundle Rewrite. Each Module first creates and validates a complete immutable mapping plan, then applies it structurally to JVM/Kotlin Metadata or AAPT2 protobuf/ZIP surfaces and emits a receipt that must close against the artifact and mappings. Class Rewrite targets only supported non-bytecode reference roots, generated classes, and their required identity closure; R8 owns the remaining program. Bundle Rewrite retains all resource IDs and deduplicates only equivalent base-module file payloads by redirecting file references rather than merging resource entries. Deterministic hash-derived names, all-output collision checks, versioned Protobuf plans, strict post-write validation, and fail-before-mutation behavior trade some maximum obfuscation coverage for reproducibility, auditability, and behavior preservation without exposing transformation mechanics through the Adoption Contract.
