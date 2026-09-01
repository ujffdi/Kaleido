package com.tongsr.kaleido.gradle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class R8ContractTest {
    @Test
    fun dictionariesAndRequiredIdentityRulesAreCanonicalAndSeedSensitive() {
        val plan = plan()
        val first = R8ConfigurationEngine.generate(adoption("a".repeat(64)), plan)
        val repeated = R8ConfigurationEngine.generate(adoption("a".repeat(64)), plan)
        val changed = R8ConfigurationEngine.generate(adoption("b".repeat(64)), plan)

        assertEquals(first, repeated)
        assertNotEquals(first.dictionaries, changed.dictionaries)
        assertEquals(listOf("example.app.k123456.C1234567890"), first.fixedIdentities)
        assertTrue(
            first.rules.contains(
                "-keep,allowoptimization class example.app.k123456.C1234567890 { *; }",
            ),
        )
        assertTrue(
            first.rules.contains(
                "-obfuscationdictionary ../dictionaries/member.txt",
            ),
        )
        assertEquals(
            R8ConfigurationEngine.DICTIONARY_SIZE + 1,
            first.dictionaries.getValue("member.txt").reader().readLines().size,
        )
    }

    @Test
    fun compositionPreservesR8MappingInformationAndRewritesOnlyStructuredOwnersAndTypes() {
        val rawKaleido = """
                schema=KaleidoRawClassMapping.v1
                example.app.JavaOwner -> example.app.k1.C1
                example.app.KotlinOwner -> example.app.k2.C2
                example.app.Removed -> example.app.k3.C3
        """.trimIndent()
        val residual = "# {\"id\":\"com.android.tools.r8.residualsignature\"," +
            "\"signature\":\"(Lx;)V\"}"
        val rawR8 = """
                # compiler: R8
                # compiler_version: 9.2.14
                # min_api: 26
                # {"id":"com.android.tools.r8.mapping","version":"2.2"}
                # pg_map_id: 0123
                # pg_map_hash: SHA-256 0123
                example.app.k1.C1 -> a:
                # {"id":"sourceFile","fileName":"JavaOwner.java"}
                    1:1:example.app.k2.C2 call(example.app.k2.C2):4:4 -> x
                %s
                example.app.k2.C2 -> b:
                # {"id":"sourceFile","fileName":"KotlinOwner.kt"}
                    1:1:void invoke():8:8 -> y
                example.app.Protected -> example.app.Protected:
        """.trimIndent().format(residual)

        val result = R8MappingComposer.compose(rawKaleido, rawR8)

        assertTrue(result.composedMapping.contains("example.app.JavaOwner -> a:"))
        assertTrue(result.composedMapping.contains("example.app.KotlinOwner -> b:"))
        assertTrue(
            result.composedMapping.contains(
                "example.app.KotlinOwner call(example.app.KotlinOwner)",
            ),
        )
        assertTrue(result.composedMapping.contains(residual))
        assertTrue(
            result.composedMapping.contains(
                "example.app.Protected -> example.app.Protected:",
            ),
        )
        assertFalse(result.composedMapping.contains("example.app.Removed ->"))
        assertFalse(result.composedMapping.contains("# pg_map_id: 0123"))
        assertEquals("9.2.14", result.rawMetadata.compilerVersion)
        assertEquals("2.2", result.rawMetadata.mappingVersion)
        assertEquals("0123", result.rawMetadata.pgMapId)
    }

    private fun adoption(stream: String): Map<String, String> = mapOf(
        "applicationId" to "example.app",
        "seed.domain.r8-dictionary" to stream,
    )

    private fun plan(): ClassRewriteArtifacts.Plan = ClassRewriteArtifacts.Plan(
        ClassRewriteArtifacts.PLAN_SCHEMA,
        ClassRewriteArtifacts.PRODUCER,
        ":app",
        "release",
        "a".repeat(64),
        "b".repeat(64),
        listOf(),
        listOf(
            ClassRewriteArtifacts.ClassDecision(
                "example.app.MainActivity",
                "directory/a",
                "c".repeat(64),
                "REWRITE",
                "example.app.k123456.C1234567890",
                "root",
            ),
        ),
        listOf(
            ClassRewriteArtifacts.ManifestSite(
                "manifest/activity[0]@android:name",
                ".MainActivity",
                ".k123456.C1234567890",
            ),
        ),
        listOf("example.app.k123456.C1234567890"),
    )
}
