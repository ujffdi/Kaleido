# 39 — Publish complete adoption and security documentation

**What to build:** A public Android team can install, configure, validate, investigate, upgrade, and responsibly assess Kaleido using tested canonical documentation without relying on private project knowledge or misleading protection claims.

**Blocked by:** 34 — Implement version, schema, and migration contracts; 35 — Establish the mandatory Compatibility Matrix; 38 — Complete license, provenance, and supply-chain evidence.

**Status:** claimed

- [x] Provide canonical English installation, quick-start, Safe/Full Profile, Safe Defaults, DSL, signing, and supported-topology documentation.
- [x] Document Compose Generator as optional AndroidJunkCode content that is disabled by default, Runtime-only, has no deliberate entry point, and does not prove absolute runtime unreachability.
- [x] Publish the exact Compatibility Matrix and explain the distinction between supported rows and nonblocking forward evidence.
- [x] Document stable diagnostics, Artifact Report, Release Evidence Set, raw/composed/resource mappings, and the default retrace workflow with tested examples.
- [x] Document the threat model, Protection Requirements, Escape Hatches, reproducibility boundary, signing boundary, proof limitations, and all explicit non-goals including store-review evasion.
- [x] Publish performance/size methodology and accepted thresholds, upgrade/deprecation/Bridge Release guidance, license/provenance, security reporting, contribution guidance, changelog, and release notes.
- [x] Build every installation and DSL example against the packaged candidate and validate links, schema examples, diagnostic references, and retrace instructions.
- [x] Any Chinese documentation is reviewed against the canonical English contract and contains no divergent behavior or compatibility promise.
- [x] Documentation never embeds production credentials, user-home paths, private Sana behavior, or an unsupported store-approval claim.

Implementation evidence: `README.md` routes to five canonical English public guides
plus security, contribution, changelog, license/provenance, and candidate release
notes. The compatibility guide lists exact A3/A4 inputs and explicitly says that no
public row is supported until mandatory Linux/Sana/device evidence exists; forward
experiments remain nonblocking evidence. There is no Chinese public documentation.

`validate-public-docs.sh` checks all relative links, user-home leakage, and complete
diagnostic-family coverage, resolves the packaged marker, runs `validatePlugins`, and
clean-builds both canonical Safe and Full+Compose examples with test-only signing.
The current validation record is PASS (nine canonical files, sixteen diagnostic
families, both Release bundles). Ticket remains claimed because tickets 35 and 38
are declared blockers and the published supported-row/digest references must be
closed against their eventual immutable candidate rather than invented now.
