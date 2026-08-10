package io.euhedral_execution.training.packaging.io;

import io.euhedral_execution.training.benchmark.config.BenchmarkExecutionConfig;
import io.euhedral_execution.training.data.SourceScenario;
import io.euhedral_execution.training.packaging.config.TrainingRunPackageInputs;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

public final class TrainingRunPackageInputsCodec {
    private static final List<String> KEYS = List.of(
            "schema_version",
            "artifact_type",
            "package_id",
            "training_run_id",
            "checkpoint_revision",
            "scheduler_seed_hex",
            "commit_sha",
            "dirty_working_tree",
            "expected_repetitions",
            "sample_duration_nanos",
            "liveness_timeout_nanos",
            "frames_per_source",
            "reset_timeout_nanos",
            "ordered_frames");

    private TrainingRunPackageInputsCodec() {}

    public static String encode(TrainingRunPackageInputs inputs) {
        BenchmarkExecutionConfig config = inputs.benchmarkConfig();
        StringBuilder out = new StringBuilder();
        line(out, "schema_version", "1");
        line(out, "artifact_type", "euhedral-training-run-package-inputs");
        line(out, "package_id", inputs.packageId());
        line(out, "training_run_id", inputs.trainingRunId());
        line(out, "checkpoint_revision", Integer.toString(inputs.checkpointRevision()));
        line(out, "scheduler_seed_hex", "%016x".formatted(inputs.schedulerSeed()));
        line(out, "commit_sha", inputs.commitSha());
        line(out, "dirty_working_tree", Boolean.toString(inputs.dirtyWorkingTree()));
        line(out, "expected_repetitions", Integer.toString(config.expectedRepetitions()));
        line(out, "sample_duration_nanos", Long.toString(config.sampleDurationNanos()));
        line(out, "liveness_timeout_nanos", Long.toString(config.livenessTimeoutNanos()));
        line(out, "frames_per_source", Integer.toString(config.framesPerSource()));
        line(out, "reset_timeout_nanos", Long.toString(config.resetTimeoutNanos()));
        line(out, "ordered_frames", Boolean.toString(config.orderedFrames()));
        for (SourceScenario scenario : inputs.requiredScenarios()) {
            line(out, "required_scenario", scenario.canonical());
        }
        return out.toString();
    }

    public static TrainingRunPackageInputs read(Path path) throws IOException {
        byte[] bytes = Files.readAllBytes(path);
        if (bytes.length == 0
                || bytes[bytes.length - 1] != '\n'
                || bytes.length >= 3 && bytes[0] == (byte) 0xef && bytes[1] == (byte) 0xbb && bytes[2] == (byte) 0xbf) {
            throw new IOException("Package inputs are not canonical UTF-8/LF");
        }
        String text = new String(bytes, StandardCharsets.UTF_8);
        if (text.indexOf('\r') >= 0 || !java.util.Arrays.equals(bytes, text.getBytes(StandardCharsets.UTF_8))) {
            throw new IOException("Package inputs are not canonical UTF-8/LF");
        }
        String[] lines = text.substring(0, text.length() - 1).split("\n", -1);
        if (lines.length <= KEYS.size()) {
            throw new IOException("Missing required scenarios");
        }
        ArrayList<String> values = new ArrayList<>();
        for (int index = 0; index < KEYS.size(); index++) {
            String prefix = KEYS.get(index) + "=";
            if (!lines[index].startsWith(prefix) || lines[index].indexOf('=', prefix.length()) >= 0) {
                throw new IOException("Out-of-order or malformed package input key");
            }
            values.add(lines[index].substring(prefix.length()));
        }
        TreeSet<SourceScenario> scenarios = new TreeSet<>();
        SourceScenario previous = null;
        for (int index = KEYS.size(); index < lines.length; index++) {
            String prefix = "required_scenario=";
            if (!lines[index].startsWith(prefix)) {
                throw new IOException("Unknown package input key");
            }
            SourceScenario scenario;
            try {
                scenario = SourceScenario.parse(lines[index].substring(prefix.length()));
            } catch (RuntimeException error) {
                throw new IOException("Invalid required scenario", error);
            }
            if (previous != null && scenario.compareTo(previous) <= 0 || !scenarios.add(scenario)) {
                throw new IOException("Required scenarios are not canonical");
            }
            previous = scenario;
        }
        if (!values.get(0).equals("1") || !values.get(1).equals("euhedral-training-run-package-inputs")) {
            throw new IOException("Unsupported package input schema");
        }
        try {
            long seed = parseHex(values.get(5));
            BenchmarkExecutionConfig config = new BenchmarkExecutionConfig(
                    Integer.parseInt(values.get(8)), Long.parseLong(values.get(9)),
                    Long.parseLong(values.get(10)), Integer.parseInt(values.get(11)),
                    Long.parseLong(values.get(12)), parseBoolean(values.get(13)));
            TrainingRunPackageInputs result = new TrainingRunPackageInputs(
                    values.get(2),
                    values.get(3),
                    Integer.parseInt(values.get(4)),
                    seed,
                    values.get(6),
                    parseBoolean(values.get(7)),
                    config,
                    scenarios);
            if (!encode(result).equals(text)) {
                throw new IOException("Package inputs are not canonically encoded");
            }
            return result;
        } catch (RuntimeException error) {
            throw new IOException("Invalid package inputs", error);
        }
    }

    private static void line(StringBuilder out, String key, String value) {
        if (value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0 || value.indexOf('=') >= 0) {
            throw new IllegalArgumentException("Unencodable package input value");
        }
        out.append(key).append('=').append(value).append('\n');
    }

    private static long parseHex(String value) {
        if (!value.matches("[0-9a-f]{16}")) {
            throw new IllegalArgumentException("Invalid scheduler seed");
        }
        return Long.parseUnsignedLong(value, 16);
    }

    private static boolean parseBoolean(String value) {
        if (!value.equals("true") && !value.equals("false")) {
            throw new IllegalArgumentException("Invalid boolean");
        }
        return Boolean.parseBoolean(value);
    }
}
