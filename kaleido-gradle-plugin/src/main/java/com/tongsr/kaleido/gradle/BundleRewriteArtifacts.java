package com.tongsr.kaleido.gradle;

import com.google.protobuf.CodedInputStream;
import com.google.protobuf.CodedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Comparator;
import java.util.List;

final class BundleRewriteArtifacts {
    static final String PLAN_SCHEMA = "BundleRewritePlan.v1";
    static final String RECEIPT_SCHEMA = "TransformReceipt.v1";
    static final String PRODUCER = "KaleidoBundleRewrite/1";

    private BundleRewriteArtifacts() {}

    static byte[] encodePlan(Plan plan) throws IOException {
        return message(output -> {
            output.writeString(1, plan.schema());
            output.writeString(2, plan.producer());
            output.writeString(3, plan.project());
            output.writeString(4, plan.variant());
            output.writeString(5, plan.inputAabSha256());
            output.writeString(6, plan.resourceTableSha256());
            for (var resource : plan.resources()) output.writeByteArray(7, message(nested -> {
                nested.writeUInt32(1, resource.id());
                nested.writeString(2, resource.packageName());
                nested.writeString(3, resource.type());
                nested.writeString(4, resource.originalName());
                nested.writeString(5, resource.targetName());
                nested.writeString(6, resource.action());
                nested.writeString(7, resource.reason());
                for (var path : resource.originalPaths()) nested.writeString(8, path);
                for (var path : resource.targetPaths()) nested.writeString(9, path);
                nested.writeBool(10, resource.nameProtected());
                nested.writeBool(11, resource.pathProtected());
            }));
            for (var entry : plan.entries()) output.writeByteArray(8, message(nested -> {
                nested.writeString(1, entry.inputPath());
                nested.writeString(2, entry.inputSha256());
                nested.writeUInt32(3, entry.compressionMethod());
                nested.writeString(4, entry.outputPath());
                nested.writeString(5, entry.action());
                nested.writeBool(6, entry.preservePayload());
            }));
            for (var outputPath : plan.expectedOutputs()) output.writeString(9, outputPath);
            for (var reference : plan.references()) output.writeByteArray(10, message(nested -> {
                nested.writeString(1, reference.origin());
                nested.writeString(2, reference.fieldPath());
                nested.writeString(3, reference.kind());
                nested.writeUInt32(4, reference.resourceId());
                nested.writeString(5, reference.originalValue());
                nested.writeString(6, reference.targetValue());
            }));
            for (var control : plan.controls()) output.writeByteArray(12, message(nested -> {
                nested.writeString(1, control.kind());
                nested.writeString(2, control.target());
                nested.writeString(3, control.inputSha256());
                nested.writeString(4, control.outputSha256());
                nested.writeString(5, control.action());
                nested.writeString(6, control.reason());
                nested.writeBool(7, control.protectedTarget());
            }));
        });
    }

    static Plan decodePlan(byte[] bytes, String project, String variant) throws IOException {
        var input = CodedInputStream.newInstance(bytes);
        var values = new String[] {"", "", "", "", "", ""};
        var resources = new java.util.ArrayList<ResourceDecision>();
        var entries = new java.util.ArrayList<EntryDecision>();
        var outputs = new java.util.ArrayList<String>();
        var references = new java.util.ArrayList<ReferenceDecision>();
        var controls = new java.util.ArrayList<ControlDecision>();
        while (!input.isAtEnd()) {
            var tag = input.readTag();
            switch (tag) {
                case 10, 18, 26, 34, 42, 50 ->
                        values[(tag - 10) / 8] = input.readStringRequireUtf8();
                case 58 -> resources.add(decodeResource(input.readByteArray()));
                case 66 -> entries.add(decodeEntry(input.readByteArray()));
                case 74 -> outputs.add(input.readStringRequireUtf8());
                case 82 -> references.add(decodeReference(input.readByteArray()));
                case 98 -> controls.add(decodeControl(input.readByteArray()));
                case 0 -> { }
                default -> input.skipField(tag);
            }
        }
        if (!PLAN_SCHEMA.equals(values[0])) {
            throw failure(project, variant, "schema",
                    "Unknown Bundle Rewrite Plan major: " + values[0],
                    "Regenerate the plan with this Kaleido version");
        }
        return new Plan(values[0], values[1], values[2], values[3], values[4], values[5],
                resources, entries, outputs, references, controls);
    }

