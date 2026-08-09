package io.euhedral_execution.training.merge.data;

import io.euhedral_execution.hardware_utils.SystemInfo;
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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class CalibrationPlanCsv {

    private static final Logger LOGGER = LoggerFactory.getLogger(CalibrationPlanCsv.class);

    /// Minimum number of anchor data rows before parallel chunk parsing is worthwhile.
    private static final int PARALLEL_ANCHOR_THRESHOLD = 64;

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
        int cpuCount = SystemInfo.getCpuCount();
        try (ExecutorService executor = Executors.newFixedThreadPool(Math.max(1, cpuCount))) {
            Future<List<String>> anchorFuture =
                    executor.submit(() -> strictLines(directory.resolve("fixed-anchors.csv")));
            Future<List<String>> referenceFuture =
                    executor.submit(() -> strictLines(directory.resolve("reference-runs.csv")));

            List<String> anchorLines;
            List<String> referenceLines;
            try {
                anchorLines = anchorFuture.get();
                referenceLines = referenceFuture.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while reading calibration plan", e);
            } catch (ExecutionException e) {
                throw unwrapIOException(e);
            }

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

            String[] firstAnchorFields = anchorLines.get(1).split(",", -1);
            if (firstAnchorFields.length != 31 || !"1".equals(firstAnchorFields[0])) {
                throw new IllegalArgumentException();
            }
            String anchorSetId = firstAnchorFields[1];

            int dataRowCount = anchorLines.size() - 1;
            List<PolicyVector> anchors;
            if (dataRowCount >= PARALLEL_ANCHOR_THRESHOLD && cpuCount > 1) {
                anchors = parseAnchorsParallel(anchorLines, anchorSetId, cpuCount, executor);
            } else {
                anchors = parseAnchorsSerial(anchorLines, anchorSetId);
            }

            Map<String, SourceScenario> scenarioById = new HashMap<>();
            if (knownScenarios != null) {
                knownScenarios.forEach(item -> scenarioById.put(item.canonical(), item));
            }
            SortedMap<SourceScenario, String> references =
                    parseReferences(referenceLines, anchorSetId, knownScenarios, scenarioById);

            LOGGER.info("Found {} anchors and {} unique scenarios", anchors.size(), references.size());
            return new CalibrationPlan(
                    new AnchorCatalog(1, anchorSetId, anchors), new ReferenceRunCatalog(1, anchorSetId, references));
        }
    }

    public static CalibrationPlan read(Path directory) throws IOException {
        return read(directory, null);
    }

    /// Parsed result for one anchor row, carrying both the vector and the raw fields for
    /// post-validation of policy ID ordering and anchor-set consistency.
    private record ParsedAnchor(PolicyVector policy, String rawPolicyId, String rawAnchorSetId) {}

    /// Parses anchor data rows in parallel chunks across available pinned CPUs. Each chunk produces
    /// a list of ParsedAnchor. After all chunks complete, results are merged and validated for
    /// ordering and anchor-set consistency.
    private static List<PolicyVector> parseAnchorsParallel(
            List<String> anchorLines, String anchorSetId, int cpuCount, ExecutorService executor) throws IOException {
        int dataRowCount = anchorLines.size() - 1;
        int workerCount = Math.min(cpuCount, dataRowCount);
        int chunkSize = (dataRowCount + workerCount - 1) / workerCount;

        @SuppressWarnings("unchecked")
        Future<List<ParsedAnchor>>[] futures = new Future[workerCount];
        for (int w = 0; w < workerCount; w++) {
            int startRow = 1 + w * chunkSize; // 1-based into anchorLines
            int endRow = Math.min(startRow + chunkSize, anchorLines.size()); // exclusive
            if (startRow >= anchorLines.size()) {
                break;
            }
            int capturedStart = startRow;
            int capturedEnd = endRow;
            futures[w] = executor.submit(() -> {
                List<ParsedAnchor> chunk = new ArrayList<>(capturedEnd - capturedStart);
                for (int line = capturedStart; line < capturedEnd; line++) {
                    chunk.add(parseOneAnchorRow(anchorLines.get(line)));
                }
                return chunk;
            });
        }

        // Collect results in order and validate.
        List<PolicyVector> anchors = new ArrayList<>(dataRowCount);
        PolicyId previousPolicyId = null;
        try {
            for (Future<List<ParsedAnchor>> future : futures) {
                if (future == null) {
                    break;
                }
                for (ParsedAnchor parsed : future.get()) {
                    if (!anchorSetId.equals(parsed.rawAnchorSetId())) {
                        throw new IllegalArgumentException("Mixed anchor set");
                    }
                    if (!parsed.policy().id().equals(PolicyId.parse(parsed.rawPolicyId()))) {
                        throw new IllegalArgumentException();
                    }
                    if (previousPolicyId != null
                            && previousPolicyId.compareTo(parsed.policy().id()) >= 0) {
                        throw new IllegalArgumentException("Anchors are not in policy order");
                    }
                    previousPolicyId = parsed.policy().id();
                    anchors.add(parsed.policy());
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while parsing anchors", e);
        } catch (ExecutionException e) {
            throw unwrapIOException(e);
        }
        return anchors;
    }

    /// Serial anchor parsing fallback for small files or single-CPU systems.
    private static List<PolicyVector> parseAnchorsSerial(List<String> anchorLines, String anchorSetId) {
        List<PolicyVector> anchors = new ArrayList<>();
        PolicyId previousPolicyId = null;
        for (int line = 1; line < anchorLines.size(); line++) {
            String[] fields = anchorLines.get(line).split(",", -1);
            if (fields.length != 31 || !"1".equals(fields[0])) {
                throw new IllegalArgumentException();
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
        return anchors;
    }

    /// Parses one anchor CSV row into a ParsedAnchor carrying the constructed PolicyVector and raw
    /// field values for deferred validation. This is the per-row hot path: hex decode of 28 weight
    /// fields followed by xxHash64 inside PolicyVector.of().
    private static ParsedAnchor parseOneAnchorRow(String row) {
        String[] fields = row.split(",", -1);
        if (fields.length != 31 || !"1".equals(fields[0])) {
            throw new IllegalArgumentException();
        }
        double[] weights = new double[PolicyVector.WIDTH];
        for (int i = 0; i < weights.length; i++) {
            weights[i] = Double.longBitsToDouble(Long.parseUnsignedLong(fields[i + 3], 16));
        }
        return new ParsedAnchor(PolicyVector.of(weights), fields[2], fields[1]);
    }

    // -- Reference parsing ------------------------------------------------------------------------

    /// Parses reference-runs.csv lines. References are lightweight (no hashing), so this runs
    /// serially on the calling thread.
    private static SortedMap<SourceScenario, String> parseReferences(
            List<String> referenceLines,
            String anchorSetId,
            Collection<SourceScenario> knownScenarios,
            Map<String, SourceScenario> scenarioById) {
        if (referenceLines.isEmpty()
                || !referenceLines.getFirst().equals("schema_version,anchor_set_id,scenario_id,benchmark_run_id")) {
            throw new IllegalArgumentException("Invalid reference header");
        }
        SortedMap<SourceScenario, String> references = new TreeMap<>();
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
        return references;
    }

    /// Unwraps an ExecutionException into an IOException, IllegalArgumentException, or wraps the
    /// cause in an IOException for checked propagation.
    private static IOException unwrapIOException(ExecutionException e) throws IOException {
        Throwable cause = e.getCause();
        if (cause instanceof IOException io) {
            throw io;
        }
        if (cause instanceof IllegalArgumentException iae) {
            throw iae;
        }
        if (cause instanceof Error error) {
            throw error;
        }
        throw new IOException("Failed during calibration plan read", cause);
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
