---
status: accepted
---

# Use One Comprehensive Sample Project

Kaleido maintains one developer-facing comprehensive Sample Project with a Kaleido `app` module and a non-Kaleido `baseline` module that share the same Consumer source and resource inputs. The `app` selects Full, Compose Generator, Protection Requirements, and bounded Full-only resource operations so one build produces the before-and-after AABs used for manual comparison. Release Fixtures remain deliberately minimal, independent Consumer Projects that isolate Java Safe, built-in Kotlin Safe, Full Compose, native, compatibility, and runtime assertions. The Compatibility Matrix records the public Sample once as `sample-comprehensive`; controlled-device coverage launches its Kaleido `app`, while `baseline` remains comparison evidence rather than a Kaleido Release Evidence Set.
