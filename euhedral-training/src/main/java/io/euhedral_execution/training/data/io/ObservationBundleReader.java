package io.euhedral_execution.training.data.io;

import io.euhedral_execution.training.data.BenchmarkObservation;
import io.euhedral_execution.training.data.BenchmarkParameters;
import io.euhedral_execution.training.data.BenchmarkRunContext;
import io.euhedral_execution.training.data.BenchmarkRunDescriptor;
import io.euhedral_execution.training.data.FrameSourceSeed;
import io.euhedral_execution.training.data.ObservationKey;
import io.euhedral_execution.training.data.PolicyId;
import io.euhedral_execution.training.data.PolicyRegistry;
import io.euhedral_execution.training.data.PolicyVector;
import io.euhedral_execution.training.data.ScheduledPolicy;
import io.euhedral_execution.training.data.SourceRatio;
import io.euhedral_execution.training.data.SourceScenario;
import io.euhedral_execution.training.data.enums.EvidenceOrigin;
import io.euhedral_execution.training.data.enums.MeasurementEncoding;
import io.euhedral_execution.training.data.enums.ObservationStatus;
import io.euhedral_execution.training.data.enums.PolicyRole;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.OptionalLong;
import java.util.Set;

public final class ObservationBundleReader {

    private static final List<String> RUN_HEADER = List.of(
            "schema_version",
            "benchmark_run_id",
            "closed_loop_iteration",
            "candidate_cohort_id",
            "scenario_id",
            "environment_id",
            "source_count",
            "available_physical_core_count",
            "source_ratio_numerator",
            "source_ratio_denominator",
            "commit_sha",
            "dirty_working_tree",
            "evidence_origin",
            "started_at",
            "completed_at",
            "expected_repetitions",
            "sample_duration_nanos",
            "liveness_timeout_nanos",
            "frames_per_source",
            "reset_timeout_nanos",
            "ordered_frames",
            "cpu_set_hex",
            "frame_source_seeds");
    private static final List<String> OBSERVATION_HEADER = List.of(
            "schema_version",
            "observation_id",
            "policy_id",
            "repetition_number",
            "status",
            "measurement_encoding",
            "started_at",
            "ended_at",
            "elapsed_nanos",
            "completed_frames",
            "throughput_frames_per_second",
            "failure_code");

    public static String readRunId(Path directory) {
        try {
            if (!Files.isRegularFile(directory.resolve("COMPLETE")) || Files.size(directory.resolve("COMPLETE")) != 0) {
                throw new IllegalArgumentException("Bundle lacks COMPLETE");
            }
            List<List<String>> runRows = readCsv(directory.resolve("run.csv"));
            requireRows(runRows, 2, 23);
            if (!runRows.getFirst().equals(RUN_HEADER)) {
                throw new IllegalArgumentException("Run header");
            }
            return runRows.get(1).get(1);
        } catch (IOException error) {
            throw new IllegalStateException(error);
        }
    }

