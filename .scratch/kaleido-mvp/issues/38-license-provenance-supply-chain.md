# 38 — Complete license, provenance, and supply-chain evidence

**What to build:** The public candidate contains a truthful Apache-2.0 licensing and provenance package that distinguishes independent implementations from permitted AabResGuard algorithm reuse and lets adopters inventory every source and dependency component.

**Blocked by:** 35 — Establish the mandatory Compatibility Matrix.

**Status:** claimed

- [x] Publish the project under Apache-2.0 with complete LICENSE, content-based NOTICE, and third-party notices.
- [x] Maintain AndroidJunkCode-equivalent and XmlClassGuard-equivalent implementations without copying upstream source, templates, or generated output.
- [x] Run and record a content-similarity audit over those implementations, templates, fixtures, and representative generated samples.
- [x] For every reused AabResGuard algorithm, preserve required Apache-2.0 notices and record the source component, provenance, adaptation, and material changes.
- [x] Generate a machine-readable source/dependency inventory and enforce dependency verification.
- [x] Produce matching source artifacts and a CycloneDX 1.7 SBOM bound to the candidate.
- [ ] Produce a signed source tag and immutable release manifest that binds source, plugin, marker, documentation, SBOM, mappings/schema assets, and candidate digests.
- [x] Security review confirms that no document claims permission from absent upstream licenses or a SLSA level/attestation the process does not actually provide.
- [x] Any missing, mismatched, ambiguous, or unreviewed provenance item blocks publication.

Implementation evidence: the binary and sources JARs contain `META-INF/LICENSE`,
`NOTICE`, and `THIRD_PARTY_NOTICES.md`; both implementation and marker POMs carry
Apache-2.0 metadata, while a release candidate requires and records authoritative
website/VCS inputs before adding SCM metadata. `generateSupplyChainEvidence` emits a digest-bound
source/dependency inventory, candidate manifest, and deterministic CycloneDX 1.7
SBOM; the generated SBOM passed the official 1.7 JSON Schema. Gradle dependency
verification is enforced by `gradle/verification-metadata.xml` and an unknown
dependency license fails closed with `KLD-PROVENANCE-001`.

`run-similarity-audit.sh` checks fixed AndroidJunkCode and XmlClassGuard revisions
against Kaleido implementation, fixtures, templates, and representative generated
content. The recorded 111,744-comparison initial scan exposed only Android manifest
boilerplate; after excluding unrelated build intermediates and setting the documented
128-token substantial-expression threshold, the fixed-revision audit passed. AabResGuard
revision, license, use boundary, adaptations, and material changes are recorded in
`NOTICE`, third-party notices, and the machine provenance properties.

The signed-tag/immutable-manifest script is implemented and fails closed on a dirty or
non-Git source tree, an invalid signed tag, missing assets, or a missing GPG identity.
This checkout is not a Git work tree and no Release Owner signing identity was supplied,
so the signed source tag and final immutable release manifest remain intentionally open.
Ticket 35's mandatory Linux matrix also remains a declared blocker.
