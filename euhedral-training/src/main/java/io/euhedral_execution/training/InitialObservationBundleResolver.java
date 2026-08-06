package io.euhedral_execution.training;

import io.euhedral_execution.training.checkpoint.ArtifactFingerprint;
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
        Map<String, Path> bundlesByRunId = new TreeMap<>();
        for (Path bundle : config.initialObservationBundles()) {
            registerBundle(bundlesByRunId, bundle.toAbsolutePath().normalize());
        }
        if (config.initialObservationBundleDirectory().isPresent()) {
            for (Path bundle :
                    resolveBundles(config.initialObservationBundleDirectory().orElseThrow())) {
                registerBundle(bundlesByRunId, bundle);
            }
        }
        for (String runId : plan.references().referenceRunIds().values()) {
            if (!bundlesByRunId.containsKey(runId)) {
                throw new IllegalArgumentException("Missing initial observation bundle for reference run " + runId);
            }
        }
        return List.copyOf(bundlesByRunId.values());
    }

    private static List<Path> resolveBundles(Path directory) throws IOException {
        Path root = directory.toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            throw new IllegalArgumentException("Initial observation bundle directory is not a directory");
        }
        ArrayList<Path> result = new ArrayList<>();
        try (var stream = Files.list(root)) {
            for (Path candidate : stream.filter(Files::isDirectory)
                    .filter(path -> Files.isRegularFile(path.resolve("COMPLETE")))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList()) {
                result.add(candidate.toAbsolutePath().normalize());
            }
        }
        return List.copyOf(result);
    }

    private static void registerBundle(Map<String, Path> bundlesByRunId, Path bundle) throws IOException {
        String runId = ObservationBundleReader.readRunId(bundle);
        Path previous = bundlesByRunId.putIfAbsent(runId, bundle);
        if (previous != null) {
            if (!ArtifactFingerprint.sha256(previous).equals(ArtifactFingerprint.sha256(bundle))) {
                throw new IllegalArgumentException("Duplicate initial observation bundle for benchmark run " + runId);
            }
        }
    }

    private InitialObservationBundleResolver() {}
}
