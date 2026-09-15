package io.euhedral_execution.benchmarks.cfd.benchmark;

import com.fasterxml.jackson.databind.JsonNode;
import io.euhedral_execution.benchmarks.cfd.config.CfdConfiguration;
import io.euhedral_execution.benchmarks.cfd.config.ConfigLoader;
import io.euhedral_execution.benchmarks.cfd.config.GridShape;
import io.euhedral_execution.benchmarks.cfd.execution.BackendOptions;
import io.euhedral_execution.benchmarks.cfd.execution.WorkerBudget;
import io.euhedral_execution.benchmarks.cfd.validation.NumericalIdentity;
import io.euhedral_execution.benchmarks.cfd.validation.ValidationProcess;
import io.euhedral_execution.benchmarks.cfd.validation.ValidationRunner;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/// Sequential process orchestration. External accuracy, same-kernel equivalence and runtime validity stay distinct.
public final class BenchmarkRunner {
    private BenchmarkRunner() {}

    public record CaseResult(
            String id,
            String caseIdentity,
            String externalStatus,
            String reason,
            String directory,
            List<BenchmarkResults.Summary> variants) {}

    public record Report(
            int schemaVersion,
            boolean complete,
            String directory,
            String numericalIdentity,
            String artifactIdentity,
            String sourceRevision,
            BenchmarkSuite suite,
            WorkerBudget parallelBudget,
            String validationReport,
            String validationSha256,
            List<CaseResult> cases) {}

    public static int command(String[] args, PrintStream out) throws IOException {
        BenchmarkSuite.require(
                args.length == 3 && args[1].equals("--config"), "Usage: euhedral-cfd bench --config <suite.json>");
        try {
            Report report = run(Path.of(args[2]), out);
            return report.cases().stream()
                            .allMatch(c -> c.externalStatus().equals("ELIGIBLE")
                                    && c.variants().stream()
                                            .allMatch(v -> v.status().equals("PASSED")))
                    ? 0
                    : 4;
        } catch (IOException error) {
            if (Thread.currentThread().isInterrupted()) {
                out.println("Benchmark interrupted; available artifacts are retained");
                return 130;
            }
            throw error;
        }
    }

