package io.euhedral_execution.training.packaging;

import io.euhedral_execution.training.checkpoint.enums.CheckpointStage;
import io.euhedral_execution.training.data.SourceRatio;
import io.euhedral_execution.training.data.SourceScenario;
import io.euhedral_execution.training.merge.enums.CalibrationAcceptance;
import io.euhedral_execution.training.packaging.enums.ArtifactOrigin;
import io.euhedral_execution.training.packaging.enums.ArtifactSemanticType;
import io.euhedral_execution.training.packaging.enums.ProducingStage;
import io.euhedral_execution.training.packaging.enums.TrainingRunPackageStatus;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class PackageManifestCodec {
    private static final List<String> ROOT_KEYS = List.of("artifact_type", "schema_version",
            "package_id", "training_run_id", "checkpoint_revision", "checkpoint_stage",
            "status", "run_complete", "config_sha256", "checkpoint_sha256", "producer",
            "required_scenarios", "coverage_rule", "calibration_acceptance",
            "winning_policy_ids", "files", "omissions");

    static String encode(TrainingRunManifest manifest) {
        StringBuilder out = new StringBuilder();
        out.append("{\n");
        field(out, 1, "artifact_type", "euhedral-training-run-package", true);
        field(out, 1, "schema_version", 1, true);
        field(out, 1, "package_id", manifest.packageId(), true);
        field(out, 1, "training_run_id", manifest.trainingRunId(), true);
        field(out, 1, "checkpoint_revision", manifest.checkpointRevision(), true);
        field(out, 1, "checkpoint_stage", manifest.checkpointStage().name(), true);
        field(out, 1, "status", manifest.status().name(), true);
        field(out, 1, "run_complete", manifest.runComplete(), true);
        field(out, 1, "config_sha256", manifest.configSha256(), true);
        field(out, 1, "checkpoint_sha256", manifest.checkpointSha256(), true);
        indent(out, 1).append("\"producer\": {\n");
        field(out, 2, "commit_sha", manifest.commitSha(), true);
        field(out, 2, "dirty_working_tree", manifest.dirtyWorkingTree(), false);
        indent(out, 1).append("},\n");
        indent(out, 1).append("\"required_scenarios\": [");
        if (!manifest.requiredScenarios().isEmpty()) out.append('\n');
        for (int index = 0; index < manifest.requiredScenarios().size(); index++) {
            SourceScenario scenario = manifest.requiredScenarios().get(index);
            indent(out, 2).append("{\n");
            field(out, 3, "scenario_id", scenario.canonical(), true);
            field(out, 3, "environment_id", scenario.environmentId(), true);
            field(out, 3, "source_count", scenario.sourceCount(), true);
            field(out, 3, "available_physical_core_count",
                    scenario.availablePhysicalCoreCount(), true);
            field(out, 3, "source_ratio_numerator", scenario.ratio().numerator(), true);
            field(out, 3, "source_ratio_denominator", scenario.ratio().denominator(), false);
            indent(out, 2).append('}');
            out.append(index + 1 == manifest.requiredScenarios().size() ? '\n' : ",\n");
        }
        indent(out, 1).append("],\n");
        field(out, 1, "coverage_rule", "all-required-scenarios-valid-v1", true);
        fieldNullable(out, 1, "calibration_acceptance",
                manifest.calibrationAcceptance() == null ? null
                        : manifest.calibrationAcceptance().name(), true);
        stringArray(out, 1, "winning_policy_ids", manifest.winningPolicyIds(), true);
        indent(out, 1).append("\"files\": [");
        if (!manifest.files().isEmpty()) out.append('\n');
        for (int index = 0; index < manifest.files().size(); index++) {
            PackageFile file = manifest.files().get(index);
            indent(out, 2).append("{\n");
            field(out, 3, "path", file.path(), true);
            field(out, 3, "semantic_type", file.semanticType().name(), true);
            field(out, 3, "media_type", file.mediaType(), true);
            fieldNullable(out, 3, "schema_version", file.schemaVersion(), true);
            fieldNullable(out, 3, "row_count", file.rowCount(), true);
            field(out, 3, "sha256", file.sha256(), true);
            field(out, 3, "producing_stage", file.producingStage().name(), true);
            stringArray(out, 3, "source_run_ids", file.sourceRunIds(), true);
            field(out, 3, "origin", file.origin().name(), true);
            field(out, 3, "complete", file.complete(), false);
            indent(out, 2).append('}');
            out.append(index + 1 == manifest.files().size() ? '\n' : ",\n");
        }
        indent(out, 1).append("],\n");
        indent(out, 1).append("\"omissions\": [");
        if (!manifest.omissions().isEmpty()) out.append('\n');
        for (int index = 0; index < manifest.omissions().size(); index++) {
            PackageOmission omission = manifest.omissions().get(index);
            indent(out, 2).append("{\n");
            field(out, 3, "semantic_group", omission.semanticGroup(), true);
            field(out, 3, "reason", omission.reason(), true);
            field(out, 3, "required_for_complete_run",
                    omission.requiredForCompleteRun(), false);
            indent(out, 2).append('}');
            out.append(index + 1 == manifest.omissions().size() ? '\n' : ",\n");
        }
        indent(out, 1).append("]\n}\n");
        return out.toString();
    }

    static TrainingRunManifest read(Path path) throws IOException {
        byte[] bytes = Files.readAllBytes(path);
        String text = new String(bytes, StandardCharsets.UTF_8);
        if (!java.util.Arrays.equals(bytes, text.getBytes(StandardCharsets.UTF_8))
                || text.startsWith("\ufeff") || text.indexOf('\r') >= 0
                || !text.endsWith("\n")) {
            throw new IOException("Manifest is not canonical UTF-8/LF");
        }
        Object parsed;
        try {
            parsed = new Parser(text).parse();
        } catch (RuntimeException error) {
            throw new IOException("Malformed manifest JSON", error);
        }
        try {
            Map<String, Object> root = object(parsed);
            requireKeys(root, ROOT_KEYS);
            require(string(root, "artifact_type").equals("euhedral-training-run-package"));
            require(integer(root, "schema_version") == 1);
            Map<String, Object> producer = object(root.get("producer"));
            requireKeys(producer, List.of("commit_sha", "dirty_working_tree"));
            ArrayList<SourceScenario> scenarios = new ArrayList<>();
            for (Object item : array(root, "required_scenarios")) {
                Map<String, Object> value = object(item);
                requireKeys(value, List.of("scenario_id", "environment_id", "source_count",
                        "available_physical_core_count", "source_ratio_numerator",
                        "source_ratio_denominator"));
                SourceScenario scenario = new SourceScenario(string(value, "environment_id"),
                        integer(value, "source_count"),
                        integer(value, "available_physical_core_count"),
                        new SourceRatio(integer(value, "source_ratio_numerator"),
                                integer(value, "source_ratio_denominator")));
                require(scenario.canonical().equals(string(value, "scenario_id")));
                scenarios.add(scenario);
            }
            ArrayList<PackageFile> files = new ArrayList<>();
            for (Object item : array(root, "files")) {
                Map<String, Object> value = object(item);
                requireKeys(value, List.of("path", "semantic_type", "media_type",
                        "schema_version", "row_count", "sha256", "producing_stage",
                        "source_run_ids", "origin", "complete"));
                files.add(new PackageFile(string(value, "path"),
                        ArtifactSemanticType.valueOf(string(value, "semantic_type")),
                        string(value, "media_type"), nullableInteger(value.get("schema_version")),
                        nullableLong(value.get("row_count")), string(value, "sha256"),
                        ProducingStage.valueOf(string(value, "producing_stage")),
                        strings(value, "source_run_ids"),
                        ArtifactOrigin.valueOf(string(value, "origin")),
                        bool(value, "complete")));
            }
            ArrayList<PackageOmission> omissions = new ArrayList<>();
            for (Object item : array(root, "omissions")) {
                Map<String, Object> value = object(item);
                requireKeys(value, List.of("semantic_group", "reason",
                        "required_for_complete_run"));
                omissions.add(new PackageOmission(string(value, "semantic_group"),
                        string(value, "reason"), bool(value, "required_for_complete_run")));
            }
            require(string(root, "coverage_rule")
                    .equals("all-required-scenarios-valid-v1"));
            Object calibration = root.get("calibration_acceptance");
            TrainingRunManifest result = new TrainingRunManifest(string(root, "package_id"),
                    string(root, "training_run_id"), integer(root, "checkpoint_revision"),
                    CheckpointStage.valueOf(string(root, "checkpoint_stage")),
                    TrainingRunPackageStatus.valueOf(string(root, "status")),
                    bool(root, "run_complete"), string(root, "config_sha256"),
                    string(root, "checkpoint_sha256"), string(producer, "commit_sha"),
                    bool(producer, "dirty_working_tree"), scenarios,
                    calibration == null ? null
                            : CalibrationAcceptance.valueOf((String) calibration),
                    strings(root, "winning_policy_ids"), files, omissions);
            require(encode(result).equals(text));
            return result;
        } catch (RuntimeException error) {
            throw new IOException("Invalid or noncanonical manifest", error);
        }
    }

    private static void field(StringBuilder out, int level, String name, Object value,
            boolean comma) {
        indent(out, level).append(quote(name)).append(": ");
        appendValue(out, value);
        out.append(comma ? ",\n" : "\n");
    }

    private static void fieldNullable(StringBuilder out, int level, String name, Object value,
            boolean comma) {
        field(out, level, name, value, comma);
    }

    private static void stringArray(StringBuilder out, int level, String name,
            List<String> values, boolean comma) {
        indent(out, level).append(quote(name)).append(": [");
        for (int index = 0; index < values.size(); index++) {
            if (index > 0) out.append(", ");
            out.append(quote(values.get(index)));
        }
        out.append(']').append(comma ? ",\n" : "\n");
    }

    private static void appendValue(StringBuilder out, Object value) {
        if (value == null) out.append("null");
        else if (value instanceof String string) out.append(quote(string));
        else out.append(value);
    }

    private static String quote(String value) {
        StringBuilder out = new StringBuilder("\"");
        for (int index = 0; index < value.length(); index++) {
            char ch = value.charAt(index);
            switch (ch) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (ch < 0x20) out.append("\\u%04x".formatted((int) ch));
                    else out.append(ch);
                }
            }
        }
        return out.append('"').toString();
    }

    private static StringBuilder indent(StringBuilder out, int level) {
        return out.append("  ".repeat(level));
    }

    private static Map<String, Object> object(Object value) {
        if (!(value instanceof Map<?, ?> map)) throw new IllegalArgumentException();
        @SuppressWarnings("unchecked") Map<String, Object> result = (Map<String, Object>) map;
        return result;
    }
    private static List<Object> array(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (!(value instanceof List<?> list)) throw new IllegalArgumentException();
        return new ArrayList<>(list);
    }
    private static String string(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (!(value instanceof String string)) throw new IllegalArgumentException();
        return string;
    }
    private static boolean bool(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (!(value instanceof Boolean bool)) throw new IllegalArgumentException();
        return bool;
    }
    private static int integer(Map<String, Object> map, String key) {
        return Math.toIntExact(number(map.get(key)));
    }
    private static Integer nullableInteger(Object value) {
        return value == null ? null : Math.toIntExact(number(value));
    }
    private static Long nullableLong(Object value) {
        return value == null ? null : number(value);
    }
    private static long number(Object value) {
        if (!(value instanceof Long number)) throw new IllegalArgumentException();
        return number;
    }
    private static List<String> strings(Map<String, Object> map, String key) {
        ArrayList<String> result = new ArrayList<>();
        for (Object value : array(map, key)) {
            if (!(value instanceof String string)) throw new IllegalArgumentException();
            result.add(string);
        }
        return List.copyOf(result);
    }
    private static void requireKeys(Map<String, Object> map, List<String> keys) {
        require(new ArrayList<>(map.keySet()).equals(keys));
    }
    private static void require(boolean condition) {
        if (!condition) throw new IllegalArgumentException();
    }

    private static final class Parser {
        private final String input;
        private int cursor;
        Parser(String input) { this.input = input; }
        Object parse() {
            Object value = value();
            whitespace();
            if (cursor != input.length()) throw new IllegalArgumentException();
            return value;
        }
        private Object value() {
            whitespace();
            if (cursor >= input.length()) throw new IllegalArgumentException();
            return switch (input.charAt(cursor)) {
                case '{' -> object();
                case '[' -> array();
                case '"' -> string();
                case 't' -> literal("true", Boolean.TRUE);
                case 'f' -> literal("false", Boolean.FALSE);
                case 'n' -> literal("null", null);
                default -> number();
            };
        }
        private Map<String, Object> object() {
            cursor++;
            LinkedHashMap<String, Object> result = new LinkedHashMap<>();
            whitespace();
            if (take('}')) return result;
            while (true) {
                whitespace();
                String key = string();
                whitespace();
                expect(':');
                if (result.containsKey(key)) throw new IllegalArgumentException();
                result.put(key, value());
                whitespace();
                if (take('}')) return result;
                expect(',');
            }
        }
        private List<Object> array() {
            cursor++;
            ArrayList<Object> result = new ArrayList<>();
            whitespace();
            if (take(']')) return result;
            while (true) {
                result.add(value());
                whitespace();
                if (take(']')) return result;
                expect(',');
            }
        }
        private String string() {
            expect('"');
            StringBuilder out = new StringBuilder();
            while (cursor < input.length()) {
                char ch = input.charAt(cursor++);
                if (ch == '"') return out.toString();
                if (ch == '\\') {
                    if (cursor >= input.length()) throw new IllegalArgumentException();
                    char escape = input.charAt(cursor++);
                    switch (escape) {
                        case '"' -> out.append('"');
                        case '\\' -> out.append('\\');
                        case '/' -> out.append('/');
                        case 'b' -> out.append('\b');
                        case 'f' -> out.append('\f');
                        case 'n' -> out.append('\n');
                        case 'r' -> out.append('\r');
                        case 't' -> out.append('\t');
                        case 'u' -> {
                            if (cursor + 4 > input.length()) throw new IllegalArgumentException();
                            out.append((char) Integer.parseInt(
                                    input.substring(cursor, cursor + 4), 16));
                            cursor += 4;
                        }
                        default -> throw new IllegalArgumentException();
                    }
                } else {
                    if (ch < 0x20) throw new IllegalArgumentException();
                    out.append(ch);
                }
            }
            throw new IllegalArgumentException();
        }
        private Long number() {
            int start = cursor;
            if (take('-')) throw new IllegalArgumentException();
            while (cursor < input.length()
                    && Character.isDigit(input.charAt(cursor))) cursor++;
            if (start == cursor) throw new IllegalArgumentException();
            return Long.parseLong(input.substring(start, cursor));
        }
        private Object literal(String token, Object value) {
            if (!input.startsWith(token, cursor)) throw new IllegalArgumentException();
            cursor += token.length();
            return value;
        }
        private void whitespace() {
            while (cursor < input.length() && " \n\r\t".indexOf(input.charAt(cursor)) >= 0) {
                cursor++;
            }
        }
        private boolean take(char expected) {
            if (cursor < input.length() && input.charAt(cursor) == expected) {
                cursor++;
                return true;
            }
            return false;
        }
        private void expect(char expected) {
            if (!take(expected)) throw new IllegalArgumentException();
        }
    }

    private PackageManifestCodec() {
    }
}
