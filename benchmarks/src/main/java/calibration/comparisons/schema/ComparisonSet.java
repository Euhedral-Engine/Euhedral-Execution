package calibration.comparisons.schema;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/// Represents a named or anonymous set of completed calibration runs.
@JsonDeserialize(using = ComparisonSet.Deserializer.class)
public record ComparisonSet(@Nullable String id, @NonNull List<RunReference> runs) {

    public ComparisonSet {
        runs = runs != null ? List.copyOf(runs) : List.of();
    }

    public static ComparisonSet ofRuns(@NonNull List<RunReference> runs) {
        return new ComparisonSet(null, runs);
    }

    public static ComparisonSet ofSingle(@NonNull RunReference run) {
        return new ComparisonSet(null, List.of(run));
    }

    public static ComparisonSet of(@Nullable String id, @NonNull List<RunReference> runs) {
        return new ComparisonSet(id, runs);
    }

    public static final class Deserializer extends JsonDeserializer<ComparisonSet> {
        @Override
        public ComparisonSet deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
            JsonNode node = p.getCodec().readTree(p);
            if (node == null || node.isNull()) {
                return null;
            }

            if (node.isTextual()) {
                return ComparisonSet.ofSingle(RunReference.of(node.asText()));
            }

            if (node.isArray()) {
                List<RunReference> runs = new ArrayList<>();
                for (JsonNode elem : node) {
                    if (elem.isTextual()) {
                        runs.add(RunReference.of(elem.asText()));
                    } else {
                        runs.add(p.getCodec().treeToValue(elem, RunReference.class));
                    }
                }
                return ComparisonSet.ofRuns(runs);
            }

            if (node.isObject()) {
                String id = node.hasNonNull("id") ? node.get("id").asText() : null;
                if (node.has("runs")) {
                    JsonNode runsNode = node.get("runs");
                    List<RunReference> runs = new ArrayList<>();
                    if (runsNode.isArray()) {
                        for (JsonNode elem : runsNode) {
                            if (elem.isTextual()) {
                                runs.add(RunReference.of(elem.asText()));
                            } else {
                                runs.add(p.getCodec().treeToValue(elem, RunReference.class));
                            }
                        }
                    }
                    return new ComparisonSet(id, runs);
                } else if (node.has("path")) {
                    RunReference ref = p.getCodec().treeToValue(node, RunReference.class);
                    return ComparisonSet.ofSingle(ref);
                }
            }

            throw new IllegalArgumentException("Unsupported JSON structure for ComparisonSet: " + node);
        }
    }
}
