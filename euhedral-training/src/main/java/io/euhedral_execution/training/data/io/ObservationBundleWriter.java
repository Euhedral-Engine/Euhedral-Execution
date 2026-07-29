package io.euhedral_execution.training.data.io;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import io.euhedral_execution.training.data.BenchmarkObservation;
import io.euhedral_execution.training.data.BenchmarkParameters;
import io.euhedral_execution.training.data.BenchmarkRunContext;
import io.euhedral_execution.training.data.BenchmarkRunDescriptor;
import io.euhedral_execution.training.data.FrameSourceSeed;
import io.euhedral_execution.training.data.PolicyId;
import io.euhedral_execution.training.data.PolicyVector;
import io.euhedral_execution.training.data.ScheduledPolicy;

public final class ObservationBundleWriter implements AutoCloseable {

    private static final String RUN_HEADER =
            "schema_version,benchmark_run_id,closed_loop_iteration,candidate_cohort_id,scenario_id,environment_id,source_count,available_physical_core_count,source_ratio_numerator,source_ratio_denominator,commit_sha,dirty_working_tree,evidence_origin,started_at,completed_at,expected_repetitions,sample_duration_nanos,liveness_timeout_nanos,frames_per_source,reset_timeout_nanos,ordered_frames,cpu_set_hex,frame_source_seeds\n";
    private static final String OBS_HEADER =
            "schema_version,observation_id,policy_id,repetition_number,status,measurement_encoding,started_at,ended_at,elapsed_nanos,completed_frames,throughput_frames_per_second,failure_code\n";

