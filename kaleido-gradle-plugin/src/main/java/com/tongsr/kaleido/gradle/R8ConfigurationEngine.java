package com.tongsr.kaleido.gradle;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

final class R8ConfigurationEngine {
    static final String SCHEMA = "KaleidoR8Configuration.v1";
    static final String PRODUCER = "KaleidoR8Configuration/1";
    static final int DICTIONARY_SIZE = 4096;

    private R8ConfigurationEngine() {}

    static Configuration generate(
            Map<String, String> adoption,
            ClassRewriteArtifacts.Plan classPlan) {
        var stream = required(adoption, "seed.domain.r8-dictionary");
        var applicationId = required(adoption, "applicationId");
        var plannedOutputs = Set.copyOf(classPlan.expectedOutputs());
        var fixedIdentities = new TreeSet<String>();
        for (var site : classPlan.manifestSites()) {
            var identity = resolveIdentity(site.target(), applicationId);
            if (!plannedOutputs.contains(identity)) {
                throw new IllegalArgumentException(
                        "Semantic class identity is absent from ClassRewritePlan outputs: "
                                + identity);
            }
            fixedIdentities.add(identity);
        }

        var dictionaries = new LinkedHashMap<String, String>();
        dictionaries.put("member.txt", dictionary(stream, "member", "m"));
        dictionaries.put("class.txt", dictionary(stream, "class", "C"));
        dictionaries.put("package.txt", dictionary(stream, "package", "p"));

        var rules = new StringBuilder("# ").append(SCHEMA).append('\n')
                .append("-obfuscationdictionary ../dictionaries/member.txt\n")
                .append("-classobfuscationdictionary ../dictionaries/class.txt\n")
                .append("-packageobfuscationdictionary ../dictionaries/package.txt\n");
        for (var identity : fixedIdentities) {
            rules.append("-keep,allowoptimization class ")
                    .append(identity).append(" { *; }\n");
        }
        return new Configuration(Map.copyOf(dictionaries), rules.toString(),
                List.copyOf(fixedIdentities));
    }

    private static String dictionary(String stream, String domain, String prefix) {
        var values = new TreeSet<String>();
        for (var index = 0; index < DICTIONARY_SIZE; index++) {
            var digest = SeedDerivation.derive(stream, "r8-" + domain, Integer.toString(index));
            var length = 10;
            var value = prefix + digest.substring(0, length);
            while (!values.add(value)) {
                length += 2;
                if (length > digest.length()) {
                    throw new IllegalStateException("Unable to allocate deterministic R8 token");
                }
                value = prefix + digest.substring(0, length);
            }
        }
        var text = new StringBuilder("# ").append(SCHEMA)
                .append(" kind=").append(domain).append('\n');
        values.forEach(value -> text.append(value).append('\n'));
        return text.toString();
    }

    private static String resolveIdentity(String lexical, String applicationId) {
        if (lexical.startsWith(".")) return applicationId + lexical;
        if (!lexical.contains(".")) return applicationId + "." + lexical;
        return lexical;
    }

    private static String required(Map<String, String> values, String key) {
        var value = values.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Adoption Plan is missing " + key);
        }
        return value;
    }

    record Configuration(
            Map<String, String> dictionaries,
            String rules,
            List<String> fixedIdentities) {}
}
