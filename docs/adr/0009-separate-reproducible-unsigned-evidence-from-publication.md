---
status: accepted
---

# Separate reproducible unsigned evidence from publication evidence

Kaleido defines byte reproducibility over normalized deterministic outputs through the transformed unsigned Release AAB, not over the final signed AAB. One canonical per-variant Artifact Report separates `deterministicEvidence` from observed `publicationEvidence`, while a `releaseEvidenceSetId` binds the unsigned and signed AAB digests, every mapping digest, deterministic-evidence digest, and upload-certificate identity into one atomic Release Evidence Set. This boundary permits cacheable deterministic work and signing-only reruns without claiming that provider-, JDK-, TSA-, or signature-dependent bytes are reproducible; publication still fails unless the final signature, expected certificate, Bundle structure, code transparency, evidence closure, and atomic output all validate. Configuration Cache reuse, up-to-date reuse, Build Cache restoration, and independent clean byte comparison remain distinct proof obligations, and secrets or environment-specific data never enter deterministic evidence.
