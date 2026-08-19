package calibration.comparisons;

import calibration.comparisons.schema.CompletedRun;
import calibration.config.ComparisonKeyConfig;
import com.fasterxml.jackson.core.JsonPointer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.NonNull;

/// Extracts typed ComparisonKey instances from resolved TrialConfig configurations using JSON Pointer paths.
public final class ComparisonKeyExtractor {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private ComparisonKeyExtractor() {}

    /// Extracts a ComparisonKey from the given completed calibration run.
    ///
    /// @throws IllegalArgumentException if pointer is invalid, missing, or resolves to null / non-scalar
    public static @NonNull ComparisonKey extract(@NonNull CompletedRun run, @NonNull ComparisonKeyConfig keyConfig) {
        Objects.requireNonNull(run, "run must not be null");
        Objects.requireNonNull(keyConfig, "keyConfig must not be null");

        JsonNode rootNode = OBJECT_MAPPER.valueToTree(run.trialConfig());
        List<ComparisonKeyValue> values = new ArrayList<>(keyConfig.paths().size());

        for (String path : keyConfig.paths()) {
            JsonPointer pointer;
            try {
                pointer = JsonPointer.compile(path);
            } catch (Exception e) {
                throw new IllegalArgumentException(
                        "Invalid JSON pointer path '" + path + "' for run '"
                                + run.identity().trialId() + "' ("
                                + run.identity().sourcePath() + ")",
                        e);
            }

            JsonNode targetNode = rootNode.at(pointer);
            if (targetNode.isMissingNode()) {
                throw new IllegalArgumentException("Configured key path '" + path + "' does not exist in run '"
                        + run.identity().trialId() + "' (" + run.identity().sourcePath() + ")");
            }

            if (targetNode.isNull()) {
                throw new IllegalArgumentException("Key value at path '" + path + "' is null in run '"
                        + run.identity().trialId() + "' (" + run.identity().sourcePath() + ")");
            }

            if (targetNode.isIntegralNumber()) {
                values.add(ComparisonKeyValue.of(targetNode.longValue()));
            } else if (targetNode.isFloatingPointNumber()) {
                values.add(ComparisonKeyValue.of(targetNode.doubleValue()));
            } else if (targetNode.isTextual()) {
                values.add(ComparisonKeyValue.of(targetNode.asText()));
            } else if (targetNode.isBoolean()) {
                values.add(ComparisonKeyValue.of(targetNode.asBoolean()));
            } else {
                throw new IllegalArgumentException("Key value at path '" + path + "' in run '"
                        + run.identity().trialId() + "' (" + run.identity().sourcePath()
                        + ") cannot be represented as a scalar key value (node type: "
                        + targetNode.getNodeType() + ")");
            }
        }

        return new ComparisonKey(values);
    }
}
