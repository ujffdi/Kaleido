# 40 — Publish one immutable Kaleido Release Candidate

**What to build:** One immutable candidate that passed every mandatory automated and human gate is promoted without rebuilding to the Gradle Plugin Portal and a matching GitHub source release, then independently downloaded and verified as the public MVP.

**Blocked by:** 33 — Pass cache, up-to-date, and byte-reproducibility gates; 36 — Pass bundletool and controlled-device runtime gates; 37 — Enforce performance, size, and complexity gates; 38 — Complete license, provenance, and supply-chain evidence; 39 — Publish complete adoption and security documentation.

**Status:** claimed

- [x] Define an immutable Kaleido Release Candidate identity bound to plugin, marker, source, documentation, SBOM, release manifest, Compatibility Matrix, test evidence, and all artifact digests.
- [ ] Run plugin validation and a publication dry run against the exact candidate bytes.
- [x] Distinguish product failures from proven infrastructure failures: product failures require a new versioned candidate; infrastructure reruns use the same bytes and record the cause.
- [x] Permit no waiver, rebuild, mutable replacement, or evidence substitution after a gate has approved the candidate.
- [x] Require two independent human approvals after automated evidence is complete and before publication credentials can be used.
- [ ] Publish the exact approved plugin and marker to the Gradle Plugin Portal and a matching signed-tag GitHub source release; do not publish to Maven Central or Google Play.
- [ ] Download the public Portal artifact, verify every digest against the release manifest, and resolve it through a clean marker-based Consumer Project.
- [ ] Build, sign with test credentials, bundletool-validate, and smoke-test the post-publication Consumer output and Release Evidence Set.
- [x] Treat a defective public version as immutable: issue an advisory and publish a new patch candidate rather than overwriting the version.
- [ ] Record the final public coordinates, source release identity, approval attestations, post-publication evidence, and release verdict in the Kaleido Release Dossier.

Implementation evidence: Plugin Publish 2.1.1 is configured with Portal metadata,
sources and Javadoc publication; `validatePlugins` passes. Dependency verification
was refreshed for the publisher itself. `ReleaseDossierCli` accepts only the exact
nine mandatory PASS records, a verified signed immutable manifest, and two distinct
signed approvals with the release-owner and provenance/security-reviewer roles. It
binds all digests and records no-waiver, product-failure/new-candidate, and classified
same-byte infrastructure-rerun policy.

The release scripts separately (1) run Portal validation without accepting artifact
byte changes, (2) require an explicit protected publication authorization plus real
Portal/GitHub credentials and a valid signed tag before publishing the exact bytes,
and (3) redownload public implementation/marker artifacts, compare manifest digests,
resolve a clean marker-only Consumer, build/sign/bundletool-validate/install/launch it,
and require its Release Evidence Set. Public publication rejects development versions;
the documented recovery is advisory plus a newly gated patch, never replacement.

Actual dry-run and publication remain open. The official `publishPlugins --validate-only`
path reached the configured publisher but requires Portal credentials; none are present.
This checkout is not Git, has no signed tag, lacks both human approvals, and tickets
35–39 retain mandatory external evidence gaps. No Portal/GitHub mutation was attempted.
