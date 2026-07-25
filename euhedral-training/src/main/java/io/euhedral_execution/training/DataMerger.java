package io.euhedral_execution.training;

import com.tdunning.math.stats.MergingDigest;
import com.tdunning.math.stats.ScaleFunction;
import com.tdunning.math.stats.TDigest;
import io.euhedral_execution.core.utils.SpinWait;
import io.euhedral_execution.data_structures.queues.PlainQueue;
import io.euhedral_execution.hardware_utils.PinnedThreadExecutor;
import io.euhedral_execution.hardware_utils.SystemInfo;
import io.euhedral_execution.training.utils.BenchmarkOutputReader;
import io.euhedral_execution.training.utils.BenchmarkOutputWriter;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

@SuppressWarnings("unchecked")
public class DataMerger {

    public static void run() throws Exception {
        File input = new File("input/merger");
        Files.createDirectories(Path.of("output/merger/temp"));
        Files.createDirectories(input.toPath());

        Files.list(Path.of("output/merger/temp")).forEach(p -> p.toFile().delete());
        File[] files = input.listFiles();

        if (files == null || files.length == 0) {
            System.out.println("Place files under input/merger at the sources root");
            throw new Cancel();
        }

        System.out.println("Starting Data Merge...");
        long now = System.nanoTime();

        Set<Integer> workers = new HashSet<>();
        PlainQueue<File>[] normalizeQueue = new PlainQueue[SystemInfo.getMaxCoreId() + 1];
        int index = 0;
        for (var file : files) {
            while (SystemInfo.getCoreInfo(index) == null) {
                index = (index + 1) % normalizeQueue.length;
            }
            if (normalizeQueue[index] == null) {
                normalizeQueue[index] = new PlainQueue<>(16);
            }

            normalizeQueue[index].add(file);
            workers.add(index);
            index = (index + 1) % normalizeQueue.length;
        }

        System.out.println("Normalizing...");
        AtomicInteger countdown = new AtomicInteger(workers.size());
        normalize(normalizeQueue, countdown);

        SpinWait.awaitWhile(() -> countdown.getOpaque() > 0);

        System.out.println("Merging Into Quantiles (P10, P25, P50, P75, P90)");
        merge();

        Files.list(Path.of("output/merger/temp")).forEach(p -> p.toFile().delete());
        Files.deleteIfExists(Path.of("output/merger/temp"));
        Duration elapsed = Duration.ofNanos(System.nanoTime() - now);

        System.out.printf("Complete.\nTime: %s%n", timeFormat(elapsed));
    }

    private static void normalize(PlainQueue<File>[] normalizeQueue, AtomicInteger countdown) {
        int index = 0;
        for (PlainQueue<File> queue : normalizeQueue) {
            if (queue == null || queue.isEmpty()) {
                index++;
                continue;
            }
            PinnedThreadExecutor executor = PinnedThreadExecutor.getOrSetIfAbsent(index++,
                    "DataMerger-" + index, Thread.NORM_PRIORITY, true);

            executor.execute(() -> {
                try {
                    normalize(queue);
                } catch (Exception e) {
                    System.err.println(e);
                    throw new RuntimeException(e);
                } finally {
                    countdown.decrementAndGet();
                }
            });
        }
    }

    private static void normalize(PlainQueue<File> normalizeQueue) throws Exception {
        while (!normalizeQueue.isEmpty()) {
            File file = normalizeQueue.poll();
            Objects.requireNonNull(file);

            TDigest meanMean = mergeMeans(file);
            double maxMean = meanMean.quantile(0.99);

            try (BenchmarkOutputReader reader = new BenchmarkOutputReader(file.toPath())) {
                try (BenchmarkOutputWriter writer = new BenchmarkOutputWriter(
                        Path.of("output/merger/temp/", "temp-" + file.getName()))) {
                    double[] arr;
                    while ((arr = reader.readDoubleArray()) != null) {
                        writer.spaceSeparatedWriteLine(arr);
                        arr = reader.readDoubleArray();

                        for (int i = 0; i < arr.length; i++) {
                            arr[i] /= maxMean;
                            arr[i] = Math.min(arr[i], 1.0);
                        }
                        writer.spaceSeparatedWriteLine(arr);
                    }
                    writer.force();
                }
            }
        }
    }

    private static TDigest mergeMeans(File file) throws Exception {
        TDigest digest = new MergingDigest(1024);
        try (BenchmarkOutputReader reader = new BenchmarkOutputReader(file.toPath())) {
            double[] arr;
            while ((arr = reader.readDoubleArray()) != null) {
                arr = reader.readDoubleArray();

                for (double mean : arr) {
                    digest.add(mean);
                }
            }
        }
        return digest;
    }

    private static void merge() throws Exception {
        Map<Integer, MergedResult> merged = new LinkedHashMap<>(32_768);
        Files.list(Path.of("output/merger/temp")).forEach(path -> {
            try (BenchmarkOutputReader reader = new BenchmarkOutputReader(path)) {
                while (true) {
                    double[] vector = reader.readDoubleArray();
                    if (vector == null) {
                        break;
                    }
                    double[] means = reader.readDoubleArray();

                    int hash = Arrays.hashCode(vector);
                    MergedResult result = merged.computeIfAbsent(hash, (key) -> {
                        TDigest digest = new MergingDigest(100);
                        digest.setScaleFunction(ScaleFunction.K_1);

                        return new MergedResult(vector, digest);
                    });

                    for (double m : means) {
                        result.digest.add(m);
                    }
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        System.out.printf("Found %d separate vector distributions.%n", merged.size());
        try (BenchmarkOutputWriter writer = new BenchmarkOutputWriter(
                Path.of("output/merger/merged-quantiles-" + System.currentTimeMillis() + ".txt"))) {
            for(MergedResult result : merged.values()) {
                writer.spaceSeparatedWriteLine(result.vector);
                writer.writeLine(result.quantiles());
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

        @Override
        public int hashCode() {
            return Arrays.hashCode(vector);
        }
    }

    public DataMerger() {

    }

    public static final class Cancel extends RuntimeException {

        public Cancel() {
            super(null, null, false, false);
        }
    }
}
