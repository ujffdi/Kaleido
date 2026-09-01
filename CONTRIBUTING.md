# Contributing

Use JDK 17 and run `./gradlew test validatePlugins`. Changes to the Gradle plugin
must preserve the plan-first public-artifact pipeline in
`docs/agents/kaleido-core-pipeline.md`, add focused unit/TestKit evidence, and
update public docs when a contract changes. Never add production credentials,
private Consumer behavior, absolute paths, unsupported compatibility claims, or
upstream source/templates without completed provenance review.

Public breaking changes follow the Bridge Release and deprecation policy. A pull
request may not waive matrix, runtime, reproducibility, performance, provenance,
documentation, security-review, approval, or post-publication gates.