    public static Report run(Path suitePath, PrintStream out) throws IOException {
        suitePath = suitePath.toAbsolutePath().normalize();
        var suite = BenchmarkSuite.load(suitePath);
        var base = suitePath.getParent();
        Files.createDirectories(base.resolve(suite.outputDirectory()));
        Path directory = Files.createTempDirectory(base.resolve(suite.outputDirectory()), "benchmark-");
        out.println("Benchmark directory: " + directory);
        var budget = WorkerBudget.resolve(
                new BackendOptions("euhedral", suite.workers(), "workers", suite.cpus(), suite.affinity(), null));
        out.println("Parallel worker budget: " + budget.workerCount() + " physical cores");
        String numerical = NumericalIdentity.current(), artifact = ValidationRunner.candidateIdentity();
        JsonNode validation;
        if (suite.validationSuite() != null) {
            String home = System.getenv("OPENLB_HOME");
            var report = ValidationRunner.run(
                    base.resolve(suite.validationSuite()), home == null ? null : Path.of(home), out);
            validation = BenchmarkSuite.JSON.valueToTree(report);
        } else {
            Path report = base.resolve(suite.validationReport());
            validation = Files.isRegularFile(report)
                    ? BenchmarkSuite.JSON.readTree(report.toFile())
                    : BenchmarkSuite.JSON.createObjectNode();
        }
        Path retainedValidation = directory.resolve("external-validation.json");
        Files.writeString(retainedValidation, ConfigLoader.json(validation));
        String validationSha = ValidationRunner.sha(retainedValidation);
        Files.writeString(directory.resolve("suite.json"), ConfigLoader.json(suite));
        var cases = new ArrayList<CaseResult>();
        for (var fixture : suite.cases()) {
            Path target = Files.createDirectory(directory.resolve(fixture.id()));
            CaseResult result;
            try {
                var config = ConfigLoader.load(
                        base.resolve(fixture.config()),
                        List.of(
                                "execution.brick.nx=" + suite.brick().nx(),
                                "execution.brick.ny=" + suite.brick().ny(),
                                "execution.brick.nz=" + suite.brick().nz(),
                                "execution.bricksPerFrame=" + suite.bricksPerFrame(),
                                "output.exportEverySteps=0",
                                "execution.diagnosticsEverySteps=0"));
                String identity = NumericalIdentity.caseIdentity(config);
                String rejection = ValidationGate.rejection(
                        validation, fixture.validationCase(), config, numerical, fixture.validationScope());
                if (config.steps() != suite.preSteps() + suite.stepsPerInvocation()) {
                    rejection = "duration must equal preSteps + stepsPerInvocation";
                }
                if (rejection != null) {
                    result = new CaseResult(
                            fixture.id(), identity, "INELIGIBLE", rejection, target.toString(), List.of());
                } else {
                    result = execute(
                            suite,
                            fixture,
                            config,
                            budget,
                            target,
                            retainedValidation,
                            validationSha,
                            identity,
                            numerical,
                            artifact,
                            out);
                }
            } catch (IOException | RuntimeException error) {
                result = new CaseResult(
                        fixture.id(), "unknown", "FAILED", error.toString(), target.toString(), List.of());
            }
            cases.add(result);
            out.println(fixture.id() + ": " + result.externalStatus() + " - " + result.reason());
            for (var variant : result.variants()) {
                out.println("  " + variant.id() + ": " + variant.status());
            }
            var partial = new Report(
                    1,
                    cases.size() == suite.cases().size()
                            && !Thread.currentThread().isInterrupted(),
                    directory.toString(),
                    numerical,
                    artifact,
                    ValidationRunner.candidateRevision(),
                    suite,
                    budget,
                    retainedValidation.toString(),
                    validationSha,
                    List.copyOf(cases));
            write(partial);
            if (Thread.currentThread().isInterrupted()) {
                throw new java.io.InterruptedIOException("benchmark interrupted; partial report retained");
            }
        }
        return new Report(
                1,
                cases.size() == suite.cases().size() && !Thread.currentThread().isInterrupted(),
                directory.toString(),
                numerical,
                artifact,
                ValidationRunner.candidateRevision(),
                suite,
                budget,
                retainedValidation.toString(),
                validationSha,
                List.copyOf(cases));
    }

