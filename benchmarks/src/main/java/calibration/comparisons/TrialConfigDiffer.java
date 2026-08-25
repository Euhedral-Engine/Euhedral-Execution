package calibration.comparisons;

import calibration.comparisons.schema.ConfigurationDifference;
import calibration.comparisons.schema.DifferenceCategory;
import calibration.config.TrialConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/// Structural differ for TrialConfig instances generating deterministic JSON Pointer differences.
public final class TrialConfigDiffer {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private TrialConfigDiffer() {}

    public static @NonNull List<ConfigurationDifference> diff(
            @NonNull TrialConfig baseline, @NonNull TrialConfig candidate) {
        Objects.requireNonNull(baseline, "baseline must not be null");
        Objects.requireNonNull(candidate, "candidate must not be null");

        JsonNode baseNode = OBJECT_MAPPER.valueToTree(baseline);
        JsonNode candNode = OBJECT_MAPPER.valueToTree(candidate);

        List<ConfigurationDifference> differences = new ArrayList<>();
        compareNodes("", baseNode, candNode, differences);

        differences.sort(Comparator.comparing(ConfigurationDifference::path));
        return List.copyOf(differences);
    }

    private static void compareNodes(
            String currentPath,
            @Nullable JsonNode base,
            @Nullable JsonNode cand,
            List<ConfigurationDifference> differences) {

        if (isNullOrMissing(base) && isNullOrMissing(cand)) {
            return;
        }

        if (isNullOrMissing(base)) {
            differences.add(new ConfigurationDifference(currentPath, null, cand, categorize(currentPath)));
            return;
        }

        if (isNullOrMissing(cand)) {
            differences.add(new ConfigurationDifference(currentPath, base, null, categorize(currentPath)));
            return;
        }

        if (base.equals(cand)) {
            return;
        }

        if (base.isObject() && cand.isObject()) {
            ObjectNode baseObj = (ObjectNode) base;
            ObjectNode candObj = (ObjectNode) cand;

            TreeSet<String> allKeys = new TreeSet<>();
            baseObj.fieldNames().forEachRemaining(allKeys::add);
            candObj.fieldNames().forEachRemaining(allKeys::add);

            for (String key : allKeys) {
                String childPath = currentPath + "/" + key;
                JsonNode childBase = baseObj.get(key);
                JsonNode childCand = candObj.get(key);
                compareNodes(childPath, childBase, childCand, differences);
            }
            return;
        }

        if (base.isArray() && cand.isArray()) {
            ArrayNode baseArr = (ArrayNode) base;
            ArrayNode candArr = (ArrayNode) cand;
            int maxLen = Math.max(baseArr.size(), candArr.size());

            for (int i = 0; i < maxLen; i++) {
                String childPath = currentPath + "/" + i;
                JsonNode childBase = baseArr.get(i);
                JsonNode childCand = candArr.get(i);
                compareNodes(childPath, childBase, childCand, differences);
            }
            return;
        }

        differences.add(new ConfigurationDifference(currentPath, base, cand, categorize(currentPath)));
    }

    private static boolean isNullOrMissing(@Nullable JsonNode node) {
        return node == null || node.isNull() || node.isMissingNode();
    }

    public static @NonNull DifferenceCategory categorize(@NonNull String path) {
        Objects.requireNonNull(path, "path must not be null");
        String normalized = path.startsWith("/") ? path.substring(1) : path;
        if (normalized.startsWith("calibrationConfig/")) {
            normalized = normalized.substring("calibrationConfig/".length());
        }

        if (normalized.startsWith("decisionWeights")
                || normalized.startsWith("decisionWeightProfile")
                || normalized.startsWith("productivityThresholdWeight")
                || normalized.startsWith("productivityGateMode")) {
            return DifferenceCategory.POLICY;
        }
        if (normalized.startsWith("lifecycleMode")) {
            return DifferenceCategory.LIFECYCLE;
        }
        if (normalized.startsWith("rawSampleLimit") || normalized.startsWith("observe")) {
            return DifferenceCategory.OBSERVATION;
        }
        if (normalized.startsWith("cpuSet")
                || normalized.startsWith("parallelSources")
                || normalized.startsWith("orderedSources")
                || normalized.startsWith("workUnits")
                || normalized.startsWith("randomizeWork")
                || normalized.startsWith("totalRequiredExecutions")
                || normalized.startsWith("invocationTimeoutMillis")) {
            return DifferenceCategory.WORKLOAD;
        }
        if (normalized.startsWith("forks")
                || normalized.startsWith("warmups")
                || normalized.startsWith("iterations")
                || normalized.startsWith("warmupTime")
                || normalized.startsWith("measurementTime")) {
            return DifferenceCategory.JMH;
        }
        if (normalized.startsWith("jvmArgs")) {
            return DifferenceCategory.JVM;
        }
        if (normalized.startsWith("id")
                || normalized.startsWith("name")
                || normalized.startsWith("group")
                || normalized.startsWith("description")
                || normalized.startsWith("hypothesis")
                || normalized.startsWith("comparison")
                || normalized.startsWith("tags")
                || normalized.startsWith("labels")
                || normalized.startsWith("origin")
                || normalized.startsWith("enabled")
                || normalized.startsWith("calibrationProfile")) {
            return DifferenceCategory.IDENTITY;
        }

        return DifferenceCategory.HARNESS;
    }
}
