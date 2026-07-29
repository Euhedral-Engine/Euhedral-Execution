package io.euhedral_execution.training.importer.currentworkspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.euhedral_execution.training.data.PolicyVector;
import io.euhedral_execution.training.scheduling.io.BootstrapPolicyCsv;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

// TEMPORARY_CURRENT_WORKSPACE_IMPORT_REMOVAL
class CurrentWorkspaceImporterTest {
    @TempDir
    Path temp;

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
