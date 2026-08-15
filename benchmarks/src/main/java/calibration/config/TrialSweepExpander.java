package calibration.config;

import calibration.config.HarnessConfig.OriginType;
import calibration.config.HarnessConfig.SweepConfig;
import calibration.config.HarnessConfig.SweepParameter;
import calibration.config.HarnessConfig.TrialConfig;
import calibration.config.HarnessConfig.TrialOrigin;
import com.fasterxml.jackson.core.JsonPointer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.NonNull;

/// Dedicated expander for parameter sweep declarations on trial configurations.
/// Performs Cartesian-product expansion of sweep parameters over base trial templates using JSON Pointer mutation.
public class TrialSweepExpander {

    /// Default upper bound on the number of generated trial configurations produced by a single sweep.
    public static final int DEFAULT_MAX_GENERATED_TRIALS = 10_000;

    private static final String CALIBRATION_CONFIG_PREFIX = "/calibrationConfig/";

    private final ObjectMapper mapper;
    private final int maxGeneratedTrials;

    /// Creates a TrialSweepExpander using a default ObjectMapper and max generated trials limit.
    public TrialSweepExpander() {
        this(new ObjectMapper(), DEFAULT_MAX_GENERATED_TRIALS);
    }

    /// Creates a TrialSweepExpander with the given ObjectMapper and default max generated trials limit.
    public TrialSweepExpander(@NonNull ObjectMapper mapper) {
        this(mapper, DEFAULT_MAX_GENERATED_TRIALS);
    }

    /// Creates a TrialSweepExpander with the given ObjectMapper and custom maximum generated trials limit.
    ///
    /// @throws IllegalArgumentException if maxGeneratedTrials <= 0
    /// @throws NullPointerException     if mapper is null
    public TrialSweepExpander(@NonNull ObjectMapper mapper, int maxGeneratedTrials) {
        this.mapper = Objects.requireNonNull(mapper, "mapper cannot be null");
        if (maxGeneratedTrials <= 0) {
            throw new IllegalArgumentException("maxGeneratedTrials must be positive: " + maxGeneratedTrials);
        }
        this.maxGeneratedTrials = maxGeneratedTrials;
    }

    /// Static convenience method to expand sweeps within a HarnessConfig using defaults.
    public static HarnessConfig expandHarnessConfig(@NonNull HarnessConfig harnessConfig) {
        return new TrialSweepExpander().expand(harnessConfig);
    }

    /// Expands all enabled sweeps declared in the given HarnessConfig.
    /// Returns a new HarnessConfig containing original trials plus all expanded sweep candidates.
    ///
    /// @throws IllegalArgumentException if sweep references non-existent baseTrialId, invalid pointer,
    ///                                  missing target, incompatible value type, or exceeds max generated limit
    public HarnessConfig expand(@NonNull HarnessConfig harnessConfig) {
        Objects.requireNonNull(harnessConfig, "harnessConfig cannot be null");
        if (harnessConfig.sweeps() == null || harnessConfig.sweeps().isEmpty()) {
            return harnessConfig;
        }

        List<TrialConfig> allTrials = new ArrayList<>(harnessConfig.trials());
        Map<String, TrialConfig> baseTrialMap = new HashMap<>();
        for (TrialConfig trial : harnessConfig.trials()) {
            if (trial.id() != null) {
                baseTrialMap.put(trial.id(), trial);
            }
        }

        for (SweepConfig sweep : harnessConfig.sweeps()) {
            if (!sweep.isEnabled()) {
                continue;
            }
            TrialConfig baseTrial = baseTrialMap.get(sweep.baseTrialId());
            if (baseTrial == null) {
                throw new IllegalArgumentException("Referenced baseTrialId '" + sweep.baseTrialId() + "' in sweep '"
                        + sweep.id() + "' was not found in trials");
            }
            List<TrialConfig> generated = expandSweep(baseTrial, sweep);
            allTrials.addAll(generated);
        }

        return new HarnessConfig(
                harnessConfig.schemaVersion(),
                harnessConfig.id(),
                harnessConfig.name(),
                harnessConfig.description(),
                harnessConfig.labels(),
                harnessConfig.runOptions(),
                harnessConfig.artifacts(),
                harnessConfig.calibrationProfiles(),
                harnessConfig.decisionWeightProfiles(),
                harnessConfig.sweeps(),
                harnessConfig.searches(),
                allTrials);
    }

