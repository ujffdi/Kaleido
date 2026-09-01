# Upstream rights and reuse constraints

Research snapshot: 2026-08-31 (Asia/Shanghai)

This report supplies evidence for the decision ticket **Choose the upstream integration strategy**. It does not choose that strategy and is not legal advice. Copyright conclusions below are risk assessments for a public Apache-2.0 project, not jurisdiction-specific legal opinions.

## Question and method

The investigation asks what permissions and obligations govern four materially different integration modes:

1. **Direct plugin dependency** — Kaleido resolves and invokes the upstream binary without vendoring or shading it.
2. **Compliant fork** — Kaleido maintains and distributes a modified upstream tree.
3. **Source-code copying** — selected upstream implementations or templates are moved into Kaleido.
4. **Source-informed independent implementation** — upstream behavior informs a new implementation, while upstream expression is not copied.

For each upstream, the default branch, latest release tag, complete fetched tree, all fetched branch/tag history, release metadata, build publication configuration, and published POM/JAR were inspected. Absence claims below mean no matching `LICENSE`, `COPYING`, or `NOTICE` file was found in the complete fetched tree/history at the stated snapshot; they do not prove that no separate private agreement exists.

Primary legal anchors:

- GitHub says that without a license default copyright law applies and others may not reproduce, distribute, or create derivative works; a public repository grants the narrower right to view and fork through GitHub's service. See [GitHub's repository licensing guidance](https://docs.github.com/en/repositories/managing-your-repositorys-settings-and-features/customizing-your-repository/licensing-a-repository) and [GitHub Terms, D.5–D.6](https://docs.github.com/en/site-policy/github-terms/github-terms-of-service#5-license-grant-to-other-users).
- The U.S. Copyright Office distinguishes unprotected ideas, program logic, algorithms, systems, and methods from copyrightable expression embodied in program code. See [Computer Programs](https://www.copyright.gov/register/tx-programs.html) and [Circular 31](https://www.copyright.gov/circs/circ31.pdf).
- Apache-2.0 grants rights to reproduce, modify, sublicense, and distribute source and object forms, subject to its conditions. Redistribution requires a license copy, prominent modification notices, retention of relevant notices, and propagation of an upstream `NOTICE` if one exists; the patent grant and trademark limitation also matter. See [Apache License 2.0, sections 2–6](https://www.apache.org/licenses/LICENSE-2.0.html).

## Fixed upstream snapshots

| Upstream | Default-branch revision inspected | Latest release inspected | Repository license evidence |
|---|---|---|---|
| AndroidJunkCode | [`cfcd9eed0b8d5a938033a9268a20e58e059b3039`](https://github.com/qq549631030/AndroidJunkCode/commit/cfcd9eed0b8d5a938033a9268a20e58e059b3039), committed 2026-02-13; this is also tag `2.0.0` | [`2.0.0`](https://github.com/qq549631030/AndroidJunkCode/releases/tag/2.0.0), published 2026-02-13 | No root license file or source headers, but committed publication metadata and the Maven Central POM explicitly declare Apache-2.0. Scope is therefore less clear than a conventional root license, but it is not accurate to describe the project as having no license signal at all. |
| AabResGuard | [`e8f3a5d361ce61a3d4fa8bafb9d030bbe459c400`](https://github.com/bytedance/AabResGuard/commit/e8f3a5d361ce61a3d4fa8bafb9d030bbe459c400), committed 2021-06-21 | [`0.1.9`](https://github.com/bytedance/AabResGuard/releases/tag/0.1.9), tag commit `e8e92bcca97caf38d3b2c37eef463828248eb178`, published 2021-04-01 | Root Apache-2.0 `LICENSE`; no root `NOTICE` at the inspected revision. GitHub marks the repository archived. |
| XmlClassGuard | [`198cea9ccd87129d8ffb6ec5f258190a3b3ee8a1`](https://github.com/liujingxing/XmlClassGuard/commit/198cea9ccd87129d8ffb6ec5f258190a3b3ee8a1), committed 2024-06-11 | [`1.2.7`](https://github.com/liujingxing/XmlClassGuard/releases/tag/1.2.7), tag commit `0c36e1dc7dcfa2acdaf5ad2982341f50438cebb9`, published 2024-06-11 | No license file, license statement, source header, or POM license declaration found. |

Exact dates above are repository commit/release dates, not “last updated” UI timestamps. The latter can change because of repository metadata and are unsuitable provenance anchors.

## Repository-by-repository findings

### AndroidJunkCode

#### Confirmed facts

- At `2.0.0`, [`library/gradle.properties`](https://github.com/qq549631030/AndroidJunkCode/blob/cfcd9eed0b8d5a938033a9268a20e58e059b3039/library/gradle.properties#L16-L18) names “The Apache Software License, Version 2.0,” links the Apache license, and says distribution is through the repository.
- That declaration first entered the repository in commit [`5e11f37ae72e2fccd87d04ac7ae635b6149a8a78`](https://github.com/qq549631030/AndroidJunkCode/commit/5e11f37ae72e2fccd87d04ac7ae635b6149a8a78) on 2021-02-05, as part of publishing to Maven Central. It is present in release `1.0.7` and later inspected tags, including `1.3.4` and `2.0.0`; release `1.0.6` predates it.
- The author-published [Maven Central `2.0.0` POM](https://repo1.maven.org/maven2/com/github/qq549631030/android-junk-code/2.0.0/android-junk-code-2.0.0.pom) repeats the Apache-2.0 declaration and points SCM metadata back to the GitHub repository.
- The `2.0.0` repository tree has no `LICENSE`, `COPYING`, or `NOTICE` file. The Maven binary and sources JARs also contain no embedded license copy. The [Gradle Plugin Portal marker POM](https://plugins.gradle.org/m2/io/github/qq549631030/android-junk-code/io.github.qq549631030.android-junk-code.gradle.plugin/2.0.0/io.github.qq549631030.android-junk-code.gradle.plugin-2.0.0.pom) delegates to `io.github.qq549631030:library:2.0.0`, whose Plugin Portal POM does not repeat the license field.
- The release history identifies `2.0.0` as the AGP 9 adaptation and says AGP 7.4 is its minimum. That is a capability/compatibility claim, not an additional license grant.

#### Legal-risk inference

The first-party build metadata plus the published Maven Central artifact metadata are meaningful evidence that the author intended the released AndroidJunkCode artifact to be Apache-2.0. This materially weakens the earlier “unlicensed repository” assumption in ADR-0002. However, the missing root license, missing file headers, and missing license in the Plugin Portal implementation artifact leave avoidable ambiguity about whether the declaration covers the entire source tree, all historical revisions, and generated outputs.

For a public Kaleido release, two confidence levels should therefore remain distinct:

- **Using the published `2.0.0` artifact directly:** supported by an explicit Apache-2.0 POM declaration, with provenance recorded. This is substantially better supported than direct use of XmlClassGuard.
- **Forking or copying AndroidJunkCode source/templates:** plausibly Apache-2.0 at `2.0.0`, but a root `LICENSE` addition or written confirmation from the copyright holder would remove scope ambiguity. Before that clarification, treating source incorporation as fully cleared is a policy risk rather than a confirmed fact.

There is a separate generated-output question: AndroidJunkCode's license metadata does not state an exception or ownership rule for generated Java/resources/Manifest fragments. Because exact generator templates can be copyrightable expression, a direct-use strategy should obtain confirmation that generated outputs may be distributed in Consumer Project AABs without additional attribution, or conservatively document the provenance and license.

### AabResGuard

#### Confirmed facts

- The fixed revision contains the full [Apache License 2.0](https://github.com/bytedance/AabResGuard/blob/e8f3a5d361ce61a3d4fa8bafb9d030bbe459c400/LICENSE), including its copyright notice.
- The license file was added by commit [`c6f2de3209ce3eba58d79f9b4882e42632a0c645`](https://github.com/bytedance/AabResGuard/commit/c6f2de3209ce3eba58d79f9b4882e42632a0c645) and is present in release `0.1.9` and the default-branch revision.
- No upstream root `NOTICE` file exists at those revisions, so Apache section 4(d) does not require propagating a nonexistent upstream `NOTICE`. Existing relevant copyright and attribution notices still must be retained.
- Several files carry separate Android Open Source Project Apache headers, for example [`Pair.groovy`](https://github.com/bytedance/AabResGuard/blob/e8f3a5d361ce61a3d4fa8bafb9d030bbe459c400/core/src/main/java/com/bytedance/android/aabresguard/utils/Pair.groovy). The repository also builds shaded artifacts and declares third-party dependencies. Any vendoring plan therefore needs a file/dependency provenance audit; the root license alone is not a substitute for checking bundled third-party material.
- GitHub marks the repository archived, and the default-branch head postdates the latest release. Archival affects maintenance/compatibility, not the continuing Apache-2.0 grant.

#### Legal-risk inference

Direct dependency, forking, modification, and selective source copying are all permitted by Apache-2.0 when its conditions are met. Kaleido would need to ship the Apache license, mark modified copied files, retain relevant notices, state exact source revisions, and audit any copied/shaded third-party content. Kaleido may license its own surrounding code under Apache-2.0, but cannot erase upstream notices or imply ByteDance endorsement; the Apache patent-termination and trademark clauses remain applicable.

### XmlClassGuard

#### Confirmed facts

- Neither fixed `master` revision nor tag `1.2.7` contains a license file or source license headers. Complete fetched history did not reveal a removed `LICENSE`, `COPYING`, or `NOTICE` file.
- The repository's [`plugin/maven.gradle`](https://github.com/liujingxing/XmlClassGuard/blob/198cea9ccd87129d8ffb6ec5f258190a3b3ee8a1/plugin/maven.gradle) publishes through Maven/JitPack without license metadata.
- The author-linked [JitPack `1.2.7` POM](https://jitpack.io/com/github/liujingxing/XmlClassGuard/1.2.7/XmlClassGuard-1.2.7.pom), binary JAR, and sources JAR contain no license declaration or embedded license copy.
- GitHub's public-repository terms permit viewing and forking within GitHub's service; they do not supply the broader rights to reuse, modify outside the service, or redistribute a derivative project that an open-source license would supply.

#### Legal-risk inference

Artifact availability and README installation instructions show an intent that users execute the plugin, but they do not specify the redistribution, modification, sublicensing, patent, or source-copy rights Kaleido needs. Relying on an implied permission is especially weak for an Apache-2.0 public plugin that would transitively distribute, vendor, or modify XmlClassGuard.

Until the owner supplies a compatible license or written permission:

- a public fork is only clearly covered to the extent GitHub's service-level fork grant allows; it is not a safe foundation for distributing Kaleido;
- source copying, template copying, translation to Kotlin/Java, or structure-preserving refactoring should be treated as unavailable;
- a direct transitive dependency remains licensing-ambiguous and should not be described as “open source” or “Apache-compatible” merely because JitPack can build it;
- behavior can inform a genuinely independent implementation, subject to the idea/expression boundary and separate patent risk.

## Integration-mode comparison

| Mode | What Kaleido would actually ship | AndroidJunkCode | AabResGuard | XmlClassGuard |
|---|---|---|---|---|
| Direct plugin dependency | Kaleido metadata/code resolves the original binary at build time; no upstream source is placed in Kaleido. Shading or repackaging is **not** direct dependency. | Published `2.0.0` has an explicit Apache-2.0 Maven POM. Direct use has the strongest available license evidence, but generated-output scope and absent embedded license merit clarification/provenance. | Permitted under Apache-2.0. If the binary is only resolved and not redistributed inside Kaleido, obligations are simpler; notices still belong in dependency/provenance documentation. | No express license found. Installation availability is not a reliable grant for Kaleido's transitive public use; permission should be obtained first. |
| Compliant fork | A maintained derivative tree and new releases. | Plausibly Apache-2.0 from the committed/released POM declaration, but source-scope ambiguity remains. Written confirmation or an upstream root license is the prudent gate. | Explicitly permitted; carry license, change notices, attributions, exact revision, and third-party audit. | GitHub can host a service-level fork, but broader modification/distribution rights are not granted. Not cleared for a Kaleido product fork. |
| Source-code or template copying | Upstream expression becomes part of Kaleido, even if renamed, translated, reformatted, or split across modules. | Same ambiguity as a fork, with harder per-file provenance. Do not treat rewriting syntax or names as independent implementation. | Permitted with Apache-2.0 compliance and per-file provenance; copying only “core” files still triggers the conditions. | Not rights-cleared on the evidence found; written permission/license is required before Kaleido adopts this route. |
| Source-informed independent implementation | Kaleido ships newly authored code implementing a behavior/specification, not upstream expression. | Possible, but direct access by implementers weakens clean-room evidence. Public behavior, black-box outputs, and independently written tests are safer inputs than copying internal structure/templates. | Possible, though Apache reuse may be cheaper; independent implementation does not automatically inherit Apache's patent grant for copied Contributions. | The only presently supportable implementation route without new permission. It must avoid literal/nonliteral copying of distinctive structure, naming, comments, templates, and generated text. |

### Important boundary: direct dependency versus bundling

A declaration such as “depends on upstream plugin version X” is not equivalent to placing upstream classes inside Kaleido's JAR. Shadowing, relocating, copying JAR entries, embedding sources, or publishing a fat plugin artifact is redistribution and must be assessed like a fork/source copy. Likewise, checking an upstream JAR into the Kaleido repository creates a distributed copy even when the code is never modified.

The plugin binary ordinarily runs only during the build and is not packaged into the Consumer Project's AAB. That fact reduces runtime redistribution but does not create missing rights for the build plugin itself, its transitive download, or copied generator output.

## A defensible source-informed process

“Reference the source” is not a sufficiently precise control. For an upstream without a clear license, a defensible independent implementation would separate these artifacts:

1. **Behavior record:** documented options, inputs, outputs, failure behavior, and black-box examples, each tied to a fixed upstream release.
2. **Independent contract:** Kaleido terminology and acceptance tests derived from required behavior, not from upstream class/file boundaries.
3. **Implementation provenance:** authors, dates, source materials consulted, and an explicit statement that upstream code/templates were not copied or mechanically translated.
4. **Similarity review:** before release, inspect for distinctive identifiers, comments, string/template bodies, control-flow structure, file layout, and copied tests—not merely line-for-line matches.
5. **Patent/trademark review:** independent coding addresses copyright expression, not possible patents or upstream naming/endorsement issues.

The strongest clean-room form uses one person to write the behavior record and another, without source access, to implement from that record. A same-person source-informed rewrite can still be independently authored, but provides weaker evidence if similarity is later disputed.

## Questions that require owner confirmation

### AndroidJunkCode owner

1. Does the Apache-2.0 declaration in `library/gradle.properties` and the Maven Central POM license the complete source tree at tag `2.0.0`, including generator templates?
2. May downstream projects fork, modify, and redistribute that source under Apache-2.0?
3. Are generated Java, resources, XML, and Manifest fragments unrestricted outputs, Apache-2.0 works, or subject to another rule?
4. Will the owner add a root `LICENSE` (and any required `NOTICE`) to make the scope machine- and human-verifiable?

### XmlClassGuard owner

1. Will the owner license tag `1.2.7` and the source tree under Apache-2.0 or another Kaleido-compatible license?
2. If not, will the owner grant Kaleido written rights to depend on, modify, copy, sublicense, and publicly redistribute specified files/versions?
3. Does any third-party code in the repository require separate attribution or restrict sublicensing?

Permission should identify the repository, exact revision/files, rights granted, permitted open-source redistribution, generated outputs where relevant, attribution, patent terms, and the person/entity authorized to grant it.

## Decision implications, without choosing the strategy

- **AabResGuard is rights-cleared for direct use, fork, or selective reuse**, subject to normal Apache-2.0 compliance and third-party provenance.
- **XmlClassGuard is not rights-cleared for dependency-based product integration, forking, or copying** on the evidence found. Independent behavior implementation or new written permission are the available paths.
- **AndroidJunkCode is an intermediate case:** its released Maven artifact has an explicit first-party Apache-2.0 declaration, contradicting the simple “no license” label, but its repository/package hygiene leaves source and generated-output scope ambiguous. Direct dependency and source incorporation should therefore be evaluated separately.
- A single blanket policy for all three upstreams would discard relevant differences. The eventual strategy can be hybrid, but the choice belongs to the blocked decision ticket.

## Conflict with the current ADR

ADR-0002 says AndroidJunkCode has no compatible license unless separately provided. The research found a compatible Apache-2.0 declaration in committed publication configuration since 2021 and in the published `2.0.0` Maven Central POM. ADR-0002 should be revisited by the decision ticket—not silently edited—so it can distinguish artifact-level evidence from the remaining source/generated-output ambiguity. Its conclusion for XmlClassGuard remains supported; its conclusion for AabResGuard remains supported.
