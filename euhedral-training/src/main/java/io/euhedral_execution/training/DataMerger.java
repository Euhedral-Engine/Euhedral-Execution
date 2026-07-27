package io.euhedral_execution.training;

import com.tdunning.math.stats.MergingDigest;
import com.tdunning.math.stats.ScaleFunction;
import com.tdunning.math.stats.TDigest;
import io.euhedral_execution.core.utils.SpinWait;
import io.euhedral_execution.data_structures.queues.PlainQueue;
import io.euhedral_execution.hardware_utils.PinnedThreadExecutor;
import io.euhedral_execution.hardware_utils.SystemInfo;
import io.euhedral_execution.hashing.HasherApi;
import io.euhedral_execution.training.data.PolicyRegistry;
import io.euhedral_execution.training.data.SourceScenario;
import io.euhedral_execution.training.merge.AggregationConfig;
import io.euhedral_execution.training.merge.AnchorBootstrapper;
import io.euhedral_execution.training.merge.AnchorSelectionConfig;
import io.euhedral_execution.training.merge.CalibrationConfig;
import io.euhedral_execution.training.merge.CalibrationPlan;
import io.euhedral_execution.training.merge.CalibrationPlanCsv;
import io.euhedral_execution.training.merge.HierarchicalAggregator;
import io.euhedral_execution.training.merge.MergeCsvWriter;
import io.euhedral_execution.training.merge.MergeRecords.MergeResult;
import io.euhedral_execution.training.merge.RunAggregator;
import io.euhedral_execution.training.merge.RunCalibrator;
import io.euhedral_execution.training.merge.ScenarioQualityRanker;
import io.euhedral_execution.training.utils.BenchmarkOutputReader;
import io.euhedral_execution.training.utils.BenchmarkOutputWriter;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SortedSet;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@SuppressWarnings("unchecked")
public class DataMerger {

    private static final Logger LOGGER = LoggerFactory.getLogger(DataMerger.class);

    public static Path mergeVectors() throws Exception {
        Path input = Path.of(System.getProperty("merger.input", "input/merger"));
        Path output = Path.of(System.getProperty("merger.vectors.output",
                "output/merger/merged-vectors"));
        return mergeVectors(input, output);
    }

    public static Path mergeVectors(Path inputDirectory, Path output) throws Exception {
        Files.createDirectories(inputDirectory);
        if (output.getParent() != null) {
            Files.createDirectories(output.getParent());
        }

        LOGGER.info("Merging vectors...");
        try (BenchmarkOutputWriter writer = new BenchmarkOutputWriter(output)) {
            Set<Long> added = new HashSet<>(131_072);
            for (Path path : listRegularFiles(inputDirectory)) {
                try (BenchmarkOutputReader reader = new BenchmarkOutputReader(path)) {
                    double[] vector;
                    while ((vector = reader.readDoubleArray()) != null) {
                        if (vector.length != 28) {
                            continue;
                        }
                        long hash = HasherApi.getHash(vector);
                        if (added.add(hash)) {
                            writer.spaceSeparatedWriteLine(vector);
                        }
                    }
                }
            }
            LOGGER.info("Merged {} vectors.", added.size());
        }
        return output;
    }

    /**
     * Backwards-compatible entry point retaining the original misspelling.
     */
    @Deprecated
    // ROBUST_OPTIMIZER_POOLED_V0_REMOVAL
    public static Path mergeQuentiles() throws Exception {
        Path input = Path.of(System.getProperty("merger.input", "input/merger"));
        Path outputDirectory = Path.of(System.getProperty("merger.output", "output/merger"));
        return mergeQuantiles(input, outputDirectory,
                "merged-quantiles-" + System.currentTimeMillis() + ".txt");
    }

