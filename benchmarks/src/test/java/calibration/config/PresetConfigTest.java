package calibration.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PresetConfigTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void exampleHarnessAndProfileLibraryUseCurrentSchema() throws Exception {
        Path examples = Path.of("src/main/presets/examples");

        HarnessConfig harness = HarnessConfig.load(
                        examples.resolve("example_harness_config.json").toFile(), mapper)
                .resolveCalibrationProfiles();
        ProfileLibrary library = mapper.readValue(
                examples.resolve("example_profile_library.json").toFile(), ProfileLibrary.class);
        ProfileLibrary baseline = mapper.readValue(
                Path.of("src/main/presets/profiles/baseline.json").toFile(), ProfileLibrary.class);

        assertEquals(2, harness.trials().size());
        assertNotNull(harness.trials().getFirst().calibrationConfig());
        assertNotNull(library.calibrationProfiles().get("imported-calibration"));
        assertNotNull(baseline.calibrationProfiles().get("standard-2core-fixture"));
    }

    @Test
    void comparisonExamplesUseForkSampleSchema() throws Exception {
        Path examples = Path.of("src/main/presets/examples");

        for (String name : new String[] {
            "example_comparison_config.json",
            "example_cross_comparison_config.json",
            "example_keyed_comparison_config.json"
        }) {
            assertNotNull(mapper.readValue(examples.resolve(name).toFile(), ComparisonConfig.class));
        }
    }
}
