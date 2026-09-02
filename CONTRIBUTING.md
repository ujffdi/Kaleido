# Contributing

Use JDK 17 and run `./gradlew test validatePlugins`. Changes to the Gradle plugin
must preserve the plan-first public-artifact pipeline in
`docs/agents/kaleido-core-pipeline.md`, add focused unit/TestKit evidence, and
update public docs when a contract changes. Never add production credentials,
private Consumer behavior, absolute paths, unsupported compatibility claims, or
upstream source/templates without completed provenance review.

Keep plugin tests, the public Sample build, documentation links, and
`validatePlugins` passing. Performance, device, and extended compatibility work
is optional and must be reported as such when performed.
