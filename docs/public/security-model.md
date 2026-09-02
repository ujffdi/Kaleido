# Threat model and proof limitations

Kaleido addresses deterministic generation, bounded code/Manifest/XML identity
rewriting, R8 mapping composition, exact resource/Bundle transformations, final
upload signing, and evidence closure for a supported Android application Release
variant. Protection Requirements cover identities and runtime contracts the
Consumer explicitly cannot permit Kaleido to change.

Kaleido does not claim secrecy, DRM, malware resistance, store-review evasion,
store approval, protection from a privileged attacker, absence of downloaded or
third-party execution paths, or absolute reflection/JNI/runtime unreachability.
It does not rewrite dependency-owned classes/resources by default. Static and
controlled-device tests are evidence about declared fixtures, not all devices or
all application behavior.

Escape Hatches are explicit compatibility policy, not a way to suppress hard
validation. Native symbols, exact reflection, serialization/framework contracts,
Manifest/XML identities, resource names/paths, and runtime attributes are
protected or rejected when their closure cannot be proven. Unknown Bundle
topology, protobuf fields, collisions, signature gaps, certificate mismatch,
mapping mismatch, or stale evidence block publication.

The canonical unsigned Bundle is the reproducible boundary. Final signing may be
nondeterministic across cryptographic providers and environments; Kaleido records
the signed digest and verifies content closure rather than promising identical
signature bytes. The project retains its license, notices, and upstream source
records without claiming a SLSA level or an upstream permission that has not
been obtained.

For vulnerability reporting, see [`SECURITY.md`](../../SECURITY.md). Do not attach
production keys, passwords, private application source, or unsanitized customer
logs to a report.