    public static ObservationBundle read(Path directory) {
        try {
            if (!Files.isRegularFile(directory.resolve("COMPLETE")) || Files.size(directory.resolve("COMPLETE")) != 0) {
                throw new IllegalArgumentException("Bundle lacks COMPLETE");
            }
            List<List<String>> runRows = readCsv(directory.resolve("run.csv"));
            requireRows(runRows, 2, 23);
            if (!runRows.getFirst().equals(RUN_HEADER)) {
                throw new IllegalArgumentException("Run header");
            }
            List<String> r = runRows.get(1);
            requireVersion(r.get(0));
            SourceScenario scenario = new SourceScenario(
                    r.get(5),
                    integer(r.get(6)),
                    integer(r.get(7)),
                    new SourceRatio(integer(r.get(8)), integer(r.get(9))));
            if (!scenario.canonical().equals(r.get(4))) {
                throw new IllegalArgumentException("Scenario ID mismatch");
            }
            List<FrameSourceSeed> seeds = new ArrayList<>();
            if (!r.get(22).isEmpty()) {
                for (String item : r.get(22).split(";")) {
                    String[] parts = item.split(":");
                    if (parts.length != 3) {
                        throw new IllegalArgumentException("Malformed source seed");
                    }
                    seeds.add(new FrameSourceSeed(integer(parts[0]), unsignedHex(parts[1]), unsignedHex(parts[2])));
                }
            }
            BenchmarkParameters parameters = new BenchmarkParameters(
                    integer(r.get(15)),
                    number(r.get(16)),
                    number(r.get(17)),
                    integer(r.get(18)),
                    number(r.get(19)),
                    bool(r.get(20)),
                    r.get(21),
                    seeds);
            BenchmarkRunDescriptor descriptor = new BenchmarkRunDescriptor(
                    1,
                    r.get(1),
                    integer(r.get(2)),
                    r.get(3),
                    scenario,
                    r.get(10),
                    bool(r.get(11)),
                    EvidenceOrigin.valueOf(r.get(12)),
                    instant(r.get(13)),
                    parameters);
            BenchmarkRunContext context = new BenchmarkRunContext(descriptor, instant(r.get(14)));

            List<List<String>> policyRows = readCsv(directory.resolve("policies.csv"));
            if (policyRows.size() < 2 || policyRows.getFirst().size() != 32) {
                throw new IllegalArgumentException("Invalid policies CSV");
            }
            List<String> policyHeader =
                    new ArrayList<>(List.of("schema_version", "schedule_position", "policy_id", "roles"));
            for (int i = 0; i < PolicyVector.WIDTH; i++) {
                policyHeader.add(String.format("weight_%02d_bits", i));
            }
            if (!policyRows.getFirst().equals(policyHeader)) {
                throw new IllegalArgumentException("Policy header");
            }
            List<ScheduledPolicy> policies = new ArrayList<>();
            Map<PolicyId, ScheduledPolicy> policiesById = new HashMap<>();
            PolicyRegistry registry = new PolicyRegistry();
            Set<PolicyId> policyIds = new HashSet<>();
            for (int rowIndex = 1; rowIndex < policyRows.size(); rowIndex++) {
                List<String> row = policyRows.get(rowIndex);
                if (row.size() != 32) {
                    throw new IllegalArgumentException("Invalid policy row");
                }
                requireVersion(row.get(0));
                if (integer(row.get(1)) != rowIndex) {
                    throw new IllegalArgumentException("Schedule gap");
                }
                double[] weights = new double[PolicyVector.WIDTH];
                for (int i = 0; i < weights.length; i++) {
                    weights[i] = Double.longBitsToDouble(unsignedHex(row.get(i + 4)));
                }
                PolicyVector policy = registry.register(PolicyVector.of(weights));
                if (!policy.id().canonical().equals(row.get(2))) {
                    throw new IllegalArgumentException("Declared policy ID mismatch");
                }
                if (!policyIds.add(policy.id())) {
                    throw new IllegalArgumentException("Duplicate policy");
                }
                EnumSet<PolicyRole> roles = EnumSet.noneOf(PolicyRole.class);
                for (String role : row.get(3).split(";")) {
                    if (!roles.add(PolicyRole.valueOf(role))) {
                        throw new IllegalArgumentException("Duplicate policy role");
                    }
                }
                String canonicalRoles = roles.stream()
                        .map(Enum::name)
                        .sorted()
                        .reduce((left, right) -> left + ";" + right)
                        .orElseThrow();
                if (!canonicalRoles.equals(row.get(3))) {
                    throw new IllegalArgumentException("Policy roles are not sorted");
                }
                ScheduledPolicy scheduled = new ScheduledPolicy(rowIndex, policy, roles);
                policies.add(scheduled);
                policiesById.put(policy.id(), scheduled);
            }

            List<List<String>> observationRows = readCsv(directory.resolve("observations.csv"));
            if (observationRows.isEmpty() || !observationRows.getFirst().equals(OBSERVATION_HEADER)) {
                throw new IllegalArgumentException("Observation header");
            }
            List<BenchmarkObservation> observations = new ArrayList<>();
            Set<ObservationKey> keys = new HashSet<>();
            for (int rowIndex = 1; rowIndex < observationRows.size(); rowIndex++) {
                List<String> row = observationRows.get(rowIndex);
                if (row.size() != 12) {
                    throw new IllegalArgumentException("Invalid observation row");
                }
                requireVersion(row.get(0));
                PolicyId policyId = PolicyId.parse(row.get(2));
                ScheduledPolicy policy = policiesById.get(policyId);
                if (policy == null) {
                    throw new IllegalArgumentException("Observation references unknown policy");
                }
                ObservationKey key =
                        new ObservationKey(descriptor.benchmarkRunId(), scenario, policyId, integer(row.get(3)));
                if (!key.canonical().equals(row.get(1)) || !keys.add(key)) {
                    throw new IllegalArgumentException("Duplicate or mismatched observation ID");
                }
                observations.add(new BenchmarkObservation(
                        key,
                        descriptor,
                        policy,
                        ObservationStatus.valueOf(row.get(4)),
                        MeasurementEncoding.valueOf(row.get(5)),
                        instant(row.get(6)),
                        instant(row.get(7)),
                        optionalLong(row.get(8)),
                        optionalLong(row.get(9)),
                        optionalDouble(row.get(10)),
                        row.get(11)));
                if (observations.getLast().endedAt().isAfter(context.completedAt())) {
                    throw new IllegalArgumentException("Observation ends after run");
                }
            }
            if (observations.size() != policies.size() * parameters.expectedRepetitions()) {
                throw new IllegalArgumentException("Missing planned repetition");
            }
            for (int i = 0; i < observations.size(); i++) {
                int position = i / parameters.expectedRepetitions() + 1;
                int repetition = i % parameters.expectedRepetitions() + 1;
                BenchmarkObservation observation = observations.get(i);
                if (observation.scheduledPolicy().schedulePosition() != position
                        || observation.key().repetitionNumber() != repetition) {
                    throw new IllegalArgumentException("Observation order mismatch");
                }
            }
            return new ObservationBundle(context, policies, observations);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public static void stream(Path directory, ObservationVisitor visitor) {
        ObservationMetadata metadata = readMetadata(directory);
        visitor.onStart(metadata.run(), metadata.policies());
        Map<PolicyId, ScheduledPolicy> policyById = new HashMap<>();
        metadata.policies().forEach(policy -> policyById.put(policy.policy().id(), policy));
        Set<ObservationKey> keys = new HashSet<>();
        int observationCount = 0;
        validateLfFile(directory.resolve("observations.csv"));
        try (BufferedReader reader =
                Files.newBufferedReader(directory.resolve("observations.csv"), StandardCharsets.UTF_8)) {
            String header = reader.readLine();
            if (header == null || !StrictCsv.parse(header + "\n").getFirst().equals(OBSERVATION_HEADER)) {
                throw new IllegalArgumentException("Observation header");
            }
            String line;
            while ((line = reader.readLine()) != null) {
                List<List<String>> parsed = StrictCsv.parse(line + "\n");
                if (parsed.size() != 1 || parsed.getFirst().size() != 12) {
                    throw new IllegalArgumentException("Invalid observation row");
                }
                List<String> row = parsed.getFirst();
                requireVersion(row.get(0));
                PolicyId policyId = PolicyId.parse(row.get(2));
                ScheduledPolicy policy = policyById.get(policyId);
                if (policy == null) {
                    throw new IllegalArgumentException("Unknown observation policy");
                }
                ObservationKey key = new ObservationKey(
                        metadata.run().descriptor().benchmarkRunId(),
                        metadata.run().descriptor().scenario(),
                        policyId,
                        integer(row.get(3)));
                if (!key.canonical().equals(row.get(1)) || !keys.add(key)) {
                    throw new IllegalArgumentException("Duplicate or mismatched observation ID");
                }
                int expectedPosition = observationCount
                                / metadata.run().descriptor().parameters().expectedRepetitions()
                        + 1;
                int expectedRepetition = observationCount
                                % metadata.run().descriptor().parameters().expectedRepetitions()
                        + 1;
                if (policy.schedulePosition() != expectedPosition || key.repetitionNumber() != expectedRepetition) {
                    throw new IllegalArgumentException("Observation order mismatch");
                }
                BenchmarkObservation observation = new BenchmarkObservation(
                        key,
                        metadata.run().descriptor(),
                        policy,
                        ObservationStatus.valueOf(row.get(4)),
                        MeasurementEncoding.valueOf(row.get(5)),
                        instant(row.get(6)),
                        instant(row.get(7)),
                        optionalLong(row.get(8)),
                        optionalLong(row.get(9)),
                        optionalDouble(row.get(10)),
                        row.get(11));
                if (observation.endedAt().isAfter(metadata.run().completedAt())) {
                    throw new IllegalArgumentException("Observation ends after run");
                }
                visitor.onObservation(observation);
                observationCount++;
            }
        } catch (IOException error) {
            throw new IllegalStateException(error);
        }
        int expectedCount = metadata.policies().size()
                * metadata.run().descriptor().parameters().expectedRepetitions();
        if (observationCount != expectedCount) {
            throw new IllegalArgumentException("Missing planned repetition");
        }
    }

    private static ObservationMetadata readMetadata(Path directory) {
        try {
            if (!Files.isRegularFile(directory.resolve("COMPLETE")) || Files.size(directory.resolve("COMPLETE")) != 0) {
                throw new IllegalArgumentException("Bundle lacks COMPLETE");
            }
            List<List<String>> runRows = readCsv(directory.resolve("run.csv"));
            requireRows(runRows, 2, 23);
            if (!runRows.getFirst().equals(RUN_HEADER)) {
                throw new IllegalArgumentException("Run header");
            }
            List<String> row = runRows.get(1);
            requireVersion(row.get(0));
            SourceScenario scenario = new SourceScenario(
                    row.get(5),
                    integer(row.get(6)),
                    integer(row.get(7)),
                    new SourceRatio(integer(row.get(8)), integer(row.get(9))));
            if (!scenario.canonical().equals(row.get(4))) {
                throw new IllegalArgumentException("Scenario ID mismatch");
            }
            List<FrameSourceSeed> seeds = new ArrayList<>();
            if (!row.get(22).isEmpty()) {
                for (String item : row.get(22).split(";")) {
                    String[] parts = item.split(":");
                    if (parts.length != 3) {
                        throw new IllegalArgumentException("Malformed source seed");
                    }
                    seeds.add(new FrameSourceSeed(integer(parts[0]), unsignedHex(parts[1]), unsignedHex(parts[2])));
                }
            }
            BenchmarkParameters parameters = new BenchmarkParameters(
                    integer(row.get(15)),
                    number(row.get(16)),
                    number(row.get(17)),
                    integer(row.get(18)),
                    number(row.get(19)),
                    bool(row.get(20)),
                    row.get(21),
                    seeds);
            BenchmarkRunDescriptor descriptor = new BenchmarkRunDescriptor(
                    1,
                    row.get(1),
                    integer(row.get(2)),
                    row.get(3),
                    scenario,
                    row.get(10),
                    bool(row.get(11)),
                    EvidenceOrigin.valueOf(row.get(12)),
                    instant(row.get(13)),
                    parameters);
            BenchmarkRunContext context = new BenchmarkRunContext(descriptor, instant(row.get(14)));

            List<List<String>> policyRows = readCsv(directory.resolve("policies.csv"));
            List<String> expectedHeader =
                    new ArrayList<>(List.of("schema_version", "schedule_position", "policy_id", "roles"));
            for (int i = 0; i < PolicyVector.WIDTH; i++) {
                expectedHeader.add(String.format("weight_%02d_bits", i));
            }
            if (policyRows.size() < 2 || !policyRows.getFirst().equals(expectedHeader)) {
                throw new IllegalArgumentException("Invalid policies CSV");
            }
            List<ScheduledPolicy> policies = new ArrayList<>();
            PolicyRegistry registry = new PolicyRegistry();
            Set<PolicyId> policyIds = new HashSet<>();
            for (int index = 1; index < policyRows.size(); index++) {
                List<String> policyRow = policyRows.get(index);
                if (policyRow.size() != 32) {
                    throw new IllegalArgumentException("Invalid policy row");
                }
                requireVersion(policyRow.get(0));
                if (integer(policyRow.get(1)) != index) {
                    throw new IllegalArgumentException("Schedule gap");
                }
                double[] weights = new double[PolicyVector.WIDTH];
                for (int weight = 0; weight < weights.length; weight++) {
                    weights[weight] = Double.longBitsToDouble(unsignedHex(policyRow.get(weight + 4)));
                }
                PolicyVector policy = registry.register(PolicyVector.of(weights));
                if (!policy.id().canonical().equals(policyRow.get(2)) || !policyIds.add(policy.id())) {
                    throw new IllegalArgumentException("Declared or duplicate policy ID");
                }
                EnumSet<PolicyRole> roles = EnumSet.noneOf(PolicyRole.class);
                for (String role : policyRow.get(3).split(";")) {
                    if (!roles.add(PolicyRole.valueOf(role))) {
                        throw new IllegalArgumentException("Duplicate policy role");
                    }
                }
                String canonicalRoles = roles.stream()
                        .map(Enum::name)
                        .sorted()
                        .reduce((left, right) -> left + ";" + right)
                        .orElseThrow();
                if (!canonicalRoles.equals(policyRow.get(3))) {
                    throw new IllegalArgumentException("Policy roles are not sorted");
                }
                policies.add(new ScheduledPolicy(index, policy, roles));
            }
            return new ObservationMetadata(context, List.copyOf(policies));
        } catch (IOException error) {
            throw new IllegalStateException(error);
        }
    }

    private static List<List<String>> readCsv(Path path) throws IOException {
        return StrictCsv.parse(Files.readString(path, StandardCharsets.UTF_8));
    }

    private static void requireRows(List<List<String>> rows, int count, int width) {
        if (rows.size() != count || rows.stream().anyMatch(row -> row.size() != width)) {
            throw new IllegalArgumentException("Invalid CSV shape");
        }
    }

    private static void requireVersion(String value) {
        if (!"1".equals(value)) {
            throw new IllegalArgumentException("Unsupported schema");
        }
    }

    private static int integer(String value) {
        return Integer.parseInt(value);
    }

    private static long number(String value) {
        return Long.parseLong(value);
    }

    private static boolean bool(String value) {
        if (!value.equals("true") && !value.equals("false")) {
            throw new IllegalArgumentException();
        }
        return Boolean.parseBoolean(value);
    }

    private static long unsignedHex(String value) {
        if (!value.matches("[0-9a-f]{16}")) {
            throw new IllegalArgumentException("Malformed hex");
        }
        return Long.parseUnsignedLong(value, 16);
    }

    private static OptionalLong optionalLong(String value) {
        return value.isEmpty() ? OptionalLong.empty() : OptionalLong.of(number(value));
    }

    private static OptionalDouble optionalDouble(String value) {
        return value.isEmpty() ? OptionalDouble.empty() : OptionalDouble.of(Double.parseDouble(value));
    }

    private static Instant instant(String value) {
        Instant parsed = Instant.parse(value);
        if (!parsed.toString().equals(value)) {
            throw new IllegalArgumentException("Instant is not canonical UTC text");
        }
        return parsed;
    }

    private static void validateLfFile(Path path) {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
            ByteBuffer buffer = ByteBuffer.allocate(4096);
            int last = -1;
            int position = 0;
            while (channel.read(buffer) >= 0) {
                buffer.flip();
                while (buffer.hasRemaining()) {
                    int value = buffer.get() & 0xff;
                    if (position == 0 && value == 0xef) {
                        throw new IllegalArgumentException("CSV byte-order mark is not allowed");
                    }
                    if (value == '\r') {
                        throw new IllegalArgumentException("CSV must use LF line endings");
                    }
                    last = value;
                    position++;
                }
                buffer.clear();
            }
            if (last != '\n') {
                throw new IllegalArgumentException("CSV must end with LF");
            }
        } catch (IOException error) {
            throw new IllegalStateException(error);
        }
    }

    private ObservationBundleReader() {}

    public interface ObservationVisitor {

        void onStart(BenchmarkRunContext run, List<ScheduledPolicy> policies);

        void onObservation(BenchmarkObservation observation);
    }

    private record ObservationMetadata(BenchmarkRunContext run, List<ScheduledPolicy> policies) {}
}
