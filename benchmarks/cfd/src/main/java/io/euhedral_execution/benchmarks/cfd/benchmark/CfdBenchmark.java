package io.euhedral_execution.benchmarks.cfd.benchmark;

import io.euhedral_execution.benchmarks.cfd.config.CfdConfiguration;
import io.euhedral_execution.benchmarks.cfd.config.ConfigLoader;
import io.euhedral_execution.benchmarks.cfd.output.VtiWriter;
import io.euhedral_execution.benchmarks.cfd.solver.Simulation;
import io.euhedral_execution.benchmarks.cfd.validation.ValidationRunner;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.IterationParams;
import org.openjdk.jmh.runner.IterationType;

@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.SECONDS)
@Threads(1)
public class CfdBenchmark {
    @Param("required-job.json")
    public String jobFile;

    private BenchmarkJob job;
    private CfdConfiguration configuration;
    private long checkedWarmupInvocations, visualizationExportNs;
    private boolean warmingUp;
    private Path visualization;
    private BenchmarkRuntime runtime;
    private Simulation simulation;
    private String referenceBackend;
    private BenchmarkProgress progress;
    private String iterationLabel;
    private long iterationInvocation;
    private int warmupIteration, measurementIteration;
    private long setupNs, resetNs, comparisonNs, invocations, invocationStarted, endToEndNs;
    private boolean valid, pending;

    @Setup(Level.Trial)
    public void prepareTrial() throws Exception {
        long started = System.nanoTime();
        job = BenchmarkSuite.JSON.readValue(Path.of(jobFile).toFile(), BenchmarkJob.class);
        var config = ConfigLoader.load(Path.of(job.configuration()));
        configuration = config;
        ValidationGate.requireEligible(job, config);
        referenceBackend = PopulationReference.requireEvidence(job);
        BenchmarkSuite.require(
                ValidationRunner.sha(Path.of(job.reference())).equals(job.referenceSha256()), "full reference changed");
        runtime = new BenchmarkRuntime(config, job.options(), job.budget());
        simulation = runtime.simulation();
        progress = new BenchmarkProgress(job);
        valid = true;
        setupNs = System.nanoTime() - started;
    }

    @Setup(Level.Iteration)
    public void iteration(IterationParams parameters) {
        warmingUp = parameters.getType() == IterationType.WARMUP;
        if (warmingUp) {
            iterationLabel = "warmup " + (++warmupIteration) + "/" + job.warmupIterations();
        } else {
            BenchmarkSuite.require(
                    valid && !pending && checkedWarmupInvocations > 0,
                    "measurement requires a successfully checked warmup");
            iterationLabel = "measurement " + (++measurementIteration) + "/" + job.measurementIterations();
        }
        iterationInvocation = 0;
    }

    @Setup(Level.Invocation)
    public void resetInvocation() {
        invocationStarted = System.nanoTime();
        pending = true;
        try {
            runtime.checkWorkers(job.budget().workerCount());
            progress.begin("resetting fields", 0);
            simulation.reset();
            progress.finish();
            if (job.preSteps() > 0) {
                progress.begin("physical pre-steps", job.preSteps());
            }
            for (long i = 0; i < job.preSteps(); i++) {
                simulation.step();
                progress.completed(i + 1);
            }
            progress.finish();
            progress.begin(iterationLabel + " invocation " + (++iterationInvocation), job.stepsPerInvocation());
            resetNs += System.nanoTime() - invocationStarted;
        } catch (RuntimeException | Error error) {
            valid = false;
            throw error;
        }
    }

    @Benchmark
    public long advance() {
        try {
            for (long i = 0; i < job.stepsPerInvocation(); i++) {
                simulation.step();
                progress.completed(i + 1);
            }
            return simulation.state().completedSteps();
        } catch (RuntimeException | Error error) {
            valid = false;
            throw error;
        }
    }

    @TearDown(Level.Invocation)
    public void checkInvocation() throws Exception {
        long started = System.nanoTime();
        progress.finish();
        progress.begin("checking invocation fields", 0);
        try {
            runtime.checkWorkers(job.budget().workerCount());
            BenchmarkSuite.require(
                    simulation.state().completedSteps() == job.preSteps() + job.stepsPerInvocation(),
                    "incomplete invocation");
            PopulationReference.verify(Path.of(job.reference()), simulation.state(), job.caseIdentity());
            invocations++;
            if (warmingUp) {
                checkedWarmupInvocations++;
            }
            pending = false;
            progress.finish();
        } catch (Exception | Error error) {
            valid = false;
            throw error;
        } finally {
            comparisonNs += System.nanoTime() - started;
            endToEndNs += System.nanoTime() - invocationStarted;
        }
    }

