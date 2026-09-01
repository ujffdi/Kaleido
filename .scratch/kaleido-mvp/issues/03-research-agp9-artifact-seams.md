# Establish supported AGP 9 artifact seams

Type: research
Status: resolved
Blocked by:

## Question

Using current Android Gradle Plugin documentation, APIs, recipes, and source where necessary, identify the supported AGP 9 extension points for generated sources/resources, class transformation, merged Manifest and XML handling, R8 configuration, Bundle transformation, signing order, and validation without modifying Consumer Project source files.

## Answer

See [`agp9-artifact-seams.md`](../research/agp9-artifact-seams.md). AGP 9.2 publicly supports generated source/resource/Manifest inputs, merged-Manifest transforms, whole-class-artifact transforms, generated R8 rules, mapping consumption, final-BUNDLE transforms, and automatic listeners. It does not expose a dependency-inclusive merged-resource or compiled-resource-table transform before signing. The public BUNDLE artifact is already finalized and signed, so any byte-changing final-AAB transform must explicitly own re-signing. Configuration-cache compatibility still depends on Kaleido's declarative task model.
