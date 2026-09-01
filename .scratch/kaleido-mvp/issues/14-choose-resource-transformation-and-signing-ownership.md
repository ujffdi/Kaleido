# Choose resource transformation and signing ownership

Type: grilling
Status: resolved
Blocked by: 03

## Question

Given that AGP 9 exposes no complete merged-resource seam before signing and the public BUNDLE artifact is already signed, will the MVP constrain resource behavior to pre-AAPT2 generated/source overlays, transform the final AAB and explicitly own secure re-signing, or accept version-coupled AGP internals? Decide the security, credential, automation, compatibility, and resource-capability consequences before the engine contract is defined.

## Answer

The MVP must provide AABResGuard-equivalent obfuscation and optimization of the final Release AAB. Kaleido therefore owns the post-bundle transformation, secure re-signing, signature verification, and publication of exactly one valid final AAB through supported AGP artifact integration; it does not narrow the resource capability to generated/source overlays and does not use version-coupled AGP internals. Signing secrets must not be persisted or included in reports, and any transform or signing failure fails the Release build. See [`ADR-0004`](../../../docs/adr/0004-own-final-aab-resource-transformation-and-signing.md).