    static byte[] encodeReceipt(Receipt receipt) throws IOException {
        return message(output -> {
            output.writeString(1, receipt.schema());
            output.writeString(2, receipt.producer());
            output.writeString(3, receipt.project());
            output.writeString(4, receipt.variant());
            output.writeString(5, receipt.planSha256());
            output.writeString(6, receipt.inputAabSha256());
            output.writeString(7, receipt.outputAabSha256());
            output.writeString(8, receipt.resourceMappingSha256());
            output.writeUInt32(9, receipt.resourceIdCount());
            output.writeUInt32(10, receipt.renamedResourceCount());
            output.writeUInt32(11, receipt.renamedPathCount());
            output.writeBool(12, receipt.referenceClosureValidated());
            output.writeBool(13, receipt.preservedPayloadsValidated());
            output.writeBool(14, receipt.bundletoolValidated());
            output.writeUInt32(15, receipt.referenceCount());
            for (var digest : receipt.preservedPayloadDigests()) output.writeString(16, digest);
            for (var digest : receipt.protectionDigests()) output.writeString(17, digest);
        });
    }

    private static ResourceDecision decodeResource(byte[] bytes) throws IOException {
        var input = CodedInputStream.newInstance(bytes);
        var id = 0;
        var values = new String[] {"", "", "", "", "", ""};
        var originalPaths = new java.util.ArrayList<String>();
        var targetPaths = new java.util.ArrayList<String>();
        var nameProtected = false;
        var pathProtected = false;
        while (!input.isAtEnd()) {
            var tag = input.readTag();
            switch (tag) {
                case 8 -> id = input.readUInt32();
                case 18, 26, 34, 42, 50, 58 ->
                        values[(tag - 18) / 8] = input.readStringRequireUtf8();
                case 66 -> originalPaths.add(input.readStringRequireUtf8());
                case 74 -> targetPaths.add(input.readStringRequireUtf8());
                case 80 -> nameProtected = input.readBool();
                case 88 -> pathProtected = input.readBool();
                case 0 -> { }
                default -> input.skipField(tag);
            }
        }
        return new ResourceDecision(id, values[0], values[1], values[2], values[3],
                values[4], values[5], originalPaths, targetPaths,
                nameProtected, pathProtected);
    }

    private static EntryDecision decodeEntry(byte[] bytes) throws IOException {
        var input = CodedInputStream.newInstance(bytes);
        var values = new String[] {"", "", "", ""};
        var method = 0;
        var preserve = false;
        while (!input.isAtEnd()) {
            var tag = input.readTag();
            switch (tag) {
                case 10 -> values[0] = input.readStringRequireUtf8();
                case 18 -> values[1] = input.readStringRequireUtf8();
                case 24 -> method = input.readUInt32();
                case 34 -> values[2] = input.readStringRequireUtf8();
                case 42 -> values[3] = input.readStringRequireUtf8();
                case 48 -> preserve = input.readBool();
                case 0 -> { }
                default -> input.skipField(tag);
            }
        }
        return new EntryDecision(values[0], values[1], method, values[2], values[3], preserve);
    }

    private static ReferenceDecision decodeReference(byte[] bytes) throws IOException {
        var input = CodedInputStream.newInstance(bytes);
        var values = new String[] {"", "", "", "", ""};
        var id = 0;
        while (!input.isAtEnd()) {
            var tag = input.readTag();
            switch (tag) {
                case 10, 18, 26 -> values[(tag - 10) / 8] = input.readStringRequireUtf8();
                case 32 -> id = input.readUInt32();
                case 42 -> values[3] = input.readStringRequireUtf8();
                case 50 -> values[4] = input.readStringRequireUtf8();
                case 0 -> { }
                default -> input.skipField(tag);
            }
        }
        return new ReferenceDecision(values[0], values[1], values[2], id,
                values[3], values[4]);
    }