    private static CaseResult execute(
            BenchmarkSuite suite,
            BenchmarkSuite.Case fixture,
            CfdConfiguration config,
            WorkerBudget budget,
            Path directory,
            Path validation,
            String validationSha,
            String identity,
            String numerical,
            String artifact,
            PrintStream out)
            throws IOException {
        Path configuration = directory.resolve("configuration.json");
        Files.writeString(configuration, ConfigLoader.replayJson(config));
        var serialBudget = new WorkerBudget(
                budget.requestedCpus(),
                new int[] {budget.effectiveCpus()[0]},
                1,
                budget.driverCpu(),
                budget.affinityCapability());
        var references = new HashMap<GridShape, Reference>();
        var summaries = new ArrayList<BenchmarkResults.Summary>();
        for (var variant : suite.variants()) {
            var selected = variant.backend().equals("serial") ? serialBudget : budget;
            var options = new BackendOptions(
                    variant.backend(), selected.workerCount(), variant.sources(), null, suite.affinity(), null);
            var brick = variant.brickOrDefault(suite.brick());
            var reference = references.get(brick);
            if (reference == null) {
                var partition = ConfigLoader.load(
                        configuration,
                        List.of(
                                "execution.brick.nx=" + brick.nx(),
                                "execution.brick.ny=" + brick.ny(),
                                "execution.brick.nz=" + brick.nz()));
                Path target = directory.resolve(brick.equals(suite.brick()) ? "reference" : "reference-" + brick);
                reference = prepareReference(
                        suite,
                        fixture,
                        partition,
                        budget,
                        serialBudget,
                        target,
                        validation,
                        validationSha,
                        identity,
                        numerical,
                        artifact,
                        out);
                references.put(brick, reference);
            }
            if (reference.error() != null) {
                summaries.add(new BenchmarkResults.Summary(
                        variant.id(),
                        variant.backend(),
                        variant.sources(),
                        options.sourceCount(selected.workerCount()),
                        selected.workerCount(),
                        reference.timedOut() ? "TIMED_OUT" : "FAILED",
                        List.of(),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null));
                out.println(fixture.id() + "/" + variant.id() + ": " + reference.error());
                if (Thread.currentThread().isInterrupted()) {
                    break;
                }
                continue;
            }
            var forks = new ArrayList<BenchmarkResults.Fork>();
            for (int fork = 0; fork < suite.forks(); fork++) {
                Path target =
                        Files.createDirectories(directory.resolve(variant.id()).resolve("fork-" + fork));
                var forkJob = job(
                        suite,
                        fixture,
                        reference.configuration(),
                        target,
                        reference.populations(),
                        reference.sha256(),
                        validation,
                        validationSha,
                        identity,
                        numerical,
                        artifact,
                        options,
                        selected);
                Path file = target.resolve("job.json");
                Files.writeString(file, ConfigLoader.json(forkJob));
                out.println(fixture.id() + "/" + variant.id() + ": starting fork " + (fork + 1) + "/" + suite.forks()
                        + ", workers=" + selected.workerCount() + ", sources="
                        + options.sourceCount(selected.workerCount()) + ", brick=" + brick + ", bricksPerFrame="
                        + suite.bricksPerFrame());
                out.println("  Fork log: " + target.resolve("process.log"));
                long started = System.nanoTime();
                BenchmarkResults.Fork result;
                try {
                    var run = process(suite, file, BenchmarkFork.class, target, out);
                    result = run.completed()
                            ? BenchmarkResults.read(target, forkJob, System.nanoTime() - started)
                            : new BenchmarkResults.Fork(
                                    run.timedOut() ? "TIMED_OUT" : "FAILED",
                                    "fork failed; see process.log",
                                    target.toString(),
                                    null,
                                    List.of(),
                                    0,
                                    System.nanoTime() - started);
                } catch (IOException | RuntimeException error) {
                    result = new BenchmarkResults.Fork(
                            "FAILED",
                            error.toString(),
                            target.toString(),
                            null,
                            List.of(),
                            0,
                            System.nanoTime() - started);
                }
                out.println(fixture.id() + "/" + variant.id() + ": fork " + (fork + 1) + "/" + suite.forks() + " "
                        + result.status());
                forks.add(result);
                writeFile(target.resolve("result.json"), ConfigLoader.json(result));
                if (Thread.currentThread().isInterrupted()) {
                    break;
                }
            }
            summaries.add(BenchmarkResults.summarize(
                    variant,
                    selected.workerCount(),
                    options.sourceCount(selected.workerCount()),
                    forks,
                    suite.forks(),
                    suite.stepsPerInvocation(),
                    suite.maxForkCv()));
            if (Thread.currentThread().isInterrupted()) {
                break;
            }
        }
        var baseline = summaries.stream()
                .filter(s -> s.id().equals(suite.baselineVariant()))
                .findFirst()
                .orElse(null);
        var serial = summaries.stream()
                .filter(s -> s.backend().equals("serial"))
                .findFirst()
                .orElse(null);
        return new CaseResult(
                fixture.id(),
                identity,
                "ELIGIBLE",
                "external coverage: " + fixture.validationScope() + "; " + suite.referenceBackend()
                        + " reference artifacts retained for " + references.size() + " brick shape(s)",
                directory.toString(),
                summaries.stream()
                        .map(s -> BenchmarkResults.compare(s, baseline, serial))
                        .toList());
    }

    /// A complete reference is shared across forks and variants with the same summation partition.
    private record Reference(Path configuration, Path populations, String sha256, String error, boolean timedOut) {}

