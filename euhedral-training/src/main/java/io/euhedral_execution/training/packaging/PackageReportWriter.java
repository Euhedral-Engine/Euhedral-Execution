package io.euhedral_execution.training.packaging;

import io.euhedral_execution.training.data.enums.EvidenceOrigin;
import io.euhedral_execution.training.data.io.CanonicalCsv;
import io.euhedral_execution.training.learning.enums.ModelAcceptanceStatus;
import io.euhedral_execution.training.packaging.config.TrainingRunPackageInputs;
import io.euhedral_execution.training.packaging.enums.TrainingRunPackageStatus;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class PackageReportWriter {
    static void write(PackageSourceSet source, TrainingRunPackageInputs inputs, Path root)
            throws IOException {
        CanonicalFileSupport.write(root.resolve("README.md"), readme(source, inputs));
        CanonicalFileSupport.write(root.resolve("reports/robust-ranking.md"),
                ranking(source));
        CanonicalFileSupport.write(root.resolve("reports/source-scenario-comparison.md"),
                scenarios(source));
    }

    private static String readme(PackageSourceSet source, TrainingRunPackageInputs inputs)
            throws IOException {
        var checkpoint = source.loaded().checkpoint();
        StringBuilder out = new StringBuilder("# Euhedral training run ")
                .append(checkpoint.trainingRunId()).append("\n\n## Status\n\n")
                .append("- Checkpoint stage: `").append(checkpoint.stage()).append("`\n")
                .append("- Checkpoint revision: ").append(checkpoint.revision()).append("\n")
                .append("- Package status: `").append(source.status()).append("`\n")
                .append("- More execution required: ")
                .append(source.status() == TrainingRunPackageStatus.COMPLETE ? "no" : "yes")
                .append("\n");
        if (source.omissions().isEmpty()) out.append("- Omissions: none\n");
        else for (PackageOmission omission : source.omissions()) {
            out.append("- Omission `").append(omission.semanticGroup()).append("`: `")
                    .append(omission.reason()).append("`\n");
        }
        out.append("\n## Winning policies\n\n");
        if (source.merge() == null || source.winners().isEmpty()) {
            out.append("No fully eligible winning policy is available at this checkpoint.\n");
        } else {
            List<List<String>> ranking = CanonicalCsv.read(
                    source.merge().resolve("robust-ranking.csv"));
            out.append("| Rank | Policy ID | Worst quality | Quality P25 | Geometric mean | "
                    + "Dispersion MAD | Mean non-success | Mean timeout |\n"
                    + "| ---: | --- | ---: | ---: | ---: | ---: | ---: | ---: |\n");
            int emitted = 0;
            for (int index = 1; index < ranking.size() && emitted < 10; index++) {
                List<String> row = ranking.get(index);
                if (!row.get(3).equals("true")) continue;
                out.append("| ").append(cell(row.get(1))).append(" | ")
                        .append(cell(row.get(2))).append(" | ").append(cell(row.get(8)))
                        .append(" | ").append(cell(row.get(9))).append(" | ")
                        .append(cell(row.get(10))).append(" | ").append(cell(row.get(11)))
                        .append(" | ").append(cell(row.get(13))).append(" | ")
                        .append(cell(row.get(14))).append(" |\n");
                emitted++;
            }
        }
        out.append("\n## Required source scenarios\n\n"
                + "| Scenario ID | Environment | Sources | Physical cores | Ratio |\n"
                + "| --- | --- | ---: | ---: | ---: |\n");
        checkpoint.requiredScenarios().forEach(scenario -> out.append("| ")
                .append(cell(scenario.canonical())).append(" | ")
                .append(cell(scenario.environmentId())).append(" | ")
                .append(scenario.sourceCount()).append(" | ")
                .append(scenario.availablePhysicalCoreCount()).append(" | ")
                .append(scenario.ratio().numerator()).append('/')
                .append(scenario.ratio().denominator()).append(" |\n"));
        out.append("\n## Coverage and ranking rule\n\n"
                + "Publication eligibility requires a valid result for every required exact "
                + "scenario. Missing and rejected scenarios are not imputed. Eligible policies "
                + "are ordered lexicographically by worst quality, quality P25, geometric mean "
                + "quality, lower dispersion MAD, and measurement stability.\n\n"
                + "## Calibration health\n\n");
        if (source.merge() == null) {
            out.append("Calibration is not available. Acceptance mode: `n/a`.\n");
        } else {
            Map<String, Integer> counts = calibrationCounts(source.merge());
            out.append("- Reference: ").append(counts.getOrDefault("REFERENCE", 0)).append("\n")
                    .append("- Strong: ").append(counts.getOrDefault("CALIBRATED", 0))
                    .append("\n- Weak: ")
                    .append(counts.getOrDefault("WEAKLY_CALIBRATED", 0))
                    .append("\n- Failed/uncalibrated: ")
                    .append(counts.getOrDefault("UNCALIBRATED", 0)).append("\n")
                    .append("- Acceptance mode: `").append(source.calibrationAcceptance())
                    .append("`\n");
        }
        out.append("\n## Model\n\n");
        if (source.modelMetadata() == null) {
            out.append("No model artifact is available at this checkpoint.\n");
        } else {
            boolean accepted = source.modelMetadata().acceptanceStatus()
                    == ModelAcceptanceStatus.ACCEPTED;
            out.append("Model status: `")
                    .append(accepted ? "accepted/deployable" : "rejected")
                    .append("`. Dataset fingerprint: `")
                    .append(source.modelMetadata().datasetFingerprintSha256()).append("`.");
            if (!source.modelMetadata().acceptanceReasons().isEmpty()) {
                out.append(" Acceptance reasons: ")
                        .append(String.join(", ", source.modelMetadata().acceptanceReasons()))
                        .append('.');
            }
            out.append('\n');
        }
        out.append("\n## Package guide\n\n"
                + "- `vectors/*.vectors.csv`: vector-only datasets.\n"
                + "- `policy-scenario-measurements.csv`: vectors with measurements.\n"
                + "- Top-level and `model/`, `scheduler/`, `checkpoints/`, and `raw-data/` CSV "
                + "files: machine-readable datasets.\n"
                + "- `README.md` and `reports/*.md`: human-readable reports.\n\n"
                + "## Provenance\n\n"
                + "- Producer commit: `").append(inputs.commitSha()).append("`\n"
                + "- Dirty working tree: ").append(inputs.dirtyWorkingTree()).append("\n")
                .append("- Evidence: ").append(originCounts(source)).append("\n")
                .append("- Config SHA-256: `").append(checkpoint.configSha256()).append("`\n")
                .append("- Checkpoint SHA-256: `");
        out.append(source.loaded().snapshotDirectory() == null ? "n/a"
                : CanonicalFileSupport.sha256(source.loaded().snapshotDirectory()));
        out.append("`\n- Raw data: `raw-data/index.csv` and `raw-data/bundles/`\n\n"
                + "## Reproduce this package\n\n"
                + "Run this command from the package directory:\n\n"
                + "```sh\n"
                + "\"$EUHEDRAL_TRAINER\" package-run --workspace ../.. --inputs "
                + "provenance/package-inputs.properties --output-root \"$OUTPUT_ROOT\"\n"
                + "```\n\n"
                + "`EUHEDRAL_TRAINER` names the built launcher or `java -jar` wrapper, and "
                + "`OUTPUT_ROOT` must be writable. The source workspace and exact checkpoint "
                + "must remain available. The recorded package ID and revision reproduce "
                + "byte-identical payload and manifest bytes.\n");
        return out.toString();
    }

    private static String ranking(PackageSourceSet source) throws IOException {
        StringBuilder out = new StringBuilder("# Robust ranking\n\n"
                + "Complete coverage is required. Eligible policies are compared by minimum "
                + "scenario quality, type-7 P25, geometric mean quality, lower cross-scenario "
                + "MAD, then measurement stability.\n\n");
        if (source.merge() == null) return out.append("Ranking is unavailable.\n").toString();
        List<List<String>> rows = CanonicalCsv.read(
                source.merge().resolve("robust-ranking.csv"));
        out.append("## Eligible policies\n\n"
                + "| Rank | Policy ID | Worst | P25 | Geometric mean | MAD | Relative IQR | "
                + "Non-success | Timeout |\n"
                + "| ---: | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |\n");
        for (int index = 1; index < rows.size(); index++) {
            List<String> row = rows.get(index);
            if (row.get(3).equals("true")) {
                out.append("| ").append(cell(row.get(1))).append(" | ")
                        .append(cell(row.get(2))).append(" | ").append(cell(row.get(8)))
                        .append(" | ").append(cell(row.get(9))).append(" | ")
                        .append(cell(row.get(10))).append(" | ").append(cell(row.get(11)))
                        .append(" | ").append(cell(row.get(12))).append(" | ")
                        .append(cell(row.get(13))).append(" | ").append(cell(row.get(14)))
                        .append(" |\n");
            }
        }
        out.append("\n## Incomplete policies\n\n"
                + "| Policy ID | Valid | Observed | Required | Missing scenarios |\n"
                + "| --- | ---: | ---: | ---: | --- |\n");
        for (int index = 1; index < rows.size(); index++) {
            List<String> row = rows.get(index);
            if (!row.get(3).equals("true")) {
                out.append("| ").append(cell(row.get(2))).append(" | ")
                        .append(cell(row.get(6))).append(" | ").append(cell(row.get(5)))
                        .append(" | ").append(cell(row.get(4))).append(" | ")
                        .append(cell(row.get(15))).append(" |\n");
            }
        }
        return out.toString();
    }

    private static String scenarios(PackageSourceSet source) throws IOException {
        StringBuilder out = new StringBuilder("# Source scenario comparison\n");
        if (source.merge() == null) return out.append("\nScenario results are unavailable.\n")
                .toString();
        List<List<String>> rows = CanonicalCsv.read(
                source.merge().resolve("scenario-results.csv"));
        Map<String, List<String>> byIdentity = new HashMap<>();
        for (int index = 1; index < rows.size(); index++) {
            List<String> row = rows.get(index);
            byIdentity.put(row.get(1) + "\0" + row.get(7), row);
        }
        for (var scenario : source.loaded().checkpoint().requiredScenarios()) {
            out.append("\n## ").append(scenario.canonical()).append("\n\n")
                    .append("| Policy ID | Status | P25 | Median | P75 | Quality | "
                            + "Relative IQR | Non-success | Timeout |\n")
                    .append("| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |\n");
            for (String policy : source.winners()) {
                List<String> row = byIdentity.get(scenario.canonical() + "\0" + policy);
                out.append("| ").append(cell(policy));
                if (row == null) out.append(" | n/a | n/a | n/a | n/a | n/a | n/a | n/a | n/a |\n");
                else out.append(" | ").append(cell(row.get(8))).append(" | ")
                        .append(cell(row.get(15))).append(" | ").append(cell(row.get(16)))
                        .append(" | ").append(cell(row.get(17))).append(" | ")
                        .append(cell(row.get(25))).append(" | ").append(cell(row.get(19)))
                        .append(" | ").append(cell(row.get(22))).append(" | ")
                        .append(cell(row.get(20))).append(" |\n");
            }
        }
        return out.toString();
    }

    private static Map<String, Integer> calibrationCounts(Path merge) throws IOException {
        Map<String, Integer> result = new HashMap<>();
        List<List<String>> rows = CanonicalCsv.read(
                merge.resolve("calibration-report.csv"));
        for (int index = 1; index < rows.size(); index++) {
            result.merge(rows.get(index).get(11), 1, Integer::sum);
        }
        return result;
    }

    private static String originCounts(PackageSourceSet source) {
        long nativeCount = source.evidence().stream()
                .filter(item -> item.origin() == EvidenceOrigin.NATIVE).count();
        long imported = source.evidence().size() - nativeCount;
        return "native=" + nativeCount + ", imported=" + imported
                + ", mixed=" + (nativeCount > 0 && imported > 0 ? 1 : 0);
    }

    private static String cell(String value) {
        if (value == null || value.isEmpty()) return "n/a";
        return value.replace("\\", "\\\\").replace("|", "\\|")
                .replace("\r", "\\r").replace("\n", "\\n");
    }

    private PackageReportWriter() {
    }
}
