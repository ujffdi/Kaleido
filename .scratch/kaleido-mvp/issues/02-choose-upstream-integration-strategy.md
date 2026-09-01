# Choose the upstream integration strategy

Type: grilling
Status: resolved
Blocked by: 01, 11

## Question

For each upstream capability family, should Kaleido directly depend on the original plugin, maintain a compliant fork, reuse licensed engine code behind a new adapter, or implement equivalent behavior using source only as technical reference? Decide the provenance, upgrade, compatibility, and fallback policy that all later product decisions inherit.

## Answer

Kaleido adopts a per-engine hybrid strategy behind its own stable internal engine boundary:

- AndroidJunkCode: independently implement Capability Parity inside Kaleido using upstream behavior and source as technical reference. Do not use the fixed `2.0.0` artifact as the production engine; copy source expression or templates only after compatible written permission.
- AabResGuard: reuse selected Apache-2.0 algorithms with exact provenance and modification notices, while replacing its Gradle/AGP integration with Kaleido-owned AGP 9 code.
- XmlClassGuard: do not depend on or copy the unlicensed implementation; independently implement required behavior using public behavior and source only as technical reference.
- Consumer Projects see only Kaleido's plugin ID, DSL, Profiles, pipeline, reports, and errors. Upstream types and configuration do not become public Kaleido API, so an engine can later be replaced without changing the adoption contract.
- A generic engine SPI remains out of the MVP until two real interchangeable implementations prove a useful seam.

Evidence: [`upstream-rights-and-reuse.md`](../research/upstream-rights-and-reuse.md) and [`agp9-artifact-seams.md`](../research/agp9-artifact-seams.md).

## Comments

- The earlier direct-dependency answer was invalidated by ticket 11. Ticket 15 subsequently closed with a non-blocking provenance policy: owner clarification may strengthen the record but is not required for independent implementation or publication, and no upstream source expression or templates may be copied without compatible permission.