    private static Reference prepareReference(
            BenchmarkSuite suite,
            BenchmarkSuite.Case fixture,
            CfdConfiguration config,
            WorkerBudget budget,
            WorkerBudget serialBudget,
            Path referenceDirectory,
            Path validation,
            String validationSha,
            String identity,
            String numerical,
            String artifact,
            PrintStream out)
            throws IOException {
        Files.createDirectory(referenceDirectory);
        Path configuration = referenceDirectory.resolve("configuration.json");
        Files.writeString(configuration, ConfigLoader.replayJson(config));
        Path reference = referenceDirectory.resolve("populations.bin");
        var referenceBudget = suite.referenceBackend().equals("serial") ? serialBudget : budget;
        var referenceOptions = new BackendOptions(
                suite.referenceBackend(), referenceBudget.workerCount(), "workers", null, suite.affinity(), null);
        var job = job(
                suite,
                fixture,
                configuration,
                referenceDirectory,
                reference,
                null,
                validation,
                validationSha,
                identity,
                numerical,
                artifact,
                referenceOptions,
                referenceBudget);
        Path jobFile = referenceDirectory.resolve("job.json");
        Files.writeString(jobFile, ConfigLoader.json(job));
        out.println(fixture.id() + ": preparing " + suite.referenceBackend() + " reference with "
                + referenceBudget.workerCount() + " worker(s), " + config.steps() + " steps on "
                + config.config().grid() + ", brick="
                + config.config().execution().brick());
        out.println("  Reference log: " + referenceDirectory.resolve("process.log"));
        var process = process(suite, jobFile, ReferenceWorker.class, referenceDirectory, out);
        if (!process.completed() || !Files.isRegularFile(referenceDirectory.resolve("reference.json"))) {
            return new Reference(
                    configuration,
                    reference,
                    null,
                    process.timedOut()
                            ? "reference timed out"
                            : "reference failed; see " + referenceDirectory.resolve("process.log"),
                    process.timedOut());
        }
        out.println(fixture.id() + ": full reference completed (" + suite.referenceBackend() + ")");
        return new Reference(configuration, reference, ValidationRunner.sha(reference), null, false);
    }

    private static BenchmarkJob job(
            BenchmarkSuite suite,
            BenchmarkSuite.Case fixture,
            Path config,
            Path directory,
            Path reference,
            String referenceSha,
            Path validation,
            String validationSha,
            String identity,
            String numerical,
            String artifact,
            BackendOptions options,
            WorkerBudget budget) {
        return new BenchmarkJob(
                config.toString(),
                directory.toString(),
                reference.toString(),
                referenceSha,
                validation.toString(),
                validationSha,
                fixture.validationCase(),
                fixture.validationScope(),
                identity,
                numerical,
                artifact,
                options,
                budget,
                suite.preSteps(),
                suite.stepsPerInvocation(),
                suite.warmupIterations(),
                suite.measurementIterations(),
                suite.iterationMillis(),
                suite.processDeadlineMillis());
    }

    private static ValidationProcess.Result process(
            BenchmarkSuite suite, Path job, Class<?> main, Path directory, PrintStream out) throws IOException {
        var command = new ArrayList<String>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.addAll(suite.jvmArgs());
        command.add("-cp");
        command.add(
                java.util.Arrays.stream(System.getProperty("java.class.path").split(java.io.File.pathSeparator))
                        .map(p -> Path.of(p).toAbsolutePath().toString())
                        .collect(java.util.stream.Collectors.joining(java.io.File.pathSeparator)));
        command.add(main.getName());
        command.add(job.toString());
        Files.writeString(directory.resolve("command.json"), ConfigLoader.json(command));
        try (var progress = new ProgressLog(directory.resolve("process.log"), out)) {
            return ValidationProcess.run(
                    command, directory, directory.resolve("process.log"), suite.processDeadlineMillis());
        }
    }

