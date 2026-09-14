package io.euhedral_execution.benchmarks.cfd.benchmark;

import io.euhedral_execution.benchmarks.cfd.config.ConfigLoader;
import io.euhedral_execution.benchmarks.cfd.validation.ValidationRunner;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;

/// Generate one full reference outside JMH after checking external numerical evidence.
public final class ReferenceWorker {
    private ReferenceWorker() {}

    public static void main(String[] args) throws Exception {
        long started = System.nanoTime();
        var job = BenchmarkSuite.JSON.readValue(Path.of(args[0]).toFile(), BenchmarkJob.class);
        var config = ConfigLoader.load(Path.of(job.configuration()));
        ValidationGate.requireEligible(job, config);
        System.out.println("Generating full reference: backend=" + job.options().backend() + ", workers="
                + job.budget().workerCount() + ", grid=" + config.config().grid() + ", steps=" + config.steps());
        long fluid;
        try (var progress = new BenchmarkProgress(job);
                var runtime = new BenchmarkRuntime(config, job.options(), job.budget())) {
            progress.begin("full reference", config.steps());
            for (long step = 1; step <= config.steps(); step++) {
                runtime.simulation().step();
                progress.completed(step);
            }
            progress.finish();
            progress.begin("writing reference fields", 0);
            var state = runtime.simulation().state();
            fluid = state.geometry().fluidCells();
            PopulationReference.write(Path.of(job.reference()), state, job.caseIdentity());
            progress.finish();
        }
        var metadata = new LinkedHashMap<String, Object>();
        metadata.put("status", "COMPLETED");
        metadata.put("backend", job.options().backend());
        metadata.put("options", job.options());
        metadata.put("budget", job.budget());
        metadata.put("fluidCells", fluid);
        metadata.put("referenceSha256", ValidationRunner.sha(Path.of(job.reference())));
        metadata.put("simulationAndExportEndToEndNs", System.nanoTime() - started);
        metadata.put("caseIdentity", job.caseIdentity());
        metadata.put("artifactIdentity", job.artifactIdentity());
        metadata.put("precision", "double");
        metadata.put("sourceRevision", ValidationRunner.candidateRevision());
        Files.writeString(Path.of(job.directory()).resolve("reference.json"), ConfigLoader.json(metadata));
        System.out.println("Full reference completed");
    }
}
