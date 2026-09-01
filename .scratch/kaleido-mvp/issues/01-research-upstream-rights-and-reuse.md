# Establish upstream rights and reuse constraints

Type: research
Status: resolved
Blocked by:

## Question

Using repository files, commit history, releases, and first-party licensing guidance, what permissions and obligations govern direct dependency, forked modification, source copying, and source-informed reimplementation for AndroidJunkCode, AabResGuard, and XmlClassGuard? Record exact repository revisions and distinguish confirmed facts from legal-risk inference.

## Answer

See [`upstream-rights-and-reuse.md`](../research/upstream-rights-and-reuse.md). AabResGuard is clearly Apache-2.0 and supports direct use, compliant forks, or selective reuse. AndroidJunkCode 2.0.0 declares Apache-2.0 in first-party publication metadata and Maven Central, but missing repository/package license files and unspecified generated-output rights make direct artifact use materially clearer than source copying. XmlClassGuard has no discovered license grant, so dependency integration, forking, and copying remain uncleared without written permission; independent behavior implementation is the available route.