    private static ControlDecision decodeControl(byte[] bytes) throws IOException {
        var input = CodedInputStream.newInstance(bytes);
        var values = new String[] {"", "", "", "", "", ""};
        var protectedTarget = false;
        while (!input.isAtEnd()) {
            var tag = input.readTag();
            switch (tag) {
                case 10, 18, 26, 34, 42, 50 ->
                        values[(tag - 10) / 8] = input.readStringRequireUtf8();
                case 56 -> protectedTarget = input.readBool();
                case 0 -> { }
                default -> input.skipField(tag);
            }
        }
        return new ControlDecision(values[0], values[1], values[2], values[3],
                values[4], values[5], protectedTarget);
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
        return new KaleidoDiagnostic("KLD-BUNDLE-001", project, variant, "bundle-rewrite",
                PLAN_SCHEMA, target, reason, repair).failure();
    }

    @FunctionalInterface private interface Writer {
        void write(CodedOutputStream output) throws IOException;
    }

    record Plan(String schema, String producer, String project, String variant,
                String inputAabSha256, String resourceTableSha256,
                List<ResourceDecision> resources, List<EntryDecision> entries,
                List<String> expectedOutputs, List<ReferenceDecision> references,
                List<ControlDecision> controls) {
        Plan {
            resources = resources.stream().sorted((left, right) ->
                            Integer.compareUnsigned(left.id(), right.id()))
                    .toList();
            entries = entries.stream().sorted(Comparator.comparing(EntryDecision::inputPath))
                    .toList();
            expectedOutputs = expectedOutputs.stream().sorted().toList();
            references = references.stream().sorted(Comparator
                    .comparing(ReferenceDecision::origin)
                    .thenComparing(ReferenceDecision::fieldPath)
                    .thenComparing(ReferenceDecision::kind)).toList();
            controls = controls.stream().sorted(Comparator
                    .comparing(ControlDecision::kind)
                    .thenComparing(ControlDecision::target)).toList();
        }
    }

    record ResourceDecision(int id, String packageName, String type, String originalName,
                            String targetName, String action, String reason,
                            List<String> originalPaths, List<String> targetPaths,
                            boolean nameProtected, boolean pathProtected) {
        ResourceDecision {
            if (originalPaths.size() != targetPaths.size()) {
                throw new IllegalArgumentException("Resource path mapping is not one-to-one");
            }
            var order = java.util.stream.IntStream.range(0, originalPaths.size()).boxed()
                    .sorted(Comparator.comparing(originalPaths::get)).toList();
            var canonicalOriginals = order.stream().map(originalPaths::get).toList();
            var canonicalTargets = order.stream().map(targetPaths::get).toList();
            originalPaths = canonicalOriginals;
            targetPaths = canonicalTargets;
        }
    }

    record EntryDecision(String inputPath, String inputSha256, int compressionMethod,
                         String outputPath, String action, boolean preservePayload) {}

    record ReferenceDecision(String origin, String fieldPath, String kind, int resourceId,
                             String originalValue, String targetValue) {}

    record ControlDecision(String kind, String target, String inputSha256,
                           String outputSha256, String action, String reason,
                           boolean protectedTarget) {}

    record Receipt(String schema, String producer, String project, String variant,
                   String planSha256, String inputAabSha256, String outputAabSha256,
                   String resourceMappingSha256, int resourceIdCount,
                   int renamedResourceCount, int renamedPathCount,
                   boolean referenceClosureValidated, boolean preservedPayloadsValidated,
                   boolean bundletoolValidated, int referenceCount,
                   List<String> preservedPayloadDigests, List<String> protectionDigests) {
        Receipt {
            preservedPayloadDigests = preservedPayloadDigests.stream().sorted().toList();
            protectionDigests = protectionDigests.stream().sorted().toList();
        }
    }
}
