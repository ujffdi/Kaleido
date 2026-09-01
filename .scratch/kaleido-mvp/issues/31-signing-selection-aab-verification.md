# 31 — Select signing atomically and verify the final AAB

**What to build:** Kaleido selects one complete signing source at execution time, signs the exact canonical hardened candidate, and verifies signature coverage, certificate identity, bundle structure, and optional code transparency without exposing credentials.

**Blocked by:** 28 — Add resource protection, deduplication, and canonicalization.

**Status:** resolved

- [x] Select the first complete source in this precedence: exact-variant DSL, top-level DSL, complete `KALEIDO_UPLOAD_*` environment source, complete `kaleido.uploadSigning.*` Gradle-property source.
- [x] Never merge fields from multiple signing sources; a partially present higher-precedence source fails rather than falling through.
- [x] Resolve credential providers, keystore material, passwords, and aliases only during noncacheable execution.
- [x] Exclude secrets, credential paths, provider objects, raw values, and user-home information from configuration state, cacheable inputs, diagnostics, reports, and Kaleido-controlled logs.
- [x] Record and compare a non-secret expected signing-certificate digest.
- [x] Sign only the exact digest produced by unsigned bundle canonicalization and perform no transformation afterward.
- [x] Verify complete AAB signature coverage, expected certificate identity, bundletool validity, and preserved code-transparency material when present.
- [x] Negative tests cover partial sources, precedence conflicts, missing keys, wrong aliases/passwords, wrong certificates, corrupted signatures, mutated candidates, and redaction.
- [x] The signed candidate remains staged and is not exposed as the final public Bundle until the Release Evidence Set is complete.

## Answer

The non-cacheable signing stage now selects exactly one complete credential source at execution time, binds the canonical unsigned digest to the signing input, checks the expected certificate, signs every AAB entry, and independently verifies signature coverage, certificate identity, bundletool validity, and unchanged code-transparency bytes. Stable diagnostics and `SigningReceipt.v1` contain only non-secret identities and digests; partial sources, invalid credentials, wrong certificates, corruption, candidate mutation, and redaction all have negative coverage. The verified signed AAB remains at the fixed staging path while the standard Bundle output remains unsigned until Ticket 32 atomically publishes it with the complete Release Evidence Set. The plugin's 62-test suite and Gradle plugin validation pass.
