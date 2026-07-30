package io.euhedral_execution.training;

import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs the active-learning feedback loop:
 *
 * <pre>
 * benchmark corpus -> normalize/merge -> train -> screen/generate -> benchmark -> corpus
 * </pre>
 *
 * Each iteration is committed to the corpus only after benchmarking finishes successfully. A
 * partially completed iteration is therefore safe to delete or resume without changing the next
 * training set.
 */
public final class ClosedLoopRunner {

    private static final Logger LOGGER = LoggerFactory.getLogger(ClosedLoopRunner.class);

    public static void run() throws Exception {
        Config config = Config.fromSystemProperties();
        Files.createDirectories(config.workspace());

        Path corpus = config.workspace().resolve("corpus");
        bootstrapCorpus(config.seedDirectory(), corpus);

        Path previousModel = config.initialModel();
        for (int iteration = 1; iteration <= config.iterations(); iteration++) {
            if (Files.exists(config.stopFile())) {
                LOGGER.info("Closed-loop stop file detected at {}", config.stopFile());
                break;
            }

            Path iterationDirectory = config.workspace().resolve(
                    String.format("iteration-%04d", iteration));
            Path complete = iterationDirectory.resolve("COMPLETE");
            Path model = iterationDirectory.resolve("model/best");
            Path rawBenchmarkDirectory = iterationDirectory.resolve("benchmark/raw");
            Path benchmarkResultsDirectory = iterationDirectory.resolve("benchmark/results");
            List<Path> corpusFiles = iterationCorpusFiles(corpus, iteration);

            if (config.resume() && Files.isRegularFile(complete) && Files.isDirectory(model)) {
                if (corpusFiles.isEmpty()) {
                    if (listRegularFiles(rawBenchmarkDirectory).isEmpty()) {
                        throw new IllegalStateException(
                                "Completed iteration is missing both corpus and raw benchmark metadata: "
                                        + iterationDirectory);
                    }
                    promoteBenchmarks(rawBenchmarkDirectory, corpus, iteration);
                }
                publishLatest(config.workspace(), model,
                        iterationDirectory.resolve("merge/training-metadata.txt"));
                LOGGER.info("Iteration {} is already complete; resuming from its model", iteration);
                previousModel = model;
                continue;
            }

            deleteRecursively(iterationDirectory);
            Files.createDirectories(iterationDirectory);
            writeState(iterationDirectory, iteration, "MERGING", null);

            Path mergedData = DataMerger.mergeQuantiles(corpus,
                    iterationDirectory.resolve("merge"), "training-metadata.txt");
            checkStop(config);

            writeState(iterationDirectory, iteration, "TRAINING", mergedData);
            model = SequenceFinder.train(mergedData, previousModel,
                    iterationDirectory.resolve("model/best"));
            checkStop(config);

            writeState(iterationDirectory, iteration, "GENERATING", model);
            int sobolSkip = sobolSkip(iteration, config.candidateCount());
            Path candidates = SequenceFinder.generateCandidates(mergedData, model,
                    iterationDirectory.resolve("candidates/vectors.txt"),
                    config.candidateCount(), sobolSkip);
            checkStop(config);

            writeState(iterationDirectory, iteration, "BENCHMARKING", candidates);
            List<BenchmarkRunner.BenchmarkRun> benchmarkRuns =
                    BenchmarkRunner.runAcrossSourceCounts(candidates, rawBenchmarkDirectory,
                            benchmarkResultsDirectory, iteration);
            checkStop(config);

            publishLatest(config.workspace(), model, mergedData);
            writeState(iterationDirectory, iteration, "COMPLETE", rawBenchmarkDirectory);
            Files.writeString(complete, Instant.now().toString(), StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING);

            // The atomic move is the corpus commit point. If it fails after COMPLETE is written,
            // resume repairs the promotion from the retained raw benchmark.
            promoteBenchmarks(rawBenchmarkDirectory, corpus, iteration);

            previousModel = model;
            LOGGER.info("Closed-loop iteration {} complete; promoted {} source configurations into "
                            + "the corpus", iteration, benchmarkRuns.size());
        }
    }

    private static int sobolSkip(int iteration, int candidateCount) {
        long base = Long.getLong("candidate.sobolSkip", 131_072L);
        long screenLimit = Long.getLong("candidate.screenLimit", 1L << 21);
        long stride = Long.getLong("candidate.sobolStride", screenLimit + candidateCount);
        if (base < 0 || stride <= 0) {
            throw new IllegalArgumentException(
                    "candidate.sobolSkip must be non-negative and candidate.sobolStride positive");
        }

        long skip = base + (iteration - 1L) * stride;
        return Math.toIntExact(skip);
    }

