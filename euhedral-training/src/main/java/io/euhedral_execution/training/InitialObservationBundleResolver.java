package io.euhedral_execution.training;

import io.euhedral_execution.training.config.ClosedLoopConfig;
import io.euhedral_execution.training.data.io.ObservationBundleReader;
import io.euhedral_execution.training.merge.data.CalibrationPlan;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public final class InitialObservationBundleResolver {

    public static List<Path> resolve(ClosedLoopConfig config, CalibrationPlan plan) throws IOException {
        ArrayList<Path> result = new ArrayList<>(config.initialObservationBundles());
        if (config.initialObservationBundleDirectory().isPresent()) {
            result.addAll(resolveReferenceBundles(
                    config.initialObservationBundleDirectory().orElseThrow(), plan));
        }
        return List.copyOf(result);
    }

    private static List<Path> resolveReferenceBundles(Path directory, CalibrationPlan plan) throws IOException {
        Path root = directory.toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            throw new IllegalArgumentException("Initial observation bundle directory is not a directory");
        }
        Map<String, Path> bundlesByRunId = new TreeMap<>();
        try (var stream = Files.list(root)) {
            for (Path candidate : stream.filter(Files::isDirectory)
                    .filter(path -> Files.isRegularFile(path.resolve("COMPLETE")))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList()) {
                String runId = ObservationBundleReader.read(candidate)
                        .run()
                        .descriptor()
                        .benchmarkRunId();
                Path previous = bundlesByRunId.putIfAbsent(
                        runId, candidate.toAbsolutePath().normalize());
                if (previous != null) {
                    throw new IllegalArgumentException(
                            "Duplicate initial observation bundle for benchmark run " + runId);
                }
            }
        }
        ArrayList<Path> result = new ArrayList<>();
        for (String runId : plan.references().referenceRunIds().values()) {
            Path bundle = bundlesByRunId.get(runId);
            if (bundle == null) {
                throw new IllegalArgumentException("Missing initial observation bundle for reference run " + runId);
            }
            result.add(bundle);
        }
        return result;
    }

    private InitialObservationBundleResolver() {}
}
