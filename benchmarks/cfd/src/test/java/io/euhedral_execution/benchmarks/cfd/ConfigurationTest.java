package io.euhedral_execution.benchmarks.cfd;

import static org.junit.jupiter.api.Assertions.*;

import io.euhedral_execution.benchmarks.cfd.config.CfdConfiguration;
import io.euhedral_execution.benchmarks.cfd.config.ConfigLoader;
import io.euhedral_execution.benchmarks.cfd.config.GridShape;
import io.euhedral_execution.benchmarks.cfd.config.MemoryEstimate;
import io.euhedral_execution.benchmarks.cfd.config.SimulationConfig;
import io.euhedral_execution.benchmarks.cfd.config.Vector3;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ConfigurationTest {
    @TempDir
    Path directory;

    private static final String MINIMAL = "\"schemaVersion\":1,\"grid\":{\"nx\":12,\"ny\":10,\"nz\":8}";

    private CfdConfiguration load(String fields) throws Exception {
        Path config = directory.resolve("scene.json");
        Files.writeString(config, "{" + fields + "}");
        return ConfigLoader.load(config, 100_000_000L);
    }

    @Test
    void defaultsAreDeterministicAndResolvePathsWithoutCreatingOutput() throws Exception {
        var first = load(MINIMAL);
        assertEquals(first, load(MINIMAL));
        assertEquals(960, first.config().grid().cellCount());
        assertEquals(0.1, first.physics().viscosity());
        assertEquals(0.8, first.physics().tau());
        assertEquals(1, first.physics().densityReference());
        assertEquals(Vector3.ZERO, first.physics().initialVelocity());
        assertEquals(Vector3.ZERO, first.physics().acceleration());
        assertEquals(0, first.physics().initialMach());
        assertNull(first.physics().reynolds());
        assertNull(first.physicalDurationSeconds());
        assertEquals(100, first.steps());
        assertEquals(30_000, first.config().execution().stepDeadlineMillis());
        assertEquals(new GridShape(16, 16, 16), first.config().execution().brick());
        assertEquals(
                SimulationConfig.FaceCondition.PERIODIC,
                first.config().geometry().faces().zMax());
        assertEquals(directory.resolve("output"), first.outputDirectory());
        assertFalse(Files.exists(first.outputDirectory()));
    }

    @Test
    void shearProfileResolvesModeUnitsAndInspectionValues() throws Exception {
        var lattice = load(MINIMAL + ",\"physics\":{\"shear\":{\"amplitude\":0.01},\"referenceLength\":10}");
        assertEquals(1, lattice.physics().shear().modeY());
        assertEquals(1, lattice.physics().shear().modeZ());
        assertEquals(0.01 * Math.sqrt(3), lattice.physics().initialMach(), 1e-16);
        assertEquals(1, lattice.physics().reynolds());
        var physical = load(MINIMAL + """
            ,"physics":{"physical":{"voxelWidth":0.01,"timeStep":0.001,"densityReference":1000,"viscosity":0.01},
            "shear":{"amplitude":0.1,"modeY":2,"modeZ":3},"referenceLength":0.1}
            """);
        assertEquals(0.01, physical.physics().shear().amplitude(), 1e-16);
        assertEquals(lattice.physics().initialMach(), physical.physics().initialMach(), 1e-16);
        assertEquals(lattice.physics().reynolds(), physical.physics().reynolds(), 1e-15);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "\"shear\":{}",
                "\"shear\":{\"amplitude\":0}",
                "\"shear\":{\"amplitude\":1e999}",
                "\"shear\":{\"amplitude\":0.01,\"modeY\":0}",
                "\"shear\":{\"amplitude\":0.01,\"modeZ\":4}",
                "\"shear\":{\"amplitude\":0.01,\"modeY\":5}",
                "\"shear\":{\"amplitude\":0.01,\"modeZ\":1.5}",
                "\"shear\":{\"amplitude\":0.01},\"lattice\":{\"initialVelocity\":{\"x\":0.01}}"
            })
    void invalidShearProfilesAreRejected(String fields) {
        assertThrows(Exception.class, () -> load(MINIMAL + ",\"physics\":{" + fields + "}"));
    }

    @Test
    void relativeAndAbsoluteOutputPathsUseDeclaringFile() throws Exception {
        assertEquals(
                directory.getParent().resolve("results"),
                load(MINIMAL + ",\"output\":{\"directory\":\"../results\"}").outputDirectory());
        assertEquals(
                directory.resolve("absolute"),
                load(MINIMAL + ",\"output\":{\"directory\":\"" + directory.resolve("absolute") + "\"}")
                        .outputDirectory());
    }

    @Test
    void physicalInputsResolveViscosityVelocityAccelerationDensityAndDuration() throws Exception {
        var resolved = load(MINIMAL + """
            ,"physics": {
              "densityReference": 2,
              "physical": {"voxelWidth":0.01,"timeStep":0.001,"densityReference":1000,
                "viscosity":0.01,"initialVelocity":{"x":0.1,"y":0,"z":0},
                "acceleration":{"x":0,"y":-9.8,"z":0}},
              "referenceLength":0.2
            },"execution":{"durationSeconds":0.0025}
            """);
        assertTrue(resolved.physics().physicalUnits());
        assertEquals(0.1, resolved.physics().viscosity(), 1e-15);
        assertEquals(0.8, resolved.physics().tau(), 1e-15);
        assertEquals(0.01, resolved.physics().initialVelocity().x(), 1e-15);
        assertEquals(-0.00098, resolved.physics().acceleration().y(), 1e-15);
        assertEquals(500, resolved.physics().densityScale());
        assertEquals(0.01 * Math.sqrt(3), resolved.physics().initialMach(), 1e-15);
        assertEquals(2, resolved.physics().reynolds(), 1e-14);
        assertEquals(3, resolved.steps());
        assertEquals(0.003, resolved.physicalDurationSeconds());
    }

    @Test
    void wallPairsAndLatticeForceAreInspectable() throws Exception {
        var resolved = load(MINIMAL + """
            ,"geometry":{"faces":{"yMin":"WALL","yMax":"WALL"}},
            "physics":{"lattice":{"acceleration":{"x":0.00001,"y":0,"z":0}}}
            """);
        assertEquals(
                SimulationConfig.FaceCondition.WALL,
                resolved.config().geometry().faces().yMax());
        assertEquals(0.00001, resolved.physics().acceleration().x());
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "\"unknown\":1",
                "\"physics\":{\"unknown\":1}",
                "\"execution\":{\"unknown\":1}",
                "\"output\":{\"directory\":null}",
                "\"physics\":null",
                "\"physics\":{\"lattice\":{\"viscosity\":\"0.1\"}}",
                "\"physics\":{\"lattice\":{\"viscosity\":\"NaN\"}}",
                "\"physics\":{\"lattice\":{\"viscosity\":1e999}}",
                "\"physics\":{\"lattice\":{\"viscosity\":1e-300}}",
                "\"physics\":{\"lattice\":{\"viscosity\":1e308}}",
                "\"physics\":{\"lattice\":{\"viscosity\":0}}",
                "\"physics\":{\"lattice\":{\"initialVelocity\":{\"x\":1e999}}}",
                "\"physics\":{\"physical\":{}}",
                "\"physics\":{\"densityReference\":-1}",
                "\"physics\":{\"referenceLength\":0}",
                "\"execution\":{\"steps\":1.5}",
                "\"execution\":{\"steps\":\"10\"}",
                "\"execution\":{\"steps\":0}",
                "\"execution\":{\"durationSeconds\":1}",
                "\"execution\":{\"steps\":1,\"durationSeconds\":1}",
                "\"execution\":{\"stepDeadlineMillis\":0}",
                "\"execution\":{\"stepDeadlineMillis\":9223372036854775807}",
                "\"execution\":{\"brick\":{\"nx\":0,\"ny\":2,\"nz\":2}}",
                "\"output\":{\"exportEverySteps\":-1}",
                "\"output\":{\"directory\":\" \"}",
                "\"geometry\":{\"faces\":{\"xMin\":\"WALL\"}}",
                "\"geometry\":{\"faces\":{\"xMin\":1,\"xMax\":1}}",
                "\"geometry\":{\"faces\":{\"xMin\":\"VELOCITY_INLET\"}}",
                "\"geometry\":{\"mesh\":\"obstacle.stl\"}",
                "\"memoryLimitBytes\":0"
            })
    void rejectsInvalidOrUnsupportedFields(String extra) {
        assertThrows(Exception.class, () -> load(MINIMAL + "," + extra));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "{}",
                "null",
                "[]",
                "{\"grid\":{\"nx\":3,\"ny\":3,\"nz\":3}}",
                "{\"schemaVersion\":1}",
                "{\"schemaVersion\":2,\"grid\":{\"nx\":3,\"ny\":3,\"nz\":3}}",
                "{\"schemaVersion\":1,\"schemaVersion\":1,\"grid\":{\"nx\":3,\"ny\":3,\"nz\":3}}",
                "{\"schemaVersion\":1,\"grid\":{\"nx\":3,\"ny\":3,\"nz\":3}} {}",
                "{\"schemaVersion\":1,\"grid\":{\"nx\":3,\"ny\":3,\"nz\":1}}",
                "{\"schemaVersion\":1,\"grid\":{\"nx\":3,\"ny\":3}}",
                "{\"schemaVersion\":1,\"grid\":{\"nx\":3.1,\"ny\":3,\"nz\":3}}"
            })
    void rejectsMalformedOrIncompleteDocuments(String json) throws Exception {
        Path file = directory.resolve("invalid.json");
        Files.writeString(file, json);
        assertThrows(Exception.class, () -> ConfigLoader.load(file));
    }

    @Test
    void rejectsScalarCoercionForPaths() {
        assertThrows(Exception.class, () -> load(MINIMAL + ",\"output\":{\"directory\":123}"));
        assertThrows(Exception.class, () -> load(MINIMAL + ",\"output\":{\"directory\":true}"));
    }

    @Test
    void rejectsUnrepresentablePhysicalConversionsAndDurations() {
        for (String physical : new String[] {
            "\"voxelWidth\":1e-300,\"timeStep\":1e300",
            "\"voxelWidth\":1e300,\"timeStep\":1e-300",
            "\"voxelWidth\":1,\"timeStep\":1e-20"
        }) {
            assertThrows(
                    Exception.class,
                    () -> load(MINIMAL + ",\"physics\":{\"physical\":{"
                            + physical + ",\"densityReference\":1000,\"viscosity\":0.1}},"
                            + "\"execution\":{\"durationSeconds\":1e300}"));
        }
    }

    @Test
    void inspectionOfBillionCellCaseCreatesOnlyConfigurationObjects() throws Exception {
        var config = new SimulationConfig(1, new GridShape(1000, 1000, 1000), null, null, null, null, Long.MAX_VALUE);
        var resolved = ConfigLoader.resolve(directory.resolve("large.json"), config, 1);
        assertEquals(304_000_000_000L, resolved.memory().populationBytes());
        assertTrue(resolved.memory().arrayIndexable());
        assertFalse(Files.exists(resolved.outputDirectory()));
    }

    @Test
    void rejectsConflictingModes() {
        var error = assertThrows(Exception.class, () -> load(MINIMAL + """
            ,"physics":{"lattice":{},"physical":{"voxelWidth":1,"timeStep":1,"densityReference":1,"viscosity":0.1}}
            """));
        assertTrue(error.getMessage().contains("mutually exclusive"));
    }

    @Test
    void oneCellBricksDoNotReserveMillionsOfEuhedralFrames() throws Exception {
        var config = ConfigLoader.load(
                Path.of("suites/cases/periodic-256.json"),
                java.util.List.of("execution.brick.nx=1", "execution.brick.ny=1", "execution.brick.nz=1"));
        assertEquals(
                16_777_216,
                config.config().grid().brickCount(config.config().execution().brick()));
        config.memory().requireAllocatable(config.config().grid());
        assertTrue(config.memory().totalBytes() < 8L * 1024 * 1024 * 1024);
    }

    @Test
    void memoryAccountsForPartialBricksAndStreamingExport() throws Exception {
        var result = load(MINIMAL + """
            ,"execution":{"brick":{"nx":5,"ny":4,"nz":3}},"output":{"exportEverySteps":1}
            """);
        assertEquals(
                27,
                result.config().grid().brickCount(result.config().execution().brick()));
        assertEquals(304L * 960, result.memory().populationBytes());
        assertEquals(5L * 960 + 40L * 27 + 1_048_576 + 131_072, result.memory().auxiliaryBytes());
        assertEquals(
                result.memory().populationBytes() + result.memory().auxiliaryBytes(),
                result.memory().totalBytes());
        assertTrue(result.memory().arrayIndexable());
    }

    @Test
    void explicitBudgetOverridesDefaultAndRejectsBeforeAllocation() throws Exception {
        var config = load(MINIMAL).config();
        long exact = MemoryEstimate.estimate(config, Long.MAX_VALUE).totalBytes();
        var accepted = load(MINIMAL + ",\"memoryLimitBytes\":" + exact);
        assertEquals(exact, accepted.memory().budgetBytes());
        assertEquals(
                exact,
                ConfigLoader.resolve(directory.resolve("scene.json"), accepted.config(), 1)
                        .memory()
                        .budgetBytes());
        var rejected = assertThrows(
                IllegalArgumentException.class,
                () -> ConfigLoader.resolve(directory.resolve("scene.json"), config, exact - 1));
        assertTrue(rejected.getMessage().contains("12x10x8"));
        assertTrue(rejected.getMessage().contains("estimated requirement=" + exact));
        assertTrue(rejected.getMessage().contains("memory budget exceeded"));
        assertFalse(Files.exists(directory.resolve("output")));
        assertEquals(400, MemoryEstimate.defaultBudget(1000, 200));
        assertEquals(1, MemoryEstimate.defaultBudget(1000, 1000));
    }

    @Test
    void arrayIndexabilityIsCheckedEvenWithLargeBudget() throws Exception {
        var config = new SimulationConfig(1, new GridShape(1024, 1024, 2048), null, null, null, null, Long.MAX_VALUE);
        assertFalse(MemoryEstimate.estimate(config, 1).arrayIndexable());
        var error = assertThrows(
                IllegalArgumentException.class, () -> ConfigLoader.resolve(directory.resolve("huge.json"), config, 1));
        assertTrue(error.getMessage().contains("Java array limit"));
        assertTrue(error.getMessage().contains("1024x1024x2048"));
    }

    @Test
    void checkedArithmeticRejectsCellMemoryAndUpdateOverflow() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new GridShape(Integer.MAX_VALUE, Integer.MAX_VALUE, 3).cellCount());
        var huge =
                new SimulationConfig(1, new GridShape(1_000_000, 1_000_000, 1_000_000), null, null, null, null, null);
        assertThrows(IllegalArgumentException.class, () -> MemoryEstimate.estimate(huge, Long.MAX_VALUE));
        var error = assertThrows(
                IllegalArgumentException.class, () -> load(MINIMAL + ",\"execution\":{\"steps\":9223372036854775807}"));
        assertTrue(error.getMessage().contains("cell update count overflows"));
    }

    @Test
    void recordBoundariesRejectNonFiniteValues() {
        assertThrows(IllegalArgumentException.class, () -> new Vector3(Double.NaN, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new Vector3(0, Double.POSITIVE_INFINITY, 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> new SimulationConfig.Lattice(Double.NEGATIVE_INFINITY, null, null));
        assertThrows(IllegalArgumentException.class, () -> new SimulationConfig.Physical(0, 1, 1, 1, null, null));
    }
}
