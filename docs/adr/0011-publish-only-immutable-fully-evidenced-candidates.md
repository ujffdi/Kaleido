---
status: accepted
---

# Publish only immutable fully evidenced candidates

Kaleido publishes a plugin version only when one immutable Kaleido Release Candidate—fixed source revision, dependency locks, toolchain, marker/implementation bytes, and digests—passes the exact A3/A4 Compatibility Matrix, complete Core Capability Set fixtures, controlled device smoke, artifact and mapping closure, four independent cache/reproducibility gates, numerical performance/size budgets, Apache-2.0 provenance, documentation, Plugin Portal validation, and two independent human approvals. The resulting Kaleido Release Dossier binds those facts without becoming a Consumer Project Release Evidence Set. This deliberately accepts high release cost to prevent tested-source/published-binary drift and non-reproducible exceptions: required gates have no waiver, product failures create a new Candidate, and a bad immutable Portal version is followed by an advisory and fully gated patch rather than overwrite. Portal plus the authoritative source release are the MVP channels; Maven Central, Play tracks, and unsupported SLSA claims remain outside the first release boundary.
