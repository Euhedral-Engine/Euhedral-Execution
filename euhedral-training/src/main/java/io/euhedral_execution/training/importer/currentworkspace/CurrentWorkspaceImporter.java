package io.euhedral_execution.training.importer.currentworkspace;

import io.euhedral_execution.training.data.PolicyRegistry;
import io.euhedral_execution.training.data.PolicyVector;
import io.euhedral_execution.training.scheduling.BootstrapPolicyCsv;
import java.io.IOException;
import java.io.BufferedWriter;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * TEMPORARY_CURRENT_WORKSPACE_IMPORT_REMOVAL: one-way vector import for the July 2026 workspace.
 */
public final class CurrentWorkspaceImporter {
    private static final String CATALOG = "imported-policies.vectors.csv";
    private static final String BOOTSTRAP = "bootstrap-policies.vectors.csv";
    private static final String REPORT = "import-report.csv";
    private static final String COMPLETE = "COMPLETE";

    public static CurrentWorkspaceImportResult importWorkspace(
            CurrentWorkspaceImportRequest request) throws IOException {
        Path target = request.outputDirectory();
        Path parent = target.getParent();
        if (parent == null) {
            throw new IllegalArgumentException("Import output must have a parent");
        }
        Files.createDirectories(parent);
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("Import output already exists");
        }
        Path temporary = parent.resolve("." + target.getFileName() + ".tmp-" + UUID.randomUUID());
        Files.createDirectory(temporary);
        boolean published = false;
        try {
            ImportState state = scan(request.sourceRoot());
            List<PolicyVector> policies = List.copyOf(state.registry.policiesInIdOrder());
            if (request.bootstrapPolicyCount() > policies.size()) {
                throw new IllegalArgumentException(
                        "Bootstrap count exceeds unique imported policy count");
            }
            writeCatalog(temporary.resolve(CATALOG), policies);
            writeBootstrap(temporary.resolve(BOOTSTRAP),
                    policies.subList(0, request.bootstrapPolicyCount()));
            writeReport(temporary.resolve(REPORT), state.report);
            writeForced(temporary.resolve(COMPLETE), new byte[0]);
            validate(temporary, state.report, policies.size(), request.bootstrapPolicyCount());
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException error) {
                throw new IOException("Atomic import publication is required", error);
            }
            published = true;
            return new CurrentWorkspaceImportResult(target, target.resolve(CATALOG),
                    target.resolve(BOOTSTRAP), target.resolve(REPORT), policies.size(),
                    request.bootstrapPolicyCount());
        } finally {
            if (!published && Files.exists(temporary, LinkOption.NOFOLLOW_LINKS)) {
                deleteOwnedTree(temporary);
            }
        }
    }

    private static ImportState scan(Path sourceRoot) throws IOException {
        Map<String, CurrentWorkspaceMapping> mappings = new HashMap<>();
        CurrentWorkspaceLayout.MAPPINGS.forEach(mapping ->
                mappings.put(mapping.relativePath(), mapping));
        List<DiscoveredPath> discovered = discover(sourceRoot);
        PolicyRegistry registry = new PolicyRegistry();
        ArrayList<CurrentWorkspaceImportReportRow> report = new ArrayList<>();
        for (DiscoveredPath item : discovered) {
            CurrentWorkspaceMapping mapping = mappings.get(item.relativePath);
            if (mapping == null || item.kind != PathKind.REGULAR) {
                report.add(new CurrentWorkspaceImportReportRow(item.relativePath,
                        CurrentWorkspaceSemanticType.UNKNOWN,
                        CurrentWorkspaceImportStatus.REJECTED, 1, 0, 0, 1,
                        item.kind == PathKind.REGULAR
                                ? "UNMAPPED_CURRENT_WORKSPACE_PATH"
                                : "UNSUPPORTED_OR_SYMLINK_PATH"));
                continue;
            }
            if (mapping.shape() == CurrentWorkspaceFileShape.HUMAN_SUMMARY) {
                report.add(new CurrentWorkspaceImportReportRow(item.relativePath,
                        CurrentWorkspaceSemanticType.HUMAN_READABLE_SUMMARY,
                        CurrentWorkspaceImportStatus.SKIPPED, 1, 0, 0, 0,
                        "DERIVED_SUMMARY_NOT_EVIDENCE"));
                continue;
            }
            ParsedFile parsed;
            try {
                parsed = parse(item.path, item.relativePath, mapping.shape());
            } catch (MalformedCurrentWorkspaceFile malformed) {
                report.add(new CurrentWorkspaceImportReportRow(item.relativePath,
                        CurrentWorkspaceSemanticType.UNKNOWN,
                        CurrentWorkspaceImportStatus.REJECTED, 1, 0, 0, 1,
                        "MALFORMED_CURRENT_WORKSPACE_FILE"));
                continue;
            }
            long accepted = 0;
            long duplicates = 0;
            for (PolicyVector policy : parsed.policies) {
                PolicyVector registered = registry.register(policy);
                if (registered == policy) {
                    accepted++;
                } else {
                    duplicates++;
                }
            }
            report.add(new CurrentWorkspaceImportReportRow(item.relativePath,
                    CurrentWorkspaceSemanticType.POLICY_VECTORS,
                    CurrentWorkspaceImportStatus.ACCEPTED, parsed.policies.size(),
                    accepted, duplicates, 0, "POLICY_VECTORS_IMPORTED"));
            if (mapping.shape()
                    == CurrentWorkspaceFileShape.ALTERNATING_VECTOR_MEASUREMENTS) {
                report.add(new CurrentWorkspaceImportReportRow(item.relativePath,
                        CurrentWorkspaceSemanticType.LEGACY_MEASUREMENTS,
                        CurrentWorkspaceImportStatus.REJECTED, parsed.measurementRows,
                        0, 0, parsed.measurementRows,
                        "REQUIRED_OBSERVATION_IDENTITY_UNRECOVERABLE"));
            }
        }
        report.sort(Comparator.comparing(CurrentWorkspaceImportReportRow::relativePath)
                .thenComparing(CurrentWorkspaceImportReportRow::semanticType));
        return new ImportState(registry, List.copyOf(report));
    }

    private static List<DiscoveredPath> discover(Path sourceRoot) throws IOException {
        ArrayList<DiscoveredPath> result = new ArrayList<>();
        for (String relativeRoot : List.of("euhedral-training/input",
                "euhedral-training/output")) {
            Path root = sourceRoot.resolve(relativeRoot);
            if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
                continue;
            }
            if (Files.isSymbolicLink(root)
                    || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
                result.add(new DiscoveredPath(root, relativeRoot, PathKind.UNSUPPORTED));
                continue;
            }
            try (var paths = Files.walk(root)) {
                paths.filter(path -> !path.equals(root)).forEach(path -> {
                    if (Files.isSymbolicLink(path)) {
                        result.add(new DiscoveredPath(path, relative(sourceRoot, path),
                                PathKind.UNSUPPORTED));
                    } else if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                        result.add(new DiscoveredPath(path, relative(sourceRoot, path),
                                PathKind.REGULAR));
                    } else if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                        result.add(new DiscoveredPath(path, relative(sourceRoot, path),
                                PathKind.UNSUPPORTED));
                    }
                });
            }
        }
        result.sort(Comparator.comparing(DiscoveredPath::relativePath));
        return result;
    }

    private static ParsedFile parse(Path path, String relativePath,
            CurrentWorkspaceFileShape shape) throws IOException {
        ArrayList<PolicyVector> policies = new ArrayList<>();
        int rows = 0;
        int measurements = 0;
        try (LegacyDecimalBitReader reader = new LegacyDecimalBitReader(path, relativePath)) {
            long[] record;
            while ((record = reader.nextRecord()) != null) {
                rows++;
                boolean measurement = shape
                        == CurrentWorkspaceFileShape.ALTERNATING_VECTOR_MEASUREMENTS
                        && rows % 2 == 0;
                int expected = measurement ? 10 : PolicyVector.WIDTH;
                if (record.length != expected) {
                    throw new MalformedCurrentWorkspaceFile(
                            "%s line %d: expected %d tokens"
                                    .formatted(relativePath, rows, expected));
                }
                if (measurement) {
                    for (long bits : record) {
                        if (!Double.isFinite(Double.longBitsToDouble(bits))) {
                            throw new MalformedCurrentWorkspaceFile(
                                    "Non-finite legacy measurement");
                        }
                    }
                    measurements++;
                } else {
                    double[] weights = new double[PolicyVector.WIDTH];
                    for (int i = 0; i < weights.length; i++) {
                        weights[i] = Double.longBitsToDouble(record[i]);
                    }
                    try {
                        policies.add(PolicyVector.of(weights));
                    } catch (IllegalArgumentException error) {
                        throw new MalformedCurrentWorkspaceFile(
                                "Non-finite policy vector", error);
                    }
                }
            }
        } catch (IllegalArgumentException error) {
            if (error instanceof MalformedCurrentWorkspaceFile malformed) {
                throw malformed;
            }
            throw new MalformedCurrentWorkspaceFile(error.getMessage(), error);
        }
        if (rows == 0 || shape == CurrentWorkspaceFileShape.ALTERNATING_VECTOR_MEASUREMENTS
                && rows % 2 != 0) {
            throw new MalformedCurrentWorkspaceFile(
                    "Invalid current-workspace file shape");
        }
        return new ParsedFile(List.copyOf(policies), measurements);
    }

    private static void writeCatalog(Path path, List<PolicyVector> policies) throws IOException {
        writeText(path, output -> {
            output.write("schema_version,policy_id");
            appendWeightHeader(output);
            output.write('\n');
            for (PolicyVector policy : policies) {
                output.write("1,");
                output.write(policy.id().canonical());
                appendWeights(output, policy);
                output.write('\n');
            }
        });
    }

    private static void writeBootstrap(Path path, List<PolicyVector> policies)
            throws IOException {
        writeText(path, output -> {
            output.write("schema_version,bootstrap_position,policy_id");
            appendWeightHeader(output);
            output.write('\n');
            for (int i = 0; i < policies.size(); i++) {
                PolicyVector policy = policies.get(i);
                output.write("1,");
                output.write(Integer.toString(i + 1));
                output.write(',');
                output.write(policy.id().canonical());
                appendWeights(output, policy);
                output.write('\n');
            }
        });
    }

    private static void writeReport(Path path, List<CurrentWorkspaceImportReportRow> rows)
            throws IOException {
        writeText(path, output -> {
            output.write("schema_version,path,semantic_type,status,record_count,"
                    + "accepted_count,duplicate_count,rejected_count,reason\n");
            for (CurrentWorkspaceImportReportRow row : rows) {
                output.write("1,");
                output.write(csv(row.relativePath()));
                output.write(',');
                output.write(row.semanticType().name());
                output.write(',');
                output.write(row.status().name());
                output.write(',');
                output.write(Long.toString(row.recordCount()));
                output.write(',');
                output.write(Long.toString(row.acceptedCount()));
                output.write(',');
                output.write(Long.toString(row.duplicateCount()));
                output.write(',');
                output.write(Long.toString(row.rejectedCount()));
                output.write(',');
                output.write(csv(row.reason()));
                output.write('\n');
            }
        });
    }

    private static void appendWeightHeader(BufferedWriter output) throws IOException {
        for (int i = 0; i < PolicyVector.WIDTH; i++) {
            output.write(",weight_%02d_bits".formatted(i));
        }
    }

    private static void appendWeights(BufferedWriter output, PolicyVector policy)
            throws IOException {
        for (int i = 0; i < PolicyVector.WIDTH; i++) {
            output.write(',');
            output.write("%016x".formatted(
                    Double.doubleToRawLongBits(policy.weight(i))));
        }
    }

    private static void writeText(Path path, TextWriter writer) throws IOException {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE);
                BufferedWriter output = new BufferedWriter(
                        Channels.newWriter(channel, StandardCharsets.UTF_8), 128 * 1024)) {
            writer.write(output);
            output.flush();
            channel.force(true);
        }
    }

    private static void writeForced(Path path, byte[] bytes) throws IOException {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            channel.force(true);
        }
    }

    private static void validate(Path directory,
            List<CurrentWorkspaceImportReportRow> expectedReport, int policyCount,
            int bootstrapCount) throws IOException {
        Set<String> expected = Set.of(CATALOG, BOOTSTRAP, REPORT, COMPLETE);
        HashSet<String> actual = new HashSet<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            for (Path path : stream) {
                if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                        || Files.isSymbolicLink(path)) {
                    throw new IOException("Import artifact contains unsupported member");
                }
                actual.add(path.getFileName().toString());
            }
        }
        if (!actual.equals(expected) || Files.size(directory.resolve(COMPLETE)) != 0) {
            throw new IOException("Invalid import artifact inventory");
        }
        if (countLines(directory.resolve(CATALOG)) != policyCount + 1L
                || countLines(directory.resolve(REPORT)) != expectedReport.size() + 1L) {
            throw new IOException("Invalid import artifact row count");
        }
        BootstrapPolicyCsv.read(directory.resolve(BOOTSTRAP), bootstrapCount);
    }

    private static long countLines(Path path) throws IOException {
        long count = 0;
        try (var reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            while (reader.readLine() != null) {
                count++;
            }
        }
        return count;
    }

    private static String csv(String value) {
        if (value.indexOf(',') < 0 && value.indexOf('"') < 0
                && value.indexOf('\r') < 0 && value.indexOf('\n') < 0) {
            return value;
        }
        return '"' + value.replace("\"", "\"\"") + '"';
    }

    private static void deleteOwnedTree(Path root) throws IOException {
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static String relative(Path root, Path path) {
        return root.relativize(path).toString().replace('\\', '/');
    }

    private enum PathKind {
        REGULAR,
        UNSUPPORTED
    }

    private record DiscoveredPath(Path path, String relativePath, PathKind kind) {
    }

    private record ParsedFile(List<PolicyVector> policies, int measurementRows) {
    }

    private record ImportState(PolicyRegistry registry,
            List<CurrentWorkspaceImportReportRow> report) {
    }

    @FunctionalInterface
    private interface TextWriter {
        void write(BufferedWriter writer) throws IOException;
    }

    private static final class MalformedCurrentWorkspaceFile
            extends IllegalArgumentException {
        private MalformedCurrentWorkspaceFile(String message) {
            super(message);
        }

        private MalformedCurrentWorkspaceFile(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private CurrentWorkspaceImporter() {
    }
}
