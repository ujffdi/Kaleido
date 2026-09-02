# Evidence, diagnostics, mappings, and retrace

Successful Release variants publish
`build/reports/kaleido/<variant>/release-evidence-set/`. Its manifest and
`artifact-report.txt` bind the exact project/variant/profile, unsigned and signed
AAB hashes, certificate, stage verdicts, raw Kaleido mapping, raw R8 mapping,
composed mapping, resource mapping, deterministic evidence, and one
`releaseEvidenceSetId`. Failed attempts do not publish a partial or stale set.

The deterministic boundary contains generated content, immutable plans and
receipts, dictionaries/rules, mappings, canonical unsigned AAB, and deterministic
evidence. Signing and runtime observations are outside byte reproducibility but
must bind the exact unsigned digest. Configuration Cache, no-clean up-to-date,
local/relocated Build Cache, and independent clean byte reproduction are separate
claims.

## Diagnostics

Every hard failure uses a stable family plus structured `project`, `variant`,
`stage`, `origin`, `target`, `reason`, and `repair`. Public families are:
`KLD-ADOPTION-001/002`, `KLD-CONFIG-001`, `KLD-TOPOLOGY-001..009`,
`KLD-GENERATION-001`, `KLD-COMPONENT-001`, `KLD-COMPOSE-001`,
`KLD-PROTECTION-001`, `KLD-CLASS-001`, `KLD-RESOURCE-001/002`,
`KLD-BUNDLE-001`, `KLD-SIGNING-001`, `KLD-PUBLICATION-001`,
`KLD-COMPAT-001`, `KLD-SCHEMA-001`, `KLD-MIGRATION-001`, and
`KLD-DEPRECATION-001`. Release-gate tools additionally use
`KLD-PROVENANCE-001` and `KLD-PERF-001`.

Investigate the first hard diagnostic, follow its `repair`, rerun the exact
variant, then compare the newly published evidence-set id. Do not edit receipts
or mappings by hand.

## Retrace workflow

Use the `composed-mapping.txt` from the same Release Evidence Set as the crashed
AAB. It maps final names through both Kaleido and R8. For R8 retrace, run the R8
version recorded by the release toolchain against that composed mapping and the
original stack trace. `raw-kaleido-mapping.txt` and `raw-r8-mapping.txt` are audit
inputs, not substitutes for the composed mapping. Resource investigations use
`resource-mapping.txt`. Always verify each mapping digest against
`release-evidence-set-manifest.properties` before use; never use a mapping from a
different version, variant, or evidence-set id.

Schemas are versioned independently. Unknown major schemas fail closed; old
immutable evidence remains interpreted with its recorded schema and archived
release tools.
