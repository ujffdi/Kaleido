# Obtain AndroidJunkCode license clarification

Type: task
Status: resolved
Blocked by: 01, 11

## Question

Prepare and send an owner-facing request that asks whether the Apache-2.0 declaration covers the complete AndroidJunkCode 2.0.0 source tree and generator templates, whether generated Java/resources/XML/Manifest outputs may be distributed in Consumer Project AABs, and whether the owner will add a root LICENSE and any required NOTICE. Record the authoritative response or the absence of one so publication policy can choose a defensible fallback.

## Answer

Owner clarification is not an architecture or public-MVP release gate. Kaleido adopts the conservative fallback immediately:

- Independently implement AndroidJunkCode-equivalent behavior inside Kaleido; AndroidJunkCode 2.0.0 is neither the production engine nor a transitive runtime/build dependency.
- Record the upstream `2.0.0` revision, first-party `gradle.properties`, and Maven POM Apache-2.0 declarations as research provenance, without treating the missing root `LICENSE`/`NOTICE` or generated-output terms as resolved.
- Do not copy source expression, generator templates, or generated fragments from AndroidJunkCode unless compatible written permission or a repository-level license clarification is obtained later.
- Keep the Compose Generator Kaleido-owned and independently authored; its classification under the AndroidJunkCode-equivalent capability family does not make it derived from upstream code.
- A future owner response may strengthen provenance or permit attributed reuse, but its absence does not prevent publication under the independent-implementation policy.

No owner-facing request was sent as part of this resolution. The product deliberately selects the no-copy fallback instead of depending on an external response.

## Comments

- Product decision: accept the non-blocking provenance recommendation and close this ticket with the conservative independent-implementation fallback.
