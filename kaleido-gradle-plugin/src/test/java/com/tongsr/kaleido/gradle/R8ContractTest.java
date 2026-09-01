package com.tongsr.kaleido.gradle;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.Test;

public final class R8ContractTest {
    @Test
    public void dictionariesAndRequiredIdentityRulesAreCanonicalAndSeedSensitive() {
        var plan = plan();
        var first = R8ConfigurationEngine.generate(adoption("a".repeat(64)), plan);
        var repeated = R8ConfigurationEngine.generate(adoption("a".repeat(64)), plan);
        var changed = R8ConfigurationEngine.generate(adoption("b".repeat(64)), plan);

        assertEquals(first, repeated);
        assertNotEquals(first.dictionaries(), changed.dictionaries());
        assertEquals(List.of("example.app.k123456.C1234567890"), first.fixedIdentities());
        assertTrue(first.rules().contains(
                "-keep,allowoptimization class example.app.k123456.C1234567890 { *; }"));
        assertTrue(first.rules().contains(
                "-obfuscationdictionary ../dictionaries/member.txt"));
        assertEquals(R8ConfigurationEngine.DICTIONARY_SIZE + 1,
                first.dictionaries().get("member.txt").lines().count());
    }

    @Test
    public void compositionPreservesR8MappingInformationAndRewritesOnlyStructuredOwnersAndTypes() {
        var rawKaleido = """
                schema=KaleidoRawClassMapping.v1
                example.app.JavaOwner -> example.app.k1.C1
                example.app.KotlinOwner -> example.app.k2.C2
                example.app.Removed -> example.app.k3.C3
                """;
        var residual = "# {\"id\":\"com.android.tools.r8.residualsignature\","
                + "\"signature\":\"(Lx;)V\"}";
        var rawR8 = """
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
                """.formatted(residual);

        var result = R8MappingComposer.compose(rawKaleido, rawR8);

        assertTrue(result.composedMapping().contains("example.app.JavaOwner -> a:"));
        assertTrue(result.composedMapping().contains("example.app.KotlinOwner -> b:"));
        assertTrue(result.composedMapping().contains(
                "example.app.KotlinOwner call(example.app.KotlinOwner)"));
        assertTrue(result.composedMapping().contains(residual));
        assertTrue(result.composedMapping().contains(
                "example.app.Protected -> example.app.Protected:"));
        assertFalse(result.composedMapping().contains("example.app.Removed ->"));
        assertFalse(result.composedMapping().contains("# pg_map_id: 0123"));
        assertEquals("9.2.14", result.rawMetadata().compilerVersion());
        assertEquals("2.2", result.rawMetadata().mappingVersion());
        assertEquals("0123", result.rawMetadata().pgMapId());
    }

    private static Map<String, String> adoption(String stream) {
        return Map.of(
                "applicationId", "example.app",
                "seed.domain.r8-dictionary", stream);
    }

    private static ClassRewriteArtifacts.Plan plan() {
        return new ClassRewriteArtifacts.Plan(
                ClassRewriteArtifacts.PLAN_SCHEMA,
                ClassRewriteArtifacts.PRODUCER,
                ":app",
                "release",
                "a".repeat(64),
                "b".repeat(64),
                List.of(),
                List.of(new ClassRewriteArtifacts.ClassDecision(
                        "example.app.MainActivity", "directory/a", "c".repeat(64),
                        "REWRITE", "example.app.k123456.C1234567890", "root")),
                List.of(new ClassRewriteArtifacts.ManifestSite(
                        "manifest/activity[0]@android:name", ".MainActivity",
                        ".k123456.C1234567890")),
                List.of("example.app.k123456.C1234567890"));
    }
}
