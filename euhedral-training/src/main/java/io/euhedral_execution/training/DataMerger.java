package io.euhedral_execution.training;

import com.tdunning.math.stats.MergingDigest;
import com.tdunning.math.stats.ScaleFunction;
import com.tdunning.math.stats.TDigest;
import io.euhedral_execution.core.utils.SpinWait;
import io.euhedral_execution.data_structures.queues.PlainQueue;
import io.euhedral_execution.hardware_utils.PinnedThreadExecutor;
import io.euhedral_execution.hardware_utils.SystemInfo;
import io.euhedral_execution.hashing.HasherApi;
import io.euhedral_execution.training.utils.BenchmarkOutputReader;
import io.euhedral_execution.training.utils.BenchmarkOutputWriter;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

@SuppressWarnings("unchecked")
public class DataMerger {

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

        System.out.println("Merging vectors...");
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
            System.out.println("Merged " + added.size() + " vectors.");
        }
        return output;
    }

    /**
     * Backwards-compatible entry point retaining the original misspelling.
     */
    public static Path mergeQuentiles() throws Exception {
        Path input = Path.of(System.getProperty("merger.input", "input/merger"));
        Path outputDirectory = Path.of(System.getProperty("merger.output", "output/merger"));
        return mergeQuantiles(input, outputDirectory,
                "merged-quantiles-" + System.currentTimeMillis() + ".txt");
    }

    public static Path mergeQuantiles(Path inputDirectory, Path outputDirectory,
            String outputName) throws Exception {
        Files.createDirectories(inputDirectory);
        Files.createDirectories(outputDirectory);

        List<Path> paths = listRegularFiles(inputDirectory);
        if (paths.isEmpty()) {
            System.out.println("Place benchmark files under " + inputDirectory.toAbsolutePath());
            throw new Cancel();
        }

        Path tempDirectory = outputDirectory.resolve(".merge-temp");
        deleteRecursively(tempDirectory);
        Files.createDirectories(tempDirectory);

        System.out.println("Starting data merge...");
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

            System.out.println("Normalizing...");
            AtomicInteger countdown = new AtomicInteger(workers.size());
            AtomicReference<Throwable> failure = new AtomicReference<>();
            normalize(normalizeQueue, countdown, failure, tempDirectory);
            SpinWait.awaitWhile(() -> countdown.getOpaque() > 0);

            Throwable error = failure.getAcquire();
            if (error != null) {
                throw new RuntimeException("Failed to normalize benchmark data", error);
            }

            Path output = outputDirectory.resolve(outputName);
            System.out.println("Merging into quantiles (P10, P25, P50, P75, P90)");
            merge(tempDirectory, output);

            Duration elapsed = Duration.ofNanos(System.nanoTime() - now);
            System.out.printf("Complete.%nTime: %s%n", timeFormat(elapsed));
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
            double[] vector;
            while ((vector = reader.readDoubleArray()) != null) {
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

        System.out.printf("Found %d separate vector distributions.%n", merged.size());
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