    public static ObservationBundleWriter open(Path directory, BenchmarkRunDescriptor run) {
        try {
            Files.createDirectories(directory);
            if (!Files.isDirectory(directory) || Files.exists(directory.resolve("COMPLETE"))) {
                throw new IllegalArgumentException("Completed bundle already exists");
            }
            FileChannel policies = FileChannel.open(directory.resolve("policies.csv"),
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            FileChannel observations = FileChannel.open(directory.resolve("observations.csv"),
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            ObservationBundleWriter writer =
                    new ObservationBundleWriter(directory, run, policies, observations);
            writer.writeChannel(policies, writer.policiesHeader());
            writer.writeChannel(observations, OBS_HEADER);
            return writer;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private final Path directory;
    private final BenchmarkRunDescriptor run;
    private final List<ScheduledPolicy> policies = new ArrayList<>();
    private final Set<PolicyId> policyIds = new HashSet<>();
    private final FileChannel policiesChannel;
    private final FileChannel observationsChannel;
    private int observationCount;
    private Instant latestObservationEnd;
    private boolean observationsStarted;
    private boolean complete;

    private ObservationBundleWriter(Path directory, BenchmarkRunDescriptor run,
            FileChannel policiesChannel, FileChannel observationsChannel) {
        this.directory = directory;
        this.run = run;
        this.policiesChannel = policiesChannel;
        this.observationsChannel = observationsChannel;
    }

    public void registerPolicy(ScheduledPolicy policy) {
        if (complete || observationsStarted || policy.schedulePosition() != policies.size() + 1
                || !policyIds.add(policy.policy().id())) {
            throw new IllegalStateException("Policies must be registered contiguously");
        }
        policies.add(policy);
        writeChannel(policiesChannel, policyRow(policy));
    }

    public void write(BenchmarkObservation observation) {
        if (complete) {
            throw new IllegalStateException("Bundle is already complete");
        }
        observationsStarted = true;
        if (!observation.run().equals(run)) {
            throw new IllegalArgumentException("Run mismatch");
        }
        int position = observation.scheduledPolicy().schedulePosition();
        int repetition = observation.key().repetitionNumber();
        int expectedIndex = observationCount;
        int expectedPosition = expectedIndex / run.parameters().expectedRepetitions() + 1;
        int expectedRepetition = expectedIndex % run.parameters().expectedRepetitions() + 1;
        if (position != expectedPosition || repetition != expectedRepetition
                || position > policies.size() || !policies.get(position - 1)
                .equals(observation.scheduledPolicy())) {
            throw new IllegalStateException("Observations are out of order");
        }
        writeChannel(observationsChannel, observationRow(observation));
        if (latestObservationEnd == null || observation.endedAt().isAfter(latestObservationEnd)) {
            latestObservationEnd = observation.endedAt();
        }
        observationCount++;
    }

    public BenchmarkRunContext complete(Instant completedAt) {
        if (complete || policies.isEmpty() || observationCount != policies.size() * run.parameters()
                .expectedRepetitions()) {
            throw new IllegalStateException("Bundle is incomplete");
        }
        BenchmarkRunContext context = new BenchmarkRunContext(run, completedAt);
        if (latestObservationEnd.isAfter(completedAt)) {
            throw new IllegalArgumentException("Observation ends after run");
        }
        try {
            policiesChannel.force(true);
            observationsChannel.force(true);
            try (FileChannel runChannel = FileChannel.open(directory.resolve("run.csv"),
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                writeChannel(runChannel, runCsv(context));
                runChannel.force(true);
            }
            try (FileChannel marker = FileChannel.open(directory.resolve("COMPLETE"),
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                marker.force(true);
            }
            complete = true;
            return context;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private String policiesHeader() {
        List<String> header = new ArrayList<>(
                List.of("schema_version", "schedule_position", "policy_id", "roles"));
        for (int i = 0; i < PolicyVector.WIDTH; i++) {
            header.add(String.format("weight_%02d_bits", i));
        }
        return StrictCsv.row(header);
    }

    private String policyRow(ScheduledPolicy policy) {
        List<String> row = new ArrayList<>(List.of("1", Integer.toString(policy.schedulePosition()),
                policy.policy().id().canonical(),
                policy.roles().stream().map(Enum::name).sorted().reduce((a, b) -> a + ";" + b)
                        .orElseThrow()));
        for (double weight : policy.policy().copyWeights()) {
            row.add(StrictCsv.hex(Double.doubleToRawLongBits(weight)));
        }
        return StrictCsv.row(row);
    }

    private String observationRow(BenchmarkObservation observation) {
        return StrictCsv.row(List.of("1", observation.key().canonical(),
                observation.key().policyId().canonical(),
                Integer.toString(observation.key().repetitionNumber()), observation.status().name(),
                observation.measurementEncoding().name(), observation.startedAt().toString(),
                observation.endedAt().toString(),
                observation.elapsedNanos().isPresent() ? Long.toString(
                        observation.elapsedNanos().getAsLong()) : "",
                observation.completedFrames().isPresent() ? Long.toString(
                        observation.completedFrames().getAsLong()) : "",
                observation.throughputFramesPerSecond().isPresent() ? Double.toString(
                        observation.throughputFramesPerSecond().getAsDouble()) : "",
                observation.failureCode()));
    }

    private String runCsv(BenchmarkRunContext context) {
        BenchmarkParameters p = run.parameters();
        String seeds = p.frameSourceSeeds().stream()
                .sorted(Comparator.comparingInt(FrameSourceSeed::sourceIndex))
                .map(seed -> seed.sourceIndex() + ":" + StrictCsv.hex(seed.idHash()) + ":"
                        + StrictCsv.hex(seed.routingSeed())).reduce((a, b) -> a + ";" + b)
                .orElse("");
        return RUN_HEADER + StrictCsv.row(
                List.of("1", run.benchmarkRunId(), Integer.toString(run.closedLoopIteration()),
                        run.candidateCohortId(), run.scenario().canonical(),
                        run.scenario().environmentId(),
                        Integer.toString(run.scenario().sourceCount()),
                        Integer.toString(run.scenario().availablePhysicalCoreCount()),
                        Integer.toString(run.scenario().ratio().numerator()),
                        Integer.toString(run.scenario().ratio().denominator()), run.commitSha(),
                        Boolean.toString(run.dirtyWorkingTree()), run.evidenceOrigin().name(),
                        run.startedAt().toString(), context.completedAt().toString(),
                        Integer.toString(p.expectedRepetitions()),
                        Long.toString(p.sampleDurationNanos()),
                        Long.toString(p.livenessTimeoutNanos()),
                        Integer.toString(p.framesPerSource()), Long.toString(p.resetTimeoutNanos()),
                        Boolean.toString(p.orderedFrames()), p.cpuSetHex(), seeds));
    }

    @Override
    public void close() {
        IOException failure = null;
        try {
            policiesChannel.force(true);
        } catch (IOException error) {
            failure = error;
        }
        try {
            observationsChannel.force(true);
        } catch (IOException error) {
            if (failure == null) {
                failure = error;
            } else {
                failure.addSuppressed(error);
            }
        }
        try {
            policiesChannel.close();
        } catch (IOException error) {
            if (failure == null) {
                failure = error;
            } else {
                failure.addSuppressed(error);
            }
        }
        try {
            observationsChannel.close();
        } catch (IOException error) {
            if (failure == null) {
                failure = error;
            } else {
                failure.addSuppressed(error);
            }
        }
        if (failure != null) {
            throw new IllegalStateException(failure);
        }
    }

    private void writeChannel(FileChannel channel, String value) {
        try {
            ByteBuffer bytes = StandardCharsets.UTF_8.encode(value);
            while (bytes.hasRemaining()) {
                channel.write(bytes);
            }
        } catch (IOException error) {
            throw new IllegalStateException(error);
        }
    }
}
