package com.tongsr.kaleido.gradle

import java.nio.charset.StandardCharsets
import java.util.HashSet
import java.util.LinkedHashSet
import org.gradle.api.GradleException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ClassRewriteContractTest {
    @Test(timeout = 10000)
    fun adversarialLargeInventoryAndPrefixCollisionsRemainBounded() {
        val roots = LinkedHashSet<String>()
        val reserved = LinkedHashSet<String>()
        val stream = "complexity-probe"
        for (index in 0 until 10_000) {
            val name = "com.example.large.C" + index
            roots.add(name)
            reserved.add(name)
            val digest = SeedDerivation.derive(stream, "class-identity", name)
            reserved.add(
                "com.example.large.k" + digest.substring(0, 6) +
                    ".C" + digest.substring(0, 10),
            )
        }
        val mapping = RewriteClassesAndManifestTask.allocateMapping(
            roots,
            setOf(),
            reserved,
            stream,
        )
        assertEquals(roots.size, mapping.size)
        assertEquals(mapping.size, HashSet(mapping.values).size)
    }

    @Test
    fun protobufPlanIsCanonicalAndRejectsUnknownMajor() {
        val first = plan(ClassRewriteArtifacts.PLAN_SCHEMA)
        val reordered = ClassRewriteArtifacts.Plan(
            first.schema,
            first.producer,
            first.project,
            first.variant,
            first.adoptionPlanSha256,
            first.manifestSha256,
            listOf(first.inputs[1], first.inputs[0]),
            listOf(first.decisions[1], first.decisions[0]),
            listOf(first.manifestSites[1], first.manifestSites[0]),
            listOf(first.expectedOutputs[1], first.expectedOutputs[0]),
        )

        assertArrayEquals(
            ClassRewriteArtifacts.encodePlan(first),
            ClassRewriteArtifacts.encodePlan(reordered),
        )
        val decoded = ClassRewriteArtifacts.decodePlan(
            ClassRewriteArtifacts.encodePlan(first),
            ":app",
            "release",
        )
        assertEquals(first, decoded)

        val failure = assertThrows(GradleException::class.java) {
            ClassRewriteArtifacts.decodePlan(
                ClassRewriteArtifacts.encodePlan(plan("ClassRewritePlan.v2")),
                ":app",
                "release",
            )
        }
        assertTrue(failure.message!!.contains("KLD-CLASS-001"))
        assertTrue(failure.message!!.contains("Unknown Class Rewrite Plan major"))
    }

    @Test
    fun identityAllocationIsStableProtectedAndCollisionExtending() {
        val roots = setOf("example.app.MainActivity", "example.app.MainActivity\$Nested")
        val stream = SeedDerivation.fingerprint("class-stream")
        val first = RewriteClassesAndManifestTask.allocateMapping(
            roots,
            setOf(),
            roots,
            stream,
        )
        val repeated = RewriteClassesAndManifestTask.allocateMapping(
            roots,
            setOf(),
            roots,
            stream,
        )
        assertEquals(first, repeated)
        assertTrue(
            first["example.app.MainActivity\$Nested"]!!
                .startsWith(first["example.app.MainActivity"] + "\$C"),
        )

        val reserved = HashSet(roots)
        reserved.add(first.getValue("example.app.MainActivity"))
        val extended = RewriteClassesAndManifestTask.allocateMapping(
            roots,
            setOf(),
            reserved,
            stream,
        )
        assertNotEquals(
            first["example.app.MainActivity"],
            extended["example.app.MainActivity"],
        )
        assertFalse(
            RewriteClassesAndManifestTask.allocateMapping(
                roots,
                setOf("example.app.MainActivity"),
                roots,
                stream,
            ).containsKey("example.app.MainActivity"),
        )
    }

    @Test
    fun manifestRegistryRewritesClassSitesButNotAliasIdentity() {
        val xml = """
                <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                    package="example.app">
                    <application android:name=".App">
                        <activity android:name=".MainActivity" />
                        <activity-alias android:name=".StableAlias"
                            android:targetActivity=".MainActivity" />
                        <meta-data android:name="example.app.MainActivity"
                            android:value="example.app.MainActivity" />
                    </application>
                </manifest>
                """.trimIndent()
        val document = RewriteClassesAndManifestTask.parseManifest(
            xml.toByteArray(StandardCharsets.UTF_8),
            ":app",
            "release",
        )
        val references = RewriteClassesAndManifestTask.manifestReferences(
            document,
            "example.app",
        )

        assertTrue(
            references.any { reference ->
                reference.location.contains("activity[0]@android:name")
            },
        )
        assertTrue(
            references.any { reference ->
                reference.location.contains("activity-alias[0]@android:targetActivity")
            },
        )
        assertFalse(
            references.any { reference ->
                reference.location.contains("activity-alias[0]@android:name")
            },
        )
        assertFalse(
            references.any { reference ->
                reference.location.contains("meta-data")
            },
        )
    }

    private fun plan(schema: String): ClassRewriteArtifacts.Plan =
        ClassRewriteArtifacts.Plan(
            schema,
            ClassRewriteArtifacts.PRODUCER,
            ":app",
            "release",
            "a".repeat(64),
            "b".repeat(64),
            listOf(
                ClassRewriteArtifacts.InputArtifact("directory/b", "d".repeat(64)),
                ClassRewriteArtifacts.InputArtifact("directory/a", "c".repeat(64)),
            ),
            listOf(
                ClassRewriteArtifacts.ClassDecision(
                    "example.B",
                    "directory/b!B.class",
                    "f".repeat(64),
                    "UNTOUCHED",
                    "example.B",
                    "outside",
                ),
                ClassRewriteArtifacts.ClassDecision(
                    "example.A",
                    "directory/a!A.class",
                    "e".repeat(64),
                    "REWRITE",
                    "example.k.Ca",
                    "root",
                ),
            ),
            listOf(
                ClassRewriteArtifacts.ManifestSite(
                    "manifest/activity[1]@android:name",
                    ".B",
                    ".k.Cb",
                ),
                ClassRewriteArtifacts.ManifestSite(
                    "manifest/activity[0]@android:name",
                    ".A",
                    ".k.Ca",
                ),
            ),
            listOf("example.k.Cb", "example.k.Ca"),
        )
}
