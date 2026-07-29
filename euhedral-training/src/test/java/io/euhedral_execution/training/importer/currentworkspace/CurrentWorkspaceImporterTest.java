package io.euhedral_execution.training.importer.currentworkspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.euhedral_execution.training.checkpoint.ArtifactFingerprint;
import io.euhedral_execution.training.data.PolicyHashCollisionException;
import io.euhedral_execution.training.data.PolicyRegistry;
import io.euhedral_execution.training.data.PolicyVector;
import io.euhedral_execution.training.scheduling.io.BootstrapPolicyCsv;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

// TEMPORARY_CURRENT_WORKSPACE_IMPORT_REMOVAL
class CurrentWorkspaceImporterTest {
    private static final String ALTERNATING =
            "euhedral-training/input/merger/graviton5-32core-1.txt";
    private static final String VECTORS = "euhedral-training/output/temp_data";

    @TempDir
    Path temp;
    private final AtomicInteger sequence = new AtomicInteger();

    @Test
    void importsOnlyMappedVectorsAndRejectsLegacyEvidenceAndUnknownArtifacts()
            throws Exception {
        Path source = temp.resolve("source");
        PolicyVector first = policy(0, true);
        PolicyVector second = policy(100, false);
        writeAlternating(source.resolve(
                "euhedral-training/input/merger/graviton5-32core-1.txt"),
                List.of(first, second, first));
        writeVectors(source.resolve("euhedral-training/output/temp_data"), List.of(second));
        Files.createDirectories(source.resolve("euhedral-training/input/temp"));
        Files.writeString(source.resolve(
                "euhedral-training/input/temp/graviton5-32core-1.txt"),
                "not parsed, even when malformed\n", StandardCharsets.UTF_8);
        Files.writeString(source.resolve("euhedral-training/input/state.properties"),
                "old=true\n", StandardCharsets.UTF_8);
        Files.createDirectories(source.resolve("euhedral-training/output/model"));
        Files.writeString(source.resolve("euhedral-training/output/model/member-0.params"),
                "old model\n", StandardCharsets.UTF_8);

        Path output = temp.resolve("import-a");
        CurrentWorkspaceImportResult result = CurrentWorkspaceImporter.importWorkspace(
                new CurrentWorkspaceImportRequest(source, output, 2));

        assertThat(result.uniquePolicyCount()).isEqualTo(2);
        assertThat(BootstrapPolicyCsv.read(result.bootstrapPolicies(), 2))
                .extracting(PolicyVector::id)
                .containsExactlyElementsOf(List.of(first, second).stream()
                        .sorted(java.util.Comparator.comparing(PolicyVector::id))
                        .map(PolicyVector::id).toList());
        String catalog = Files.readString(result.policyCatalog());
        assertThat(catalog).contains("0000000000000000", "8000000000000000");
        String report = Files.readString(result.importReport());
        assertThat(report)
                .contains("LEGACY_MEASUREMENTS,REJECTED,3,0,0,3,"
                        + "REQUIRED_OBSERVATION_IDENTITY_UNRECOVERABLE")
                .contains("HUMAN_READABLE_SUMMARY,SKIPPED,1,0,0,0,"
                        + "DERIVED_SUMMARY_NOT_EVIDENCE")
                .contains("state.properties,UNKNOWN,REJECTED,1,0,0,1,"
                        + "UNMAPPED_CURRENT_WORKSPACE_PATH")
                .contains("member-0.params,UNKNOWN,REJECTED,1,0,0,1,"
                        + "UNMAPPED_CURRENT_WORKSPACE_PATH");
        assertThat(Files.readAllBytes(output.resolve("COMPLETE"))).isEmpty();
        assertThat(Files.list(output).map(path -> path.getFileName().toString()).toList())
                .containsExactlyInAnyOrder("imported-policies.vectors.csv",
                        "bootstrap-policies.vectors.csv", "import-report.csv", "COMPLETE");
    }