    @Deprecated
    // ROBUST_OPTIMIZER_POOLED_V0_REMOVAL
    public static Path mergeQuantiles(Path inputDirectory, Path outputDirectory,
            String outputName) throws Exception {
        Files.createDirectories(inputDirectory);
        Files.createDirectories(outputDirectory);

        List<Path> paths = listRegularFiles(inputDirectory);
        if (paths.isEmpty()) {
            LOGGER.info("Place benchmark files under {}", inputDirectory.toAbsolutePath());
            throw new Cancel();
        }

        Path tempDirectory = outputDirectory.resolve(".merge-temp");
        deleteRecursively(tempDirectory);
        Files.createDirectories(tempDirectory);

        LOGGER.info("Starting data merge...");
        long now = System.nanoTime();
        try {
            Set<Integer> workers = new HashSet<>();
            PlainQueue<File>[] normalizeQueue =
                    new PlainQueue[SystemInfo.getMaxCoreId() + 1];
            int index = 0;
            for (Path path : paths) {
                while (SystemInfo.getCoreInfo(index) == null) {
                    index = (index + 1) % normalizeQueue.length;
                }
                if (normalizeQueue[index] == null) {
                    normalizeQueue[index] = new PlainQueue<>(16);
                }

                normalizeQueue[index].add(path.toFile());
                workers.add(index);
                index = (index + 1) % normalizeQueue.length;
            }

            LOGGER.info("Normalizing...");
            AtomicInteger countdown = new AtomicInteger(workers.size());
            AtomicReference<Throwable> failure = new AtomicReference<>();
            normalize(normalizeQueue, countdown, failure, tempDirectory);
            SpinWait.awaitWhile(() -> countdown.getOpaque() > 0);

            Throwable error = failure.getAcquire();
            if (error != null) {
                throw new RuntimeException("Failed to normalize benchmark data", error);
            }

            Path output = outputDirectory.resolve(outputName);
            LOGGER.info("Merging into quantiles (P10, P25, P50, P75, P90)");
            merge(tempDirectory, output);

            Duration elapsed = Duration.ofNanos(System.nanoTime() - now);
            LOGGER.info("Complete.\nTime: {}", timeFormat(elapsed));
            return output;
        } finally {
            deleteRecursively(tempDirectory);
        }
    }

    private static List<Path> listRegularFiles(Path directory) throws Exception {
        try (Stream<Path> stream = Files.list(directory)) {
            return stream.filter(Files::isRegularFile)
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
        }
    }

    private static void normalize(PlainQueue<File>[] normalizeQueue, AtomicInteger countdown,
            AtomicReference<Throwable> failure, Path tempDirectory) {
        int index = 0;
        for (PlainQueue<File> queue : normalizeQueue) {
            if (queue == null || queue.isEmpty()) {
                index++;
                continue;
            }
            int core = index++;
            PinnedThreadExecutor executor = PinnedThreadExecutor.getOrSetIfAbsent(core,
                    "DataMerger-" + core, Thread.NORM_PRIORITY, true);

            executor.execute(() -> {
                try {
                    normalize(queue, tempDirectory);
                } catch (Throwable e) {
                    failure.compareAndSet(null, e);
                } finally {
                    countdown.decrementAndGet();
                }
            });
        }
    }

    private static void normalize(PlainQueue<File> normalizeQueue, Path tempDirectory)
            throws Exception {
        while (!normalizeQueue.isEmpty()) {
            File file = normalizeQueue.poll();
            Objects.requireNonNull(file);

            TDigest meanDigest = mergeMeans(file);
            double maxMean = meanDigest.quantile(0.99);
            if (!Double.isFinite(maxMean) || maxMean <= 0) {
                maxMean = 1.0;
            }

            try (BenchmarkOutputReader reader = new BenchmarkOutputReader(file.toPath());
                    BenchmarkOutputWriter writer = new BenchmarkOutputWriter(
                            tempDirectory.resolve("normalized-" + file.getName()))) {
                double[] vector;
                while ((vector = reader.readDoubleArray()) != null) {
                    double[] means = reader.readDoubleArray();
                    if (means == null) {
                        throw new IllegalStateException(
                                "Missing benchmark measurements after vector in " + file);
                    }
                    if (vector.length != 28) {
                        throw new IllegalStateException(
                                "Expected 28 policy weights in " + file + " but found "
                                        + vector.length);
                    }

                    writer.spaceSeparatedWriteLine(vector);
                    for (int i = 0; i < means.length; i++) {
                        means[i] = Math.min(Math.max(means[i] / maxMean, 0.0), 1.0);
                    }
                    writer.spaceSeparatedWriteLine(means);
                }
                writer.force();
            }
        }
    }

    private static TDigest mergeMeans(File file) throws Exception {
        TDigest digest = new MergingDigest(1024);
        try (BenchmarkOutputReader reader = new BenchmarkOutputReader(file.toPath())) {
            // Input files are formated as
            // vectorA
            // meansA
            // vectorB
            // meansB
            while (reader.readDoubleArray() != null) {
                double[] means = reader.readDoubleArray();
                if (means == null) {
                    throw new IllegalStateException(
                            "Missing benchmark measurements after vector in " + file);
                }
                for (double mean : means) {
                    if (Double.isFinite(mean)) {
                        digest.add(mean);
                    }
                }
            }
        }
        return digest;
    }