    /// Performs Cartesian product expansion of a single SweepConfig against a base TrialConfig.
    ///
    /// @throws IllegalArgumentException if validation checks fail or limit is exceeded
    public List<TrialConfig> expandSweep(@NonNull TrialConfig baseTrial, @NonNull SweepConfig sweep) {
        Objects.requireNonNull(baseTrial, "baseTrial cannot be null");
        Objects.requireNonNull(sweep, "sweep cannot be null");

        if (baseTrial.id() == null || !baseTrial.id().equals(sweep.baseTrialId())) {
            throw new IllegalArgumentException("Base trial ID '" + baseTrial.id()
                    + "' does not match sweep baseTrialId '" + sweep.baseTrialId() + "'");
        }

        List<SweepParameter> parameters = sweep.parameters();
        long product = 1;
        for (SweepParameter param : parameters) {
            String path = param.path();
            if (!path.startsWith(CALIBRATION_CONFIG_PREFIX)) {
                throw new IllegalArgumentException(
                        "Sweep parameter path must begin with '" + CALIBRATION_CONFIG_PREFIX + "': " + path);
            }
            try {
                JsonPointer.compile(path);
            } catch (Exception e) {
                throw new IllegalArgumentException("Invalid JSON Pointer syntax in sweep path: " + path, e);
            }
            long count = param.values().size();
            try {
                product = Math.multiplyExact(product, count);
            } catch (ArithmeticException e) {
                throw new IllegalArgumentException(
                        "Cartesian product size for sweep '" + sweep.id() + "' overflows integer limit");
            }
            if (product > maxGeneratedTrials) {
                throw new IllegalArgumentException("Cartesian product size (" + product + ") for sweep '" + sweep.id()
                        + "' exceeds maximum allowed generated limit of " + maxGeneratedTrials);
            }
        }

        ObjectNode baseObjectNode = mapper.valueToTree(baseTrial);

        for (SweepParameter param : parameters) {
            JsonPointer pointer = JsonPointer.compile(param.path());
            JsonNode target = baseObjectNode.at(pointer);
            if (target.isMissingNode()) {
                throw new IllegalArgumentException("Target path does not exist in base TrialConfig: " + param.path());
            }
        }

        List<List<JsonNode>> combinations = new ArrayList<>();
        buildCartesianCombinations(parameters, 0, new ArrayList<>(), combinations);

        List<TrialConfig> generatedTrials = new ArrayList<>((int) product);
        for (int candidateIndex = 0; candidateIndex < combinations.size(); candidateIndex++) {
            List<JsonNode> combination = combinations.get(candidateIndex);
            ObjectNode candidateNode = baseObjectNode.deepCopy();

            for (int p = 0; p < parameters.size(); p++) {
                SweepParameter param = parameters.get(p);
                JsonNode value = combination.get(p);
                applyValueAtPointer(candidateNode, param.path(), value);
            }

            TrialConfig candidateTrial;
            try {
                candidateTrial = mapper.treeToValue(candidateNode, TrialConfig.class);
            } catch (Exception e) {
                throw new IllegalArgumentException(
                        "Sweep parameter value cannot deserialize back into TrialConfig for sweep '" + sweep.id()
                                + "': " + e.getMessage(),
                        e);
            }

            String generatedId = baseTrial.id() + "__" + sweep.id() + "__" + candidateIndex;
            String generatedName =
                    (baseTrial.name() != null) ? baseTrial.name() + "__" + sweep.id() + "__" + candidateIndex : null;
            TrialOrigin generatedOrigin = new TrialOrigin(OriginType.SWEEP, sweep.id(), null, candidateIndex);

            TrialConfig finalTrial = new TrialConfig(
                    generatedId,
                    generatedName,
                    candidateTrial.group(),
                    candidateTrial.description(),
                    candidateTrial.hypothesis(),
                    candidateTrial.comparison(),
                    candidateTrial.tags(),
                    Boolean.TRUE,
                    generatedOrigin,
                    candidateTrial.forks(),
                    candidateTrial.warmups(),
                    candidateTrial.iterations(),
                    candidateTrial.jvmArgs(),
                    candidateTrial.calibrationConfig());

            generatedTrials.add(finalTrial);
        }

        return generatedTrials;
    }

    private void buildCartesianCombinations(
            List<SweepParameter> params, int index, List<JsonNode> current, List<List<JsonNode>> result) {
        if (index == params.size()) {
            result.add(new ArrayList<>(current));
            return;
        }
        SweepParameter param = params.get(index);
        for (JsonNode val : param.values()) {
            current.add(val);
            buildCartesianCombinations(params, index + 1, current, result);
            current.remove(current.size() - 1);
        }
    }

    private void applyValueAtPointer(ObjectNode root, String path, JsonNode value) {
        JsonPointer pointer = JsonPointer.compile(path);
        List<JsonPointer> segments = new ArrayList<>();
        JsonPointer curr = pointer;
        while (!curr.matches()) {
            segments.add(curr);
            curr = curr.tail();
        }
        if (segments.isEmpty()) {
            throw new IllegalArgumentException("Cannot apply value to empty JSON Pointer: " + path);
        }

        JsonNode current = root;
        for (int i = 0; i < segments.size() - 1; i++) {
            JsonPointer seg = segments.get(i);
            if (current.isObject()) {
                String prop = seg.getMatchingProperty();
                current = ((ObjectNode) current).get(prop);
            } else if (current.isArray()) {
                int idx = seg.getMatchingIndex();
                if (idx < 0 || idx >= current.size()) {
                    throw new IllegalArgumentException("Target path index out of bounds: " + path);
                }
                current = ((ArrayNode) current).get(idx);
            } else {
                throw new IllegalArgumentException("Target path parent is not a container: " + path);
            }
            if (current == null || current.isMissingNode()) {
                throw new IllegalArgumentException("Target path parent does not exist: " + path);
            }
        }

        JsonPointer lastSeg = segments.get(segments.size() - 1);
        if (current.isObject()) {
            String prop = lastSeg.getMatchingProperty();
            if (!((ObjectNode) current).has(prop)) {
                throw new IllegalArgumentException("Target property does not exist in ObjectNode: " + path);
            }
            ((ObjectNode) current).set(prop, value);
        } else if (current.isArray()) {
            int idx = lastSeg.getMatchingIndex();
            if (idx < 0 || idx >= current.size()) {
                throw new IllegalArgumentException("Target array index out of bounds: " + path);
            }
            ((ArrayNode) current).set(idx, value);
        } else {
            throw new IllegalArgumentException("Target path parent is not an object or array: " + path);
        }
    }
}
