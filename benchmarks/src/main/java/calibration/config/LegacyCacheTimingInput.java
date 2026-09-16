package calibration.config;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.euhedral_execution.core.control_plane.FragmentControlConfig;
import java.io.IOException;

/// Deprecated harness JVM input only. Ordinary artifact readers never consult process properties.
public final class LegacyCacheTimingInput {
    private LegacyCacheTimingInput() {}

    public static ObjectMapper harnessMapper(ObjectMapper mapper, String parkProperty) {
        if (parkProperty == null) {
            return mapper;
        }
        long parkNs = Long.parseLong(parkProperty);
        if (parkNs < 0L) {
            throw new IllegalArgumentException("idleParkNs must not be negative");
        }
        SimpleModule module = new SimpleModule();
        module.addDeserializer(CalibrationBenchmarkConfig.class, new JsonDeserializer<>() {
            @Override
            public CalibrationBenchmarkConfig deserialize(JsonParser parser, DeserializationContext context)
                    throws IOException {
                ObjectNode node = parser.readValueAsTree();
                if (node.hasNonNull("idleParkNs") && mapper.treeToValue(node.get("idleParkNs"), Long.class) != parkNs) {
                    throw new IllegalArgumentException(
                            "Conflicting explicit idleParkNs and deprecated " + FragmentControlConfig.CACHE_PARK_NS);
                }
                node.put("idleParkNs", parkNs);
                return mapper.treeToValue(node, CalibrationBenchmarkConfig.class);
            }
        });
        return mapper.copy().registerModule(module);
    }

    /// Resolved trial arguments may confirm the fixture, but cannot override its persisted identity.
    public static void validateTrialArguments(TrialConfig trial) {
        if (trial.jvmArgs() == null) {
            return;
        }
        String prefix = "-D" + FragmentControlConfig.CACHE_PARK_NS + "=";
        for (String arg : trial.jvmArgs()) {
            if (arg.equals("-D" + FragmentControlConfig.CACHE_PARK_NS)
                    || (arg.startsWith(prefix)
                            && Long.parseLong(arg.substring(prefix.length()))
                                    != trial.calibrationConfig().cacheParkNs())) {
                throw new IllegalArgumentException("Conflicting idleParkNs and deprecated trial JVM argument: " + arg);
            }
        }
    }
}