    private static void merge(Path tempDirectory, Path output) throws Exception {
        Map<Long, MergedResult> merged = new LinkedHashMap<>(131_072);
        for (Path path : listRegularFiles(tempDirectory)) {
            try (BenchmarkOutputReader reader = new BenchmarkOutputReader(path)) {
                while (true) {
                    double[] vector = reader.readDoubleArray();
                    if (vector == null) {
                        break;
                    }
                    double[] means = reader.readDoubleArray();
                    if (means == null) {
                        throw new IllegalStateException(
                                "Missing normalized measurements after vector in " + path);
                    }

                    long hash = HasherApi.getHash(vector);
                    MergedResult result = merged.computeIfAbsent(hash, key -> {
                        TDigest digest = new MergingDigest(100);
                        digest.setScaleFunction(ScaleFunction.K_1);
                        return new MergedResult(vector, digest);
                    });

                    for (double mean : means) {
                        result.digest.add(mean);
                    }
                }
            }
        }

        LOGGER.info("Found {} separate vector distributions.", merged.size());
        try (BenchmarkOutputWriter writer = new BenchmarkOutputWriter(output)) {
            for (MergedResult result : merged.values()) {
                writer.spaceSeparatedWriteLine(result.vector);
                writer.writeLine(result.quantiles());
            }
        }
    }