    @Test
    void malformedMappedFileIsTransactionalAndOutputIsDeterministic() throws Exception {
        Path source = temp.resolve("source");
        PolicyVector policy = policy(7, false);
        writeVectors(source.resolve("euhedral-training/output/temp_data"), List.of(policy));
        Path malformed = source.resolve(
                "euhedral-training/input/merger/graviton5-32core-1.txt");
        Files.createDirectories(malformed.getParent());
        Files.writeString(malformed, "1 2 3\n", StandardCharsets.UTF_8);

        Path first = temp.resolve("first/import");
        Path second = temp.resolve("second/import");
        CurrentWorkspaceImporter.importWorkspace(
                new CurrentWorkspaceImportRequest(source, first, 1));
        CurrentWorkspaceImporter.importWorkspace(
                new CurrentWorkspaceImportRequest(source, second, 1));

        for (String file : List.of("imported-policies.vectors.csv",
                "bootstrap-policies.vectors.csv", "import-report.csv", "COMPLETE")) {
            assertThat(Files.readAllBytes(first.resolve(file)))
                    .containsExactly(Files.readAllBytes(second.resolve(file)));
        }
        assertThat(Files.readString(first.resolve("import-report.csv")))
                .contains("UNKNOWN,REJECTED,1,0,0,1,MALFORMED_CURRENT_WORKSPACE_FILE");
        assertThat(Files.readAllLines(first.resolve("imported-policies.vectors.csv")))
                .hasSize(2);
    }

