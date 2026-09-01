package com.tongsr.kaleido.gradle;

import com.google.protobuf.CodedInputStream;
import com.google.protobuf.CodedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Comparator;
import java.util.List;

final class ClassRewriteArtifacts {
    static final String PLAN_SCHEMA = "ClassRewritePlan.v1";
    static final String RECEIPT_SCHEMA = "TransformReceipt.v1";
    static final String PRODUCER = "KaleidoClassRewrite/1";

    private ClassRewriteArtifacts() {}

    static byte[] encodePlan(Plan plan) throws IOException {
        return message(output -> {
            output.writeString(1, plan.schema());
            output.writeString(2, plan.producer());
            output.writeString(3, plan.project());
            output.writeString(4, plan.variant());
            output.writeString(5, plan.adoptionPlanSha256());
            output.writeString(6, plan.manifestSha256());
            for (var input : plan.inputs()) {
                output.writeByteArray(7, message(nested -> {
                    nested.writeString(1, input.origin());
                    nested.writeString(2, input.sha256());
                }));
            }
            for (var decision : plan.decisions()) {
                output.writeByteArray(8, message(nested -> {
                    nested.writeString(1, decision.original());
                    nested.writeString(2, decision.origin());
                    nested.writeString(3, decision.inputSha256());
                    nested.writeString(4, decision.action());
                    nested.writeString(5, decision.target());
                    nested.writeString(6, decision.reason());
                }));
            }
            for (var site : plan.manifestSites()) {
                output.writeByteArray(9, message(nested -> {
                    nested.writeString(1, site.location());
                    nested.writeString(2, site.original());
                    nested.writeString(3, site.target());
                }));
            }
            for (var outputIdentity : plan.expectedOutputs()) {
                output.writeString(10, outputIdentity);
            }
        });
    }

    static Plan decodePlan(byte[] bytes, String project, String variant) throws IOException {
        var input = CodedInputStream.newInstance(bytes);
        var schema = "";
        var producer = "";
        var encodedProject = "";
        var encodedVariant = "";
        var adoptionPlanSha256 = "";
        var manifestSha256 = "";
        var inputs = new java.util.ArrayList<InputArtifact>();
        var decisions = new java.util.ArrayList<ClassDecision>();
        var sites = new java.util.ArrayList<ManifestSite>();
        var outputs = new java.util.ArrayList<String>();
        while (!input.isAtEnd()) {
            switch (input.readTag()) {
                case 10 -> schema = input.readStringRequireUtf8();
                case 18 -> producer = input.readStringRequireUtf8();
                case 26 -> encodedProject = input.readStringRequireUtf8();
                case 34 -> encodedVariant = input.readStringRequireUtf8();
                case 42 -> adoptionPlanSha256 = input.readStringRequireUtf8();
                case 50 -> manifestSha256 = input.readStringRequireUtf8();
                case 58 -> inputs.add(decodeInput(input.readByteArray()));
                case 66 -> decisions.add(decodeDecision(input.readByteArray()));
                case 74 -> sites.add(decodeSite(input.readByteArray()));
                case 82 -> outputs.add(input.readStringRequireUtf8());
                case 0 -> { }
                default -> input.skipField(input.getLastTag());
            }
        }
        if (!PLAN_SCHEMA.equals(schema)) {
            throw failure(project, variant, "schema", "Unknown Class Rewrite Plan major: " + schema,
                    "Regenerate the plan with this Kaleido version");
        }
        return new Plan(schema, producer, encodedProject, encodedVariant,
                adoptionPlanSha256, manifestSha256, inputs, decisions, sites, outputs);
    }