    private static void deleteRecursively(Path root) throws Exception {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> stream = Files.walk(root)) {
            List<Path> paths = new ArrayList<>(stream.sorted(Comparator.reverseOrder()).toList());
            for (Path path : paths) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static String timeFormat(Duration elapsed) {
        String hours = elapsed.toHours() + "";
        String minutes = (elapsed.toMinutes() % 60) + "";
        String seconds = (elapsed.toSeconds() % 60) + "";
        String millis = (elapsed.toMillis() % 1000) + "";
        while (minutes.length() < 2) {
            minutes = "0" + minutes;
        }
        while (seconds.length() < 2) {
            seconds = "0" + seconds;
        }
        while (millis.length() < 3) {
            millis = "0" + millis;
        }

        return String.format("%s:%s:%s:%s", hours, minutes, seconds, millis);
    }

    public static CalibrationPlan bootstrapCalibrationV1(
            CalibrationBootstrapRequest request) throws Exception {
        Objects.requireNonNull(request);
        Path target = request.planDirectory().toAbsolutePath().normalize();
        Path temporary = temporarySibling(target);
        ensureNewTarget(target, temporary);
        try {
            PolicyRegistry policies = new PolicyRegistry();
            var runs = RunAggregator.aggregate(request.observationBundles(), policies,
                    request.aggregation());
            CalibrationPlan plan = AnchorBootstrapper.bootstrap(runs, request.requiredScenarios(),
                    request.policyBudget(), request.referenceOverrides(),
                    request.anchorSelection(), request.aggregation());
            CalibrationPlanCsv.write(temporary, plan);
            CalibrationPlan readBack = CalibrationPlanCsv.read(temporary,
                    request.requiredScenarios());
            if (!readBack.anchors().anchorSetId().equals(plan.anchors().anchorSetId())
                    || !readBack.references().equals(plan.references())) {
                throw new IllegalStateException("Calibration plan validation failed");
            }
            publish(temporary, target);
            return plan;
        } catch (Throwable error) {
            deleteRecursively(temporary);
            throw error;
        }
    }

    public static MergeArtifacts mergeV1(MergeRequest request) throws Exception {
        Objects.requireNonNull(request);
        Path target = request.outputDirectory().toAbsolutePath().normalize();
        Path temporary = temporarySibling(target);
        ensureNewTarget(target, temporary);
        try {
            PolicyRegistry policies = new PolicyRegistry();
            var runs = RunAggregator.aggregate(request.observationBundles(), policies,
                    request.aggregation());
            var calibrations = RunCalibrator.calibrate(runs, request.calibrationPlan(),
                    request.calibration());
            var scenarios = HierarchicalAggregator.aggregateScenarios(
                    policies.policiesInIdOrder(), runs, calibrations,
                    request.requiredScenarios(), request.aggregation());
            scenarios = ScenarioQualityRanker.assignQualities(scenarios);
            var summaries = ScenarioQualityRanker.summarize(policies.policiesInIdOrder(),
                    scenarios, request.requiredScenarios());
            MergeResult result = new MergeResult(request.calibrationPlan(), calibrations,
                    scenarios, summaries);
            MergeCsvWriter.write(temporary, result,
                    request.aggregation().calibrationAcceptance());
            validateMergeOutput(temporary, request.requiredScenarios());
            publish(temporary, target);
            return artifacts(target);
        } catch (Throwable error) {
            deleteRecursively(temporary);
            throw error;
        }
    }

    private static Path temporarySibling(Path target) {
        Path parent = target.getParent();
        if (parent == null) throw new IllegalArgumentException("Output requires a parent");
        return parent.resolve("." + target.getFileName() + ".tmp-" + UUID.randomUUID());
    }

    private static void ensureNewTarget(Path target, Path temporary) throws Exception {
        if (Files.exists(target)) {
            throw new IllegalArgumentException("Output already exists: " + target);
        }
        Files.createDirectories(target.getParent());
        Files.createDirectory(temporary);
    }

    private static void publish(Path temporary, Path target) throws Exception {
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, target);
        }
    }

    private static void validateMergeOutput(Path directory,
            SortedSet<SourceScenario> requiredScenarios) throws Exception {
        CalibrationPlanCsv.read(directory, requiredScenarios);
        List<String> files = List.of("fixed-anchors.csv", "reference-runs.csv",
                "calibration-report.csv", "scenario-results.csv", "robust-ranking.csv",
                "coverage-report.csv", "robust-leaders.vectors.csv",
                "incomplete-policies.vectors.csv");
        for (String file : files) {
            String text = Files.readString(directory.resolve(file));
            if (!text.startsWith("schema_version,") || !text.endsWith("\n")) {
                throw new IllegalStateException("Invalid merge artifact " + file);
            }
        }
    }

    private static MergeArtifacts artifacts(Path directory) {
        return new MergeArtifacts(directory.resolve("fixed-anchors.csv"),
                directory.resolve("reference-runs.csv"),
                directory.resolve("calibration-report.csv"),
                directory.resolve("scenario-results.csv"),
                directory.resolve("robust-ranking.csv"),
                directory.resolve("coverage-report.csv"),
                directory.resolve("robust-leaders.vectors.csv"),
                directory.resolve("incomplete-policies.vectors.csv"));
    }

    public record CalibrationBootstrapRequest(
            List<Path> observationBundles,
            SortedSet<SourceScenario> requiredScenarios,
            int policyBudget,
            Map<SourceScenario, String> referenceOverrides,
            Path planDirectory,
            AnchorSelectionConfig anchorSelection,
            AggregationConfig aggregation) {
        public CalibrationBootstrapRequest {
            observationBundles = List.copyOf(observationBundles);
            requiredScenarios = java.util.Collections.unmodifiableSortedSet(
                    new java.util.TreeSet<>(requiredScenarios));
            referenceOverrides = Map.copyOf(referenceOverrides);
            Objects.requireNonNull(planDirectory);
            Objects.requireNonNull(anchorSelection);
            Objects.requireNonNull(aggregation);
        }
    }

    public record MergeRequest(
            List<Path> observationBundles,
            SortedSet<SourceScenario> requiredScenarios,
            CalibrationPlan calibrationPlan,
            Path outputDirectory,
            CalibrationConfig calibration,
            AggregationConfig aggregation) {
        public MergeRequest {
            observationBundles = List.copyOf(observationBundles);
            requiredScenarios = java.util.Collections.unmodifiableSortedSet(
                    new java.util.TreeSet<>(requiredScenarios));
            Objects.requireNonNull(calibrationPlan);
            Objects.requireNonNull(outputDirectory);
            Objects.requireNonNull(calibration);
            Objects.requireNonNull(aggregation);
        }
    }

    public record MergeArtifacts(
            Path fixedAnchors,
            Path referenceRuns,
            Path calibrationReport,
            Path scenarioResults,
            Path robustRanking,
            Path coverageReport,
            Path robustLeaderVectors,
            Path incompleteVectors) {
    }

    private record MergedResult(double[] vector, TDigest digest) {

        String quantiles() {
            double p10 = digest.quantile(0.1);
            double p25 = digest.quantile(0.25);
            double p50 = digest.quantile(0.5);
            double p75 = digest.quantile(0.75);
            double p90 = digest.quantile(0.9);

            return String.format("%d %d %d %d %d", Double.doubleToLongBits(p10),
                    Double.doubleToLongBits(p25), Double.doubleToLongBits(p50),
                    Double.doubleToLongBits(p75), Double.doubleToLongBits(p90));
        }
    }

    private DataMerger() {

    }

    public static final class Cancel extends RuntimeException {

        public Cancel() {
            super(null, null, false, false);
        }
    }
}