    @TearDown(Level.Trial)
    public void closeTrial() throws Exception {
        try {
            try {
                if (valid
                        && !pending
                        && checkedWarmupInvocations > 0
                        && measurementIteration > 0
                        && job.options().backend().equals("euhedral")) {
                    long started = System.nanoTime();
                    Path target = Path.of(job.directory()).resolve("simulation-final.vti");
                    progress.begin("writing ParaView snapshot " + target, 0);
                    new VtiWriter().write(target, simulation.state(), configuration);
                    visualization = target;
                    visualizationExportNs = System.nanoTime() - started;
                    progress.finish();
                }
            } finally {
                try {
                    if (runtime != null) {
                        runtime.close();
                    }
                } finally {
                    if (progress != null) {
                        progress.close();
                    }
                }
            }
        } catch (Exception | Error error) {
            valid = false;
            throw error;
        } finally {
            if (job != null) {
                var metadata = new LinkedHashMap<String, Object>();
                metadata.put("status", valid && !pending && invocations > 0 ? "COMPLETED" : "FAILED");
                metadata.put("backendEquivalenceVerified", valid && !pending && invocations > 0);
                metadata.put("checkedInvocations", invocations);
                metadata.put("checkedWarmupInvocations", checkedWarmupInvocations);
                metadata.put("backendQualificationPolicy", "CHECKED_WARMUP");
                metadata.put("setupNs", setupNs);
                metadata.put("visualizationFile", visualization == null ? null : visualization.toString());
                metadata.put("visualizationExportNs", visualizationExportNs);
                metadata.put("resetAndPreStepsNs", resetNs);
                metadata.put("fullFieldComparisonNs", comparisonNs);
                metadata.put("invocationEndToEndNs", endToEndNs);
                metadata.put(
                        "endToEndScope",
                        "all warmup/measurement invocations, including reset, pre-steps and complete comparison");
                metadata.put("resetPolicy", "DRIVER_ZERO_BOTH_BUFFERS_THEN_INITIALIZE_AND_PRESTEP");
                metadata.put("firstTouchPolicy", "DRIVER");
                metadata.put("schedulerStatePolicy", "CONTINUOUS_PER_FORK");
                metadata.put(
                        "submissionPolicy",
                        job.options().backend().equals("euhedral")
                                        || job.options().backend().equals("serial")
                                ? "LAZY_STRIDED_SOURCES"
                                : "RETAINED_RANGES");
                metadata.put("framesCreated", runtime == null ? null : runtime.framesCreated());
                metadata.put(
                        "logicalRangesPerTimestep",
                        configuration == null
                                ? null
                                : configuration
                                        .config()
                                        .grid()
                                        .brickCount(configuration
                                                .config()
                                                .execution()
                                                .brick()));
                metadata.put("intermediateDiagnostics", false);
                metadata.put("allocationScope", "JMH GC profiler includes invocation reset and full-field validation");
                metadata.put("precision", "double");
                metadata.put("progressIntervalMillis", BenchmarkProgress.INTERVAL_MILLIS);
                metadata.put("progressPolicy", "opaque counter per completed timestep; asynchronous pass ETA");
                metadata.put("externalCoverage", job.validationScope());
                metadata.put("referenceBackend", referenceBackend);
                metadata.put("pid", ProcessHandle.current().pid());
                metadata.put("javaVersion", System.getProperty("java.runtime.version"));
                metadata.put("jvmArgs", ManagementFactory.getRuntimeMXBean().getInputArguments());
                metadata.put("sourceRevision", ValidationRunner.candidateRevision());
                metadata.put("job", job);
                if (simulation != null) {
                    metadata.put("fluidCells", simulation.state().geometry().fluidCells());
                    metadata.put("grid", simulation.state().shape());
                }
                Files.writeString(Path.of(job.directory()).resolve("trial.json"), ConfigLoader.json(metadata));
            }
        }
    }
}