    private static void publishLatest(Path workspace, Path model, Path mergedData) throws Exception {
        Path latestModel = workspace.resolve("latest-model");
        deleteRecursively(latestModel);
        copyRecursively(model, latestModel);
        if (Files.isRegularFile(mergedData)) {
            Files.copy(mergedData, workspace.resolve("latest-training-metadata.txt"),
                    StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void copyRecursively(Path source, Path destination) throws Exception {
        try (Stream<Path> stream = Files.walk(source)) {
            for (Path path : stream.sorted(Comparator.comparingInt(Path::getNameCount)).toList()) {
                Path target = destination.resolve(source.relativize(path));
                if (Files.isDirectory(path)) {
                    Files.createDirectories(target);
                } else {
                    Files.copy(path, target, StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.COPY_ATTRIBUTES);
                }
            }
        }
    }

    private static List<Path> iterationCorpusFiles(Path corpus, int iteration) throws Exception {
        String prefix = String.format("iteration-%04d-source-", iteration);
        try (Stream<Path> stream = Files.list(corpus)) {
            return stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().startsWith(prefix))
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
        }
    }

    private static void promoteBenchmarks(Path rawDirectory, Path corpus, int iteration)
            throws Exception {
        for (Path raw : listRegularFiles(rawDirectory)) {
            String name = raw.getFileName().toString();
            Path destination = corpus.resolve(String.format("iteration-%04d-%s", iteration, name));
            promote(raw, destination);
        }
    }

    private static void promote(Path rawBenchmark, Path corpusFile) throws Exception {
        Path pending = corpusFile.resolveSibling(corpusFile.getFileName() + ".pending");
        Files.copy(rawBenchmark, pending, StandardCopyOption.REPLACE_EXISTING);
        try {
            Files.move(pending, corpusFile, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(pending, corpusFile, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void bootstrapCorpus(Path seedDirectory, Path corpus) throws Exception {
        Files.createDirectories(seedDirectory);
        Files.createDirectories(corpus);
        if (!listRegularFiles(corpus).isEmpty()) {
            return;
        }

        List<Path> seeds = listRegularFiles(seedDirectory);
        if (seeds.isEmpty()) {
            throw new IllegalArgumentException(
                    "No seed benchmark metadata found under " + seedDirectory.toAbsolutePath());
        }

        for (int index = 0; index < seeds.size(); index++) {
            Path seed = seeds.get(index);
            Path destination = corpus.resolve(String.format("seed-%04d-%s", index,
                    seed.getFileName()));
            Files.copy(seed, destination, StandardCopyOption.REPLACE_EXISTING);
        }
        LOGGER.info("Bootstrapped closed-loop corpus with {} benchmark files", seeds.size());
    }

    private static void checkStop(Config config) {
        if (Files.exists(config.stopFile())) {
            throw new StopRequested(config.stopFile());
        }
    }

    private static void writeState(Path iterationDirectory, int iteration, String stage,
            Path artifact) throws Exception {
        Properties state = new Properties();
        state.setProperty("iteration", Integer.toString(iteration));
        state.setProperty("stage", stage);
        state.setProperty("updated", Instant.now().toString());
        if (artifact != null) {
            state.setProperty("artifact", artifact.toAbsolutePath().toString());
        }

        Path statePath = iterationDirectory.resolve("state.properties");
        try (OutputStream output = Files.newOutputStream(statePath, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
            state.store(output, "Euhedral closed-loop state");
        }
    }

    private static List<Path> listRegularFiles(Path directory) throws Exception {
        try (Stream<Path> stream = Files.list(directory)) {
            return stream.filter(Files::isRegularFile)
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
        }
    }

    private static void deleteRecursively(Path root) throws Exception {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> stream = Files.walk(root)) {
            for (Path path : stream.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private record Config(Path seedDirectory, Path workspace, Path initialModel, Path stopFile,
                          int iterations, int candidateCount, boolean resume) {

        static Config fromSystemProperties() {
            Path seedDirectory = Path.of(System.getProperty("cycle.seed", "input/merger"));
            Path workspace = Path.of(System.getProperty("cycle.workspace", "output/closed-loop"));
            Path initialModel = optionalPath("cycle.model");
            Path stopFile = Path.of(System.getProperty("cycle.stopFile",
                    workspace.resolve("STOP").toString()));
            int iterations = Integer.getInteger("cycle.iterations", 1);
            int candidates = Integer.getInteger("cycle.candidates", 32_768);
            boolean resume = Boolean.parseBoolean(System.getProperty("cycle.resume", "true"));

            if (iterations <= 0) {
                throw new IllegalArgumentException("cycle.iterations must be positive");
            }
            if (candidates < 3) {
                throw new IllegalArgumentException("cycle.candidates must be at least 3");
            }
            return new Config(seedDirectory, workspace, initialModel, stopFile, iterations,
                    candidates, resume);
        }

        private static Path optionalPath(String property) {
            String value = System.getProperty(property);
            return value == null || value.isBlank() ? null : Path.of(value);
        }
    }

    public static final class StopRequested extends RuntimeException {

        private StopRequested(Path stopFile) {
            super("Closed-loop stop requested by " + stopFile.toAbsolutePath(), null, false, false);
        }
    }

    private ClosedLoopRunner() {
    }
}
