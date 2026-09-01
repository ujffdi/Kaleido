# Own final-AAB resource transformation and signing

Kaleido must provide AABResGuard-equivalent resource obfuscation and optimization on the final Release AAB, so the Hardening Pipeline owns the post-bundle resource transformation, re-signing, signature verification, and publication of one valid final AAB. The implementation uses supported AGP artifact integration rather than version-coupled AGP internals, never persists or reports signing secrets, and fails the Release build instead of publishing an unsigned, incorrectly signed, or only partially transformed artifact.