    @Test
    void rejectsUnsafeTargetsInvalidTokensAndInsufficientCatalog() throws Exception {
        Path source = temp.resolve("source");
        Files.createDirectories(source);
        Path mapped = source.resolve("euhedral-training/output/temp_data");
        Files.createDirectories(mapped.getParent());
        Files.writeString(mapped, "+1\n", StandardCharsets.UTF_8);
        Path output = temp.resolve("import");
        assertThatThrownBy(() -> CurrentWorkspaceImporter.importWorkspace(
                new CurrentWorkspaceImportRequest(source, output, 1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exceeds unique");
        assertThat(output).doesNotExist();
        assertThatThrownBy(() -> new CurrentWorkspaceImportRequest(source,
                source.resolve("euhedral-training/output/import"), 1))
                .isInstanceOf(IllegalArgumentException.class);
        Files.createDirectory(output);
        assertThatThrownBy(() -> new CurrentWorkspaceImportRequest(source, output, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void acceptsExactSignedDecimalAndLineEndingVariants() throws Exception {
        PolicyVector signedZero = policy(0, true);
        List<String> variants = List.of(
                decimalRecord(signedZero).replace("\n", "")
                        + "\n" + measurementRecord() + "\n",
                decimalRecord(signedZero).replace("\n", "")
                        + "\r\n" + measurementRecord() + "\r\n",
                decimalRecord(signedZero).replace(" ", "\t")
                        + measurementRecord().replace(" ", "\t") + "\n");
        for (String variant : variants) {
            Path source = temp.resolve("valid-" + sequence.incrementAndGet());
            Path path = source.resolve(ALTERNATING);
            Files.createDirectories(path.getParent());
            Files.writeString(path, variant, StandardCharsets.US_ASCII);
            CurrentWorkspaceImportResult result =
                    CurrentWorkspaceImporter.importWorkspace(
                            new CurrentWorkspaceImportRequest(source,
                                    temp.resolve("valid-output-"
                                            + sequence.incrementAndGet()), 1));
            PolicyVector decoded = BootstrapPolicyCsv.read(
                    result.bootstrapPolicies(), 1).getFirst();
            assertThat(decoded.bitwiseEquals(signedZero)).isTrue();
            assertThat(Double.doubleToRawLongBits(decoded.weight(0))).isZero();
            assertThat(Double.doubleToRawLongBits(decoded.weight(1)))
                    .isEqualTo(Long.MIN_VALUE);
        }
    }

    @Test
    void rejectsEveryMalformedSignedDecimalAndRecordGrammarWithoutPartialImport()
            throws Exception {
        for (String token : List.of("+1", "--1", "-", "1,0", "1.0", "1e0",
                "1E0", "9223372036854775808", "-9223372036854775809",
                "1\u000b2", "1\u000c2")) {
            assertMappedFileRejected(vectorLine(token) + measurementRecord() + "\n");
        }
        assertMappedFileRejected("\ufeff" + vectorLine("0")
                + measurementRecord() + "\n");
        assertMappedFileRejected(vectorLine("0") + measurementRecord());
        assertMappedFileRejected(decimalRecord(policy(3, false)));
        assertMappedFileRejected("1 2 3\n" + measurementRecord() + "\n");
        assertMappedFileRejected(vectorLine(Long.toString(
                Double.doubleToRawLongBits(Double.NaN)))
                + measurementRecord() + "\n");
        assertMappedFileRejected(decimalRecord(policy(4, false))
                + measurementLine(Long.toString(
                Double.doubleToRawLongBits(Double.POSITIVE_INFINITY))));
    }

    @Test
    void streamsMoreThanOneParserBufferAndIsCreationOrderIndependent()
            throws Exception {
        Path forward = temp.resolve("large-forward");
        Path reverse = temp.resolve("large-reverse");
        writeLargeVectors(forward.resolve(VECTORS), 400, false);
        writeLargeVectors(reverse.resolve(VECTORS), 400, true);
        assertThat(Files.size(forward.resolve(VECTORS))).isGreaterThan(128 * 1024);
        CurrentWorkspaceImportResult first = CurrentWorkspaceImporter.importWorkspace(
                new CurrentWorkspaceImportRequest(forward,
                        temp.resolve("large-output-a"), 10));
        CurrentWorkspaceImportResult second = CurrentWorkspaceImporter.importWorkspace(
                new CurrentWorkspaceImportRequest(reverse,
                        temp.resolve("large-output-b"), 10));
        assertThat(first.uniquePolicyCount()).isEqualTo(400);
        for (String name : List.of("imported-policies.vectors.csv",
                "bootstrap-policies.vectors.csv", "import-report.csv", "COMPLETE")) {
            assertThat(Files.mismatch(first.directory().resolve(name),
                    second.directory().resolve(name))).isEqualTo(-1);
        }
    }

    @Test
    void rejectsSymlinksAndUnsupportedFifosWithoutFollowingThem() throws Exception {
        Path source = temp.resolve("special-source");
        writeVectors(source.resolve(VECTORS), List.of(policy(11, false)));
        Path symlink = source.resolve("euhedral-training/input/linked.txt");
        Files.createDirectories(symlink.getParent());
        Files.createSymbolicLink(symlink, Path.of("../../output/temp_data"));

        Path fifo = source.resolve("euhedral-training/input/fifo");
        boolean fifoCreated;
        try {
            Process process = new ProcessBuilder("mkfifo", fifo.toString()).start();
            fifoCreated = process.waitFor() == 0;
        } catch (IOException unavailable) {
            fifoCreated = false;
        }
        CurrentWorkspaceImportResult result = CurrentWorkspaceImporter.importWorkspace(
                new CurrentWorkspaceImportRequest(source,
                        temp.resolve("special-output"), 1));
        String report = Files.readString(result.importReport());
        assertThat(report).contains(
                "euhedral-training/input/linked.txt,UNKNOWN,REJECTED,1,0,0,1,"
                        + "UNSUPPORTED_OR_SYMLINK_PATH");
        if (fifoCreated) {
            assertThat(report).contains(
                    "euhedral-training/input/fifo,UNKNOWN,REJECTED,1,0,0,1,"
                            + "UNSUPPORTED_OR_SYMLINK_PATH");
        } else {
            Assumptions.abort("mkfifo is unavailable; symlink behavior was verified");
        }
    }

    @Test
    void publicationFailuresAndExistingTargetsPreserveAllUnownedBytes()
            throws Exception {
        Path source = temp.resolve("failure-source");
        writeVectors(source.resolve(VECTORS), List.of(policy(13, false)));
        String sourceHash = ArtifactFingerprint.sha256(source);
        for (CurrentWorkspaceImporter.PublicationPoint point
                : CurrentWorkspaceImporter.PublicationPoint.values()) {
            Path output = temp.resolve("failure-" + point).resolve("import");
            CurrentWorkspaceImportRequest request =
                    new CurrentWorkspaceImportRequest(source, output, 1);
            assertThatThrownBy(() -> CurrentWorkspaceImporter.importWorkspace(
                    request, reached -> {
                        if (reached == point) {
                            throw new IOException("injected " + point);
                        }
                    })).isInstanceOf(IOException.class)
                    .hasMessageContaining("injected " + point);
            assertThat(output).doesNotExist();
            assertThat(temporarySiblings(output)).isEmpty();
            assertThat(ArtifactFingerprint.sha256(source)).isEqualTo(sourceHash);
        }

        Path existing = temp.resolve("existing-target");
        Files.createDirectories(existing);
        Files.writeString(existing.resolve("owned.txt"), "leave me\n");
        String existingHash = ArtifactFingerprint.sha256(existing);
        assertThatThrownBy(() -> new CurrentWorkspaceImportRequest(
                source, existing, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThat(ArtifactFingerprint.sha256(existing)).isEqualTo(existingHash);
        assertThat(ArtifactFingerprint.sha256(source)).isEqualTo(sourceHash);
    }

    @Test
    void registryCollisionSeamProvesImporterRegistrationIsFatal() throws Exception {
        PolicyVector first = policy(21, false);
        PolicyVector collision = policy(22, false);
        var id = PolicyVector.class.getDeclaredField("id");
        id.setAccessible(true);
        id.set(collision, first.id());
        PolicyRegistry registry = new PolicyRegistry();
        registry.register(first);
        assertThatThrownBy(() -> registry.register(collision))
                .isInstanceOf(PolicyHashCollisionException.class);
    }

    private void assertMappedFileRejected(String malformed) throws Exception {
        int id = sequence.incrementAndGet();
        Path source = temp.resolve("malformed-" + id);
        PolicyVector surviving = policy(1000 + id, false);
        writeVectors(source.resolve(VECTORS), List.of(surviving));
        Path mapped = source.resolve(ALTERNATING);
        Files.createDirectories(mapped.getParent());
        Files.writeString(mapped, malformed, StandardCharsets.UTF_8);
        CurrentWorkspaceImportResult result = CurrentWorkspaceImporter.importWorkspace(
                new CurrentWorkspaceImportRequest(source,
                        temp.resolve("malformed-output-" + id), 1));
        assertThat(result.uniquePolicyCount()).isOne();
        assertThat(BootstrapPolicyCsv.read(result.bootstrapPolicies(), 1).getFirst()
                .bitwiseEquals(surviving)).isTrue();
        assertThat(Files.readString(result.importReport()))
                .contains("UNKNOWN,REJECTED,1,0,0,1,"
                        + "MALFORMED_CURRENT_WORKSPACE_FILE");
    }

    private static String vectorLine(String firstToken) {
        ArrayList<String> tokens = new ArrayList<>();
        tokens.add(firstToken);
        for (int index = 1; index < PolicyVector.WIDTH; index++) {
            tokens.add("0");
        }
        return String.join(" ", tokens) + "\n";
    }

    private static String measurementRecord() {
        return measurementLine("0").stripTrailing();
    }

    private static String measurementLine(String firstToken) {
        ArrayList<String> tokens = new ArrayList<>();
        tokens.add(firstToken);
        for (int index = 1; index < 10; index++) {
            tokens.add("0");
        }
        return String.join(" ", tokens) + "\n";
    }

    private static void writeLargeVectors(Path path, int count, boolean reverse)
            throws Exception {
        Files.createDirectories(path.getParent());
        try (BufferedWriter writer = Files.newBufferedWriter(path,
                StandardCharsets.US_ASCII)) {
            for (int offset = 0; offset < count; offset++) {
                int seed = reverse ? count - 1 - offset : offset;
                writer.write(decimalRecord(policy(10_000 + seed, false)));
            }
        }
    }

    private static List<Path> temporarySiblings(Path output) throws Exception {
        Path parent = output.getParent();
        if (!Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) {
            return List.of();
        }
        try (var paths = Files.list(parent)) {
            return paths.filter(path -> path.getFileName().toString()
                    .startsWith("." + output.getFileName() + ".tmp-")).toList();
        }
    }

    private static PolicyVector policy(int seed, boolean signedZero) {
        double[] weights = new double[PolicyVector.WIDTH];
        for (int i = 0; i < weights.length; i++) {
            weights[i] = seed + i / 100.0;
        }
        if (signedZero) {
            weights[0] = 0.0;
            weights[1] = -0.0;
        }
        return PolicyVector.of(weights);
    }

    private static void writeAlternating(Path path, List<PolicyVector> policies)
            throws Exception {
        Files.createDirectories(path.getParent());
        StringBuilder output = new StringBuilder();
        for (PolicyVector policy : policies) {
            output.append(decimalRecord(policy));
            for (int i = 0; i < 10; i++) {
                if (i != 0) {
                    output.append(' ');
                }
                output.append(Double.doubleToRawLongBits(1.0 + i));
            }
            output.append('\n');
        }
        Files.writeString(path, output, StandardCharsets.US_ASCII);
    }

    private static void writeVectors(Path path, List<PolicyVector> policies) throws Exception {
        Files.createDirectories(path.getParent());
        StringBuilder output = new StringBuilder();
        policies.forEach(policy -> output.append(decimalRecord(policy)));
        Files.writeString(path, output, StandardCharsets.US_ASCII);
    }

    private static String decimalRecord(PolicyVector policy) {
        StringBuilder output = new StringBuilder();
        for (int i = 0; i < PolicyVector.WIDTH; i++) {
            if (i != 0) {
                output.append(' ');
            }
            output.append(Double.doubleToRawLongBits(policy.weight(i)));
        }
        return output.append('\n').toString();
    }
}