    private static void write(Report report) throws IOException {
        Path directory = Path.of(report.directory());
        writeFile(directory.resolve("report.json"), ConfigLoader.json(report));
        var csv = new StringBuilder(
                "case,external_coverage,variant,backend,sources,workers,status,seconds_per_invocation,fork_sd_seconds,fork_cv,mlups,speedup,parallel_efficiency,brick,bricks_per_frame\n");
        var markdown = new StringBuilder("# CFD execution comparison\n\nSpeedup baseline: `"
                + report.suite().baselineVariant()
                + "`. Bricks per frame: " + report.suite().bricksPerFrame()
                + ". Only PASSED variants receive derived performance metrics.\n\n"
                + "| Case | External coverage | Variant | Sources | Workers | Status | MLUPS | Speedup | Efficiency |"
                + " Fork CV | Brick |\n"
                + "|---|---|---|---:|---:|---|---:|---:|---:|---:|---|\n");
        for (var fixture : report.cases()) {
            var coverage = report.suite().cases().stream()
                    .filter(c -> c.id().equals(fixture.id()))
                    .findFirst()
                    .orElseThrow()
                    .validationScope();
            if (fixture.variants().isEmpty()) {
                csv.append(fixture.id())
                        .append(',')
                        .append(coverage)
                        .append(",,,,,")
                        .append(fixture.externalStatus())
                        .append(",,,,,,,\n");
                markdown.append('|')
                        .append(fixture.id())
                        .append('|')
                        .append(coverage)
                        .append("| - | - | - |")
                        .append(fixture.externalStatus())
                        .append("| - | - | - | - | - |\n");
            }
            for (var variant : fixture.variants()) {
                var brick = report.suite().variants().stream()
                        .filter(v -> v.id().equals(variant.id()))
                        .findFirst()
                        .orElseThrow()
                        .brickOrDefault(report.suite().brick());
                csv.append(fixture.id())
                        .append(',')
                        .append(coverage)
                        .append(',')
                        .append(variant.id())
                        .append(',')
                        .append(variant.backend())
                        .append(',')
                        .append(variant.resolvedSources())
                        .append(',')
                        .append(variant.workers())
                        .append(',')
                        .append(variant.status())
                        .append(',')
                        .append(value(variant.meanSeconds()))
                        .append(',')
                        .append(value(variant.forkStdDevSeconds()))
                        .append(',')
                        .append(value(variant.forkCv()))
                        .append(',')
                        .append(value(variant.mlups()))
                        .append(',')
                        .append(value(variant.speedup()))
                        .append(',')
                        .append(value(variant.parallelEfficiency()))
                        .append(',')
                        .append(brick)
                        .append(',')
                        .append(report.suite().bricksPerFrame())
                        .append('\n');
                markdown.append('|')
                        .append(fixture.id())
                        .append('|')
                        .append(coverage)
                        .append('|')
                        .append(variant.id())
                        .append('|')
                        .append(variant.resolvedSources())
                        .append('|')
                        .append(variant.workers())
                        .append('|')
                        .append(variant.status())
                        .append('|')
                        .append(value(variant.mlups()))
                        .append('|')
                        .append(value(variant.speedup()))
                        .append('|')
                        .append(value(variant.parallelEfficiency()))
                        .append('|')
                        .append(value(variant.forkCv()))
                        .append('|')
                        .append(brick)
                        .append("|\n");
            }
        }
        writeFile(directory.resolve("comparison.csv"), csv.toString());
        writeFile(directory.resolve("comparison.md"), markdown.toString());
    }

    private static void writeFile(Path target, String text) throws IOException {
        boolean interrupted = Thread.interrupted();
        Path temporary = null;
        try {
            temporary = Files.createTempFile(target.getParent(), ".benchmark-", ".tmp");
            Files.writeString(temporary, text);
            Files.move(
                    temporary,
                    target,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } finally {
            try {
                if (temporary != null) {
                    Files.deleteIfExists(temporary);
                }
            } finally {
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    private static String value(Double value) {
        return value == null ? "" : Double.toString(value);
    }
}
