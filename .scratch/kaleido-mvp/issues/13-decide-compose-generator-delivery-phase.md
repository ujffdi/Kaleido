# Decide the Compose Generator delivery phase

Type: grilling
Status: resolved
Blocked by:

## Question

Is the explicitly enabled Compose Generator required for the first publicly releasable MVP, or is it a later optional capability that must not delay the four core capability families? If it is deferred, close ticket 12 as out of scope for this map; if it is in the MVP, make ticket 12 a release blocker before final acceptance.

## Answer

The Compose Generator ships in the first publicly releasable Kaleido MVP as an opt-in sub-capability of AndroidJunkCode-equivalent generation. It is not a fifth capability family, remains disabled by default, and is unavailable unless the Consumer Project explicitly enables it and already supports Compose. This delivery commitment does not replace, narrow, or postpone definition and delivery of any of the four mandatory capability families.

The optional Compose Generator contract remains in scope and becomes a release blocker: Kaleido cannot pass final release acceptance or publish the MVP until that contract and its required acceptance evidence are resolved. Selecting Safe Profile or Full Profile alone never enables the Compose Generator.

## Comments

- The Compose Generator is an optional sub-capability of AndroidJunkCode-equivalent generation. It never replaces, narrows, or blocks definition of the four-family Core Capability Set.
- Product correction: it must not be modeled or reported as a fifth capability family.