    static byte[] encodeReceipt(Receipt receipt) throws IOException {
        return message(output -> {
            output.writeString(1, receipt.schema());
            output.writeString(2, receipt.producer());
            output.writeString(3, receipt.project());
            output.writeString(4, receipt.variant());
            output.writeString(5, receipt.planSha256());
            output.writeString(6, receipt.outputJarSha256());
            output.writeString(7, receipt.outputManifestSha256());
            output.writeUInt32(8, receipt.appliedMappings());
            output.writeBool(9, receipt.inputDigestsRechecked());
            output.writeBool(10, receipt.outputClosureValidated());
            for (var inputDigest : receipt.inputDigests()) {
                output.writeString(11, inputDigest);
            }
        });
    }

    private static InputArtifact decodeInput(byte[] bytes) throws IOException {
        var input = CodedInputStream.newInstance(bytes);
        var origin = "";
        var sha = "";
        while (!input.isAtEnd()) {
            switch (input.readTag()) {
                case 10 -> origin = input.readStringRequireUtf8();
                case 18 -> sha = input.readStringRequireUtf8();
                case 0 -> { }
                default -> input.skipField(input.getLastTag());
            }
        }
        return new InputArtifact(origin, sha);
    }

    private static ClassDecision decodeDecision(byte[] bytes) throws IOException {
        var input = CodedInputStream.newInstance(bytes);
        var values = new String[] {"", "", "", "", "", ""};
        while (!input.isAtEnd()) {
            var tag = input.readTag();
            if (tag >= 10 && tag <= 50 && tag % 8 == 2) {
                values[(tag - 10) / 8] = input.readStringRequireUtf8();
            } else if (tag != 0) {
                input.skipField(tag);
            }
        }
        return new ClassDecision(values[0], values[1], values[2], values[3], values[4], values[5]);
    }

    private static ManifestSite decodeSite(byte[] bytes) throws IOException {
        var input = CodedInputStream.newInstance(bytes);
        var values = new String[] {"", "", ""};
        while (!input.isAtEnd()) {
            var tag = input.readTag();
            if (tag >= 10 && tag <= 26 && tag % 8 == 2) {
                values[(tag - 10) / 8] = input.readStringRequireUtf8();
            } else if (tag != 0) {
                input.skipField(tag);
            }
        }
        return new ManifestSite(values[0], values[1], values[2]);
    }

    private static byte[] message(Writer writer) throws IOException {
        var bytes = new ByteArrayOutputStream();
        var output = CodedOutputStream.newInstance(bytes);
        writer.write(output);
        output.flush();
        return bytes.toByteArray();
    }

    private static org.gradle.api.GradleException failure(
            String project, String variant, String target, String reason, String repair) {
        return new KaleidoDiagnostic("KLD-CLASS-001", project, variant, "class-rewrite",
                PLAN_SCHEMA, target, reason, repair).failure();
    }

    @FunctionalInterface
    private interface Writer {
        void write(CodedOutputStream output) throws IOException;
    }

    record Plan(
            String schema,
            String producer,
            String project,
            String variant,
            String adoptionPlanSha256,
            String manifestSha256,
            List<InputArtifact> inputs,
            List<ClassDecision> decisions,
            List<ManifestSite> manifestSites,
            List<String> expectedOutputs) {
        Plan {
            inputs = inputs.stream().sorted(Comparator.comparing(InputArtifact::origin)).toList();
            decisions = decisions.stream().sorted(Comparator.comparing(ClassDecision::original)).toList();
            manifestSites = manifestSites.stream()
                    .sorted(Comparator.comparing(ManifestSite::location)).toList();
            expectedOutputs = expectedOutputs.stream().sorted().toList();
        }
    }

    record InputArtifact(String origin, String sha256) {}
    record ClassDecision(String original, String origin, String inputSha256,
                         String action, String target, String reason) {}
    record ManifestSite(String location, String original, String target) {}
    record Receipt(String schema, String producer, String project, String variant,
                   String planSha256, String outputJarSha256, String outputManifestSha256,
                   int appliedMappings, boolean inputDigestsRechecked,
                   boolean outputClosureValidated, List<String> inputDigests) {
        Receipt {
            inputDigests = inputDigests.stream().sorted().toList();
        }
    }
}
