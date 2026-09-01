# Define the capability contract for each engine

Type: grilling
Status: resolved
Blocked by: 02, 03, 11, 14

## Question

Which exact AndroidJunkCode, deterministic R8 dictionary, resource-guard, and class/XML/Manifest capabilities belong in the MVP, which belong only to the Full Profile, and which are deferred without breaking the product promise?

## Answer

Both Safe Profile and Full Profile execute every family in the Core Capability Set; Profiles vary exposure and intensity, never family membership.

- **AndroidJunkCode-equivalent generation:** Safe Profile generates retained ordinary Java classes/methods, layouts, XML drawables, and strings without runtime entry points. Full Profile additionally exposes the pinned upstream implementation's built-in Java Activity and application-child Manifest generation through explicit nonzero declarative configuration; generated Activities are non-exported and have no intent filters by default. Kaleido does not expose arbitrary executable or whole-tree callbacks. Generation is deterministic, declared-input-driven, confined to `build/`, and retained in the final artifacts.
- **XmlClassGuard-equivalent protection:** transformation is reference-driven and renames eligible Application-module and Kaleido-generated classes discovered through Application, Activity, Activity Alias, Service, Receiver, Provider, layout/XML, nested Navigation, Data Binding, and affected keep/R8 surfaces. Local library and external dependency classes are analyzed but not renamed. ConstraintLayout referenced IDs feed resource keeps. Dynamic or unsupported references require an escape hatch or fail closed.
- **AABResGuard-equivalent processing:** both Profiles obfuscate final-AAB resource entry names, directories, and file paths, preserve resource IDs, emit a stable mapping, and merge byte-identical resources within a module. File deletion, unused-string replacement, and language filtering require explicit Full Profile configuration.
- **Deterministic R8 handling:** a Release variant without minification fails with an actionable diagnostic. One declared seed deterministically derives isolated class, package, and member dictionaries; Kaleido retains R8 inputs/mapping and never consumes a prior mapping implicitly.

Deferred without weakening the MVP promise: arbitrary generator callbacks; general-purpose Kotlin generation other than the Compose Generator; unevidenced component generators; XmlClassGuard `packageChange`/`moveDir`; renaming dependency classes; general compression, image conversion, resource-ID compaction, and generic unused-resource shrinking. The Compose Generator is an opt-in sub-capability of AndroidJunkCode-equivalent generation; its delivery phase and detailed contract remain owned by their dedicated tickets.

Evidence: [`engine-capability-dimensions.md`](../research/engine-capability-dimensions.md).

## Comments

- Product decision: all four capability families are mandatory in the MVP and are delivered by one Kaleido plugin. This ticket defines their exact contracts and Profile exposure; it may not defer or replace an entire core family.
- Product decision: the resource family includes AABResGuard-equivalent obfuscation and optimization of the final Release AAB, not only generated resources or pre-AAPT2 overlays.
- Product input: a Kaleido-owned Compose Generator is requested as an explicitly enabled optional sub-capability of AndroidJunkCode-equivalent generation. Whether it ships in the MVP or a later release is decided in ticket 13; its detailed contract is ticket 12.
- Product correction: Compose Generator belongs to AndroidJunkCode-equivalent generation and must not be modeled as a fifth capability family.
- Decision round 1: Safe Profile and Full Profile both run all four Core Capability Set families; Profiles change generation exposure and intensity rather than removing a family.
- Decision round 1: Full Profile targets AndroidJunkCode Capability Parity behind Kaleido's own DSL; Safe Profile limits generation to static code/resources with no runtime entry point or side effect. Generated outputs are deterministic, retained, and confined to `build/`.
- Decision round 1: class transformation defaults to Consumer Project and Kaleido-generated classes; third-party dependencies participate in reference analysis but are not renamed. Unsupported dynamic references are kept or fail closed.
- Decision round 1: final-AAB resource handling includes both obfuscation and optimization while preserving resource IDs and protected dynamic/public/SDK behavior.
- Decision round 1: a Release variant without R8/minification fails with an actionable diagnostic; Kaleido does not silently enable it by rewriting the Consumer Project DSL.
- Evidence for round 2: [`engine-capability-dimensions.md`](../research/engine-capability-dimensions.md) records the exact public behavior of the three pinned upstream implementations and separates it from proposed Kaleido extensions.
- Decision round 2: AndroidJunkCode Capability Parity covers the pinned upstream implementation's built-in Java Activity, ordinary Java class, method, layout, XML drawable, string, Activity Manifest, and retention dimensions through a declarative Kaleido DSL; arbitrary executable or whole-tree callbacks are excluded.
- Decision round 2: Full Profile exposes generated Activities only through explicit nonzero configuration; generated Activities are non-exported and have no intent filter by default. Service, Receiver, Provider, Application, and other unevidenced generator types are deferred.
- Decision round 2: class transformation is reference-driven rather than a second whole-program obfuscator. It renames eligible Application-module and Kaleido-generated classes discovered through supported non-bytecode references; local library and external dependency classes are analyzed but not renamed in the MVP.
- Decision round 2: the supported static-reference surface includes Application, Activity, Activity Alias, Service, Receiver, Provider, layout/XML class references, nested Navigation destinations, Data Binding types, and affected keep/R8 rules. Dynamic or unsupported references use explicit keeps or fail closed.
- Decision round 2: ConstraintLayout referenced-ID discovery feeds resource keeps in the MVP; upstream `packageChange` and `moveDir` capabilities are deferred.
- Decision round 2: resource name/directory/path obfuscation, stable mapping, and within-module byte-identical resource merging run in both Profiles. File deletion, unused-string replacement, and language filtering require explicit Full Profile configuration. General compression, image conversion, resource-ID compaction, and generic unused-resource shrinking are deferred.
- Decision round 2: one declared seed deterministically derives isolated class, package, and member dictionaries. No prior mapping is consumed implicitly; any future mapping reuse must be an explicit declared input.
