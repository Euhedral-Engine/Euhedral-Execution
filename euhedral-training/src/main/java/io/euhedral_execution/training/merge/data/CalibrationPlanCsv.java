package io.euhedral_execution.training.merge.data;

import io.euhedral_execution.training.data.PolicyId;
import io.euhedral_execution.training.data.PolicyVector;
import io.euhedral_execution.training.data.SourceScenario;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.SortedMap;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class CalibrationPlanCsv {
    private static final Logger LOGGER = LoggerFactory.getLogger(CalibrationPlanCsv.class);

    public static void write(Path directory, CalibrationPlan plan) throws IOException {
        Files.createDirectories(directory);
        if (Files.exists(directory.resolve("fixed-anchors.csv"))
                || Files.exists(directory.resolve("reference-runs.csv"))) {
            throw new IllegalArgumentException("Calibration plan already exists");
        }
        StringBuilder anchors = new StringBuilder("schema_version,anchor_set_id,policy_id");
        for (int i = 0; i < PolicyVector.WIDTH; i++) {
            anchors.append(String.format(",weight_%02d_bits", i));
        }
        anchors.append('\n');
        for (PolicyVector policy : plan.anchors().fixedAnchors().stream()
                .sorted(Comparator.comparing(PolicyVector::id))
                .toList()) {
            anchors.append("1,")
                    .append(plan.anchors().anchorSetId())
                    .append(',')
                    .append(policy.id().canonical());
            for (double weight : policy.copyWeights()) {
                anchors.append(',').append(String.format("%016x", Double.doubleToRawLongBits(weight)));
            }
            anchors.append('\n');
        }
        StringBuilder references = new StringBuilder("schema_version,anchor_set_id,scenario_id,benchmark_run_id\n");
        plan.references()
                .referenceRunIds()
                .forEach((scenario, runId) -> references
                        .append("1,")
                        .append(plan.anchors().anchorSetId())
                        .append(',')
                        .append(scenario.canonical())
                        .append(',')
                        .append(runId)
                        .append('\n'));
        Files.writeString(
                directory.resolve("fixed-anchors.csv"),
                anchors,
                StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.CREATE_NEW);
        Files.writeString(
                directory.resolve("reference-runs.csv"),
                references,
                StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.CREATE_NEW);
    }

    public static CalibrationPlan read(Path directory, Collection<SourceScenario> knownScenarios) throws IOException {
        LOGGER.info("Reading calibration plan from {}", directory);
        List<String> anchorLines = strictLines(directory.resolve("fixed-anchors.csv"));
        if (anchorLines.size() < 2) {
            throw new IllegalArgumentException("Empty anchor catalog");
        }
        StringBuilder expectedAnchorHeader = new StringBuilder("schema_version,anchor_set_id,policy_id");
        for (int i = 0; i < PolicyVector.WIDTH; i++) {
            expectedAnchorHeader.append(String.format(",weight_%02d_bits", i));
        }
        if (!anchorLines.getFirst().contentEquals(expectedAnchorHeader)) {
            throw new IllegalArgumentException("Invalid anchor header");
        }
        String anchorSetId = null;
        List<PolicyVector> anchors = new ArrayList<>();
        PolicyId previousPolicyId = null;
        for (int line = 1; line < anchorLines.size(); line++) {
            String[] fields = anchorLines.get(line).split(",", -1);
            if (fields.length != 31 || !"1".equals(fields[0])) {
                throw new IllegalArgumentException();
            }
            if (anchorSetId == null) {
                anchorSetId = fields[1];
            }
            if (!anchorSetId.equals(fields[1])) {
                throw new IllegalArgumentException("Mixed anchor set");
            }
            double[] weights = new double[PolicyVector.WIDTH];
            for (int i = 0; i < weights.length; i++) {
                weights[i] = Double.longBitsToDouble(Long.parseUnsignedLong(fields[i + 3], 16));
            }
            PolicyVector policy = PolicyVector.of(weights);
            if (!policy.id().equals(PolicyId.parse(fields[2]))) {
                throw new IllegalArgumentException();
            }
            if (previousPolicyId != null && previousPolicyId.compareTo(policy.id()) >= 0) {
                throw new IllegalArgumentException("Anchors are not in policy order");
            }
            previousPolicyId = policy.id();
            anchors.add(policy);
        }
        Map<String, SourceScenario> scenarioById = new HashMap<>();
        if (knownScenarios != null) {
            knownScenarios.forEach(item -> scenarioById.put(item.canonical(), item));
        }
        SortedMap<SourceScenario, String> references = new TreeMap<>();
        List<String> referenceLines = strictLines(directory.resolve("reference-runs.csv"));
        if (referenceLines.isEmpty()
                || !referenceLines.getFirst().equals("schema_version,anchor_set_id,scenario_id,benchmark_run_id")) {
            throw new IllegalArgumentException("Invalid reference header");
        }
        SourceScenario previousScenario = null;
        for (int line = 1; line < referenceLines.size(); line++) {
            String[] fields = referenceLines.get(line).split(",", -1);
            if (fields.length != 4 || !"1".equals(fields[0]) || !Objects.equals(anchorSetId, fields[1])) {
                throw new IllegalArgumentException("Invalid reference catalog");
            }
            SourceScenario scenario =
                    knownScenarios == null ? SourceScenario.parse(fields[2]) : scenarioById.get(fields[2]);
            if (scenario == null) {
                scenario = SourceScenario.parse(fields[2]);
            }
            references.put(scenario, fields[3]);
            if (previousScenario != null && previousScenario.compareTo(scenario) >= 0) {
                throw new IllegalArgumentException("References are not in scenario order");
            }
            previousScenario = scenario;
        }
        LOGGER.info("Found {} anchors and {} unique scenarios", anchors.size(), references.size());
        return new CalibrationPlan(
                new AnchorCatalog(1, anchorSetId, anchors), new ReferenceRunCatalog(1, anchorSetId, references));
    }

    public static CalibrationPlan read(Path directory) throws IOException {
        return read(directory, null);
    }

    private static List<String> strictLines(Path path) throws IOException {
        String text = Files.readString(path, StandardCharsets.UTF_8);
        if (text.startsWith("\ufeff") || text.indexOf('\r') >= 0 || !text.endsWith("\n")) {
            throw new IllegalArgumentException("CSV must use UTF-8 and LF line endings");
        }
        return Arrays.asList(text.substring(0, text.length() - 1).split("\n", -1));
    }

    private CalibrationPlanCsv() {}
}
