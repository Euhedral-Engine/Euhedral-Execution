package calibration.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.euhedral_execution.core.config.FragmentDecisionWeights;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// Comprehensive unit tests for external profile library imports, namespacing, resolution,
/// cycle detection, and sweep expansion.
class ProfileLibraryLoaderTest {

    private final ObjectMapper mapper = new ObjectMapper().configure(JsonParser.Feature.ALLOW_COMMENTS, true);

    private static CalibrationBenchmarkConfig dummyCalibrationConfig(int workUnits, String weightProfile) {
        return new CalibrationBenchmarkConfig(
                List.of(1, 2),
                4,
                2,
                workUnits,
                false,
                1000,
                5000,
                weightProfile,
                1024,
                false,
                false,
                false,
                false,
                false,
                false);
    }

    private static CalibrationBenchmarkConfig dummyCalibrationConfigWithWeights(int workUnits) {
        return new CalibrationBenchmarkConfig(
                List.of(1, 2),
                4,
                2,
                workUnits,
                false,
                1000,
                5000,
                FragmentDecisionWeights.DEFAULT,
                1024,
                false,
                false,
                false,
                false,
                false,
                false);
    }

    /// Verifies single imported library loads and namespaced profiles are accessible.
    @Test
    void testSingleImportedLibrary(@TempDir Path tempDir) throws Exception {
        Path profileFile = tempDir.resolve("common.json");
        String profileJson = """
            {
              "calibrationProfiles": {
                "uniform-xs": {
                  "cpuSet": [1, 2],
                  "parallelSources": 4,
                  "orderedSources": 0,
                  "workUnits": 42,
                  "randomizeWork": false,
                  "totalRequiredExecutions": 1000,
                  "invocationTimeoutMillis": 5000,
                  "decisionWeights": {
                    "idleContentionThresholds": { "xsContention": 1, "sContention": 1, "mContention": 1, "hContention": 1 },
                    "idleBodyCostWeights": [],
                    "idleTimeNs": [],
                    "execContentionThresholds": { "xsContention": 1, "sContention": 1, "mContention": 1, "hContention": 1 },
                    "execBodyCostWeights": [],
                    "executionPolicies": []
                  },
                  "rawSampleLimit": 1024,
                  "observeCycleStart": false,
                  "observeBatchProgress": false,
                  "observeBatchComplete": false,
                  "observeRawBodyCost": false,
                  "observeIdleDecision": false,
                  "observeExecDecision": false
                }
              },
              "decisionWeightProfiles": {
                "baseline-weights": {
                  "idleContentionThresholds": { "xsContention": 200, "sContention": 200, "mContention": 200, "hContention": 200 },
                  "idleBodyCostWeights": [],
                  "idleTimeNs": [],
                  "execContentionThresholds": { "xsContention": 200, "sContention": 200, "mContention": 200, "hContention": 200 },
                  "execBodyCostWeights": [],
                  "executionPolicies": []
                }
              }
            }
            """;
        Files.writeString(profileFile, profileJson);

        Path harnessFile = tempDir.resolve("harness.json");
        String harnessJson = """
            {
              "imports": [
                {
                  "path": "common.json",
                  "namespace": "common"
                }
              ],
              "trials": [
                {
                  "id": "trial-1",
                  "calibrationProfile": "common.uniform-xs",
                  "forks": 1,
                  "warmups": 1,
                  "iterations": 1
                }
              ]
            }
            """;
        Files.writeString(harnessFile, harnessJson);

        HarnessConfig loaded = ProfileLibraryLoader.loadAndResolve(harnessFile.toFile(), mapper);
        assertNotNull(loaded.calibrationProfiles());
        assertTrue(loaded.calibrationProfiles().containsKey("common.uniform-xs"));
        assertNotNull(loaded.decisionWeightProfiles());
        assertTrue(loaded.decisionWeightProfiles().containsKey("common.baseline-weights"));

        HarnessConfig resolved = loaded.resolveCalibrationProfiles();
        assertNotNull(resolved.trials().getFirst().calibrationConfig());
        assertEquals(42, resolved.trials().getFirst().calibrationConfig().workUnits());
    }

    /// Verifies calibration profile lookup resolves imported calibration profile into trial calibrationConfig.
    @Test
    void testCalibrationProfileLookup(@TempDir Path tempDir) throws Exception {
        Path profileFile = tempDir.resolve("lib.json");
        ProfileLibrary library =
                new ProfileLibrary(Map.of("fast-profile", dummyCalibrationConfigWithWeights(15)), null);
        mapper.writeValue(profileFile.toFile(), library);

        HarnessConfig config = new HarnessConfig(
                null,
                null,
                null,
                null,
                null,
                List.of(new ProfileImport("lib.json", "lib")),
                null,
                null,
                null,
                null,
                null,
                null,
                List.of(new TrialConfig(
                        "t1",
                        "name",
                        "group",
                        null,
                        null,
                        null,
                        null,
                        null,
                        true,
                        null,
                        1,
                        1,
                        1,
                        null,
                        null,
                        null,
                        "lib.fast-profile",
                        null)));

        HarnessConfig withImports = config.resolveImports(tempDir.toFile(), mapper);
        HarnessConfig resolved = withImports.resolveCalibrationProfiles();

        TrialConfig trial = resolved.trials().getFirst();
        assertNotNull(trial.calibrationConfig());
        assertEquals(15, trial.calibrationConfig().workUnits());
    }

    /// Verifies decision weight profile lookup resolves imported decisionWeightProfile reference into decisionWeights.
    @Test
    void testDecisionWeightProfileLookup(@TempDir Path tempDir) throws Exception {
        Path profileFile = tempDir.resolve("weights.json");
        ProfileLibrary library = new ProfileLibrary(null, Map.of("custom-weights", FragmentDecisionWeights.DEFAULT));
        mapper.writeValue(profileFile.toFile(), library);

        CalibrationBenchmarkConfig trialCalConfig = dummyCalibrationConfig(80, "imported.custom-weights");
        TrialConfig trial = new TrialConfig(
                "t1",
                "name",
                "group",
                null,
                null,
                null,
                null,
                null,
                true,
                null,
                1,
                1,
                1,
                null,
                null,
                null,
                null,
                trialCalConfig);

        HarnessConfig config = new HarnessConfig(
                null,
                null,
                null,
                null,
                null,
                List.of(new ProfileImport("weights.json", "imported")),
                null,
                null,
                null,
                null,
                null,
                null,
                List.of(trial));

        HarnessConfig withImports = config.resolveImports(tempDir.toFile(), mapper);
        HarnessConfig resolved = withImports.resolveCalibrationProfiles();

        TrialConfig resolvedTrial = resolved.trials().getFirst();
        assertNotNull(resolvedTrial.calibrationConfig());
        assertNotNull(resolvedTrial.calibrationConfig().decisionWeights());
    }

    /// Verifies local profiles and imported profiles coexist cleanly without interference.
    @Test
    void testLocalAndImportedProfileCoexistence(@TempDir Path tempDir) throws Exception {
        Path profileFile = tempDir.resolve("imported.json");
        ProfileLibrary library = new ProfileLibrary(
                Map.of("cal-imported", dummyCalibrationConfigWithWeights(99)),
                Map.of("weights-imported", FragmentDecisionWeights.DEFAULT));
        mapper.writeValue(profileFile.toFile(), library);

        TrialConfig trialLocal = new TrialConfig(
                "t-local",
                "name",
                "group",
                null,
                null,
                null,
                null,
                null,
                true,
                null,
                1,
                1,
                1,
                null,
                null,
                null,
                "local-cal",
                null);
        TrialConfig trialImported = new TrialConfig(
                "t-imported",
                "name",
                "group",
                null,
                null,
                null,
                null,
                null,
                true,
                null,
                1,
                1,
                1,
                null,
                null,
                null,
                "ns.cal-imported",
                null);

        HarnessConfig config = new HarnessConfig(
                null,
                null,
                null,
                null,
                null,
                List.of(new ProfileImport("imported.json", "ns")),
                null,
                null,
                Map.of("local-cal", dummyCalibrationConfigWithWeights(11)),
                Map.of("local-weights", FragmentDecisionWeights.DEFAULT),
                null,
                null,
                List.of(trialLocal, trialImported));

        HarnessConfig withImports = config.resolveImports(tempDir.toFile(), mapper);
        assertEquals(2, withImports.calibrationProfiles().size());
        assertTrue(withImports.calibrationProfiles().containsKey("local-cal"));
        assertTrue(withImports.calibrationProfiles().containsKey("ns.cal-imported"));

        assertEquals(2, withImports.decisionWeightProfiles().size());
        assertTrue(withImports.decisionWeightProfiles().containsKey("local-weights"));
        assertTrue(withImports.decisionWeightProfiles().containsKey("ns.weights-imported"));

        HarnessConfig resolved = withImports.resolveCalibrationProfiles();
        assertEquals(11, resolved.trials().get(0).calibrationConfig().workUnits());
        assertEquals(99, resolved.trials().get(1).calibrationConfig().workUnits());
    }

    /// Verifies same profile name in two different namespaces are isolated and resolve independently.
    @Test
    void testSameProfileNameInTwoNamespaces(@TempDir Path tempDir) throws Exception {
        Path lib1File = tempDir.resolve("lib1.json");
        ProfileLibrary lib1 = new ProfileLibrary(Map.of("baseline", dummyCalibrationConfigWithWeights(100)), null);
        mapper.writeValue(lib1File.toFile(), lib1);

        Path lib2File = tempDir.resolve("lib2.json");
        ProfileLibrary lib2 = new ProfileLibrary(Map.of("baseline", dummyCalibrationConfigWithWeights(200)), null);
        mapper.writeValue(lib2File.toFile(), lib2);

        TrialConfig t1 = new TrialConfig(
                "t1",
                "name",
                "group",
                null,
                null,
                null,
                null,
                null,
                true,
                null,
                1,
                1,
                1,
                null,
                null,
                null,
                "ns1.baseline",
                null);
        TrialConfig t2 = new TrialConfig(
                "t2",
                "name",
                "group",
                null,
                null,
                null,
                null,
                null,
                true,
                null,
                1,
                1,
                1,
                null,
                null,
                null,
                "ns2.baseline",
                null);

        HarnessConfig config = new HarnessConfig(
                null,
                null,
                null,
                null,
                null,
                List.of(new ProfileImport("lib1.json", "ns1"), new ProfileImport("lib2.json", "ns2")),
                null,
                null,
                null,
                null,
                null,
                null,
                List.of(t1, t2));

        HarnessConfig withImports = config.resolveImports(tempDir.toFile(), mapper);
        HarnessConfig resolved = withImports.resolveCalibrationProfiles();

        assertEquals(100, resolved.trials().get(0).calibrationConfig().workUnits());
        assertEquals(200, resolved.trials().get(1).calibrationConfig().workUnits());
    }

    /// Verifies duplicate namespace declarations are rejected in HarnessConfig and ProfileLibrary.
    @Test
    void testDuplicateNamespaceRejection() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new HarnessConfig(
                        null,
                        null,
                        null,
                        null,
                        null,
                        List.of(new ProfileImport("a.json", "common"), new ProfileImport("b.json", "common")),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        List.of(new TrialConfig(1, 1, 1, null, dummyCalibrationConfigWithWeights(1)))));

        assertThrows(
                IllegalArgumentException.class,
                () -> new ProfileLibrary(
                        List.of(new ProfileImport("a.json", "base"), new ProfileImport("b.json", "base")), null, null));
    }

    /// Verifies missing or blank namespace declarations are rejected.
    @Test
    void testMissingNamespaceRejection() {
        assertThrows(NullPointerException.class, () -> new ProfileImport("a.json", null));
        assertThrows(IllegalArgumentException.class, () -> new ProfileImport("a.json", ""));
        assertThrows(IllegalArgumentException.class, () -> new ProfileImport("a.json", "   "));

        assertThrows(NullPointerException.class, () -> new ProfileImport(null, "ns"));
        assertThrows(IllegalArgumentException.class, () -> new ProfileImport("", "ns"));
        assertThrows(IllegalArgumentException.class, () -> new ProfileImport("   ", "ns"));
    }

    /// Verifies namespaces containing '.' are rejected.
    @Test
    void testInvalidNamespaceContainingDot() {
        assertThrows(IllegalArgumentException.class, () -> new ProfileImport("a.json", "foo.bar"));
        assertThrows(IllegalArgumentException.class, () -> new ProfileImport("a.json", ".foo"));
        assertThrows(IllegalArgumentException.class, () -> new ProfileImport("a.json", "foo."));
    }

    /// Verifies missing imported file produces a descriptive error containing import path, namespace, and root path.
    @Test
    void testMissingImportedFile(@TempDir Path tempDir) {
        File configFile = tempDir.resolve("root.json").toFile();
        HarnessConfig config = new HarnessConfig(
                null,
                null,
                null,
                null,
                null,
                List.of(new ProfileImport("missing.json", "missingNs")),
                null,
                null,
                null,
                null,
                null,
                null,
                List.of(new TrialConfig(1, 1, 1, null, dummyCalibrationConfigWithWeights(1))));

        IllegalArgumentException ex =
                assertThrows(IllegalArgumentException.class, () -> config.resolveImports(configFile, mapper));

        assertTrue(ex.getMessage().contains("missing.json"));
        assertTrue(ex.getMessage().contains("missingNs"));
        assertTrue(ex.getMessage().contains(configFile.getPath()));
    }

    /// Verifies relative import paths resolve relative to importing JSON file.
    @Test
    void testRelativePathResolution(@TempDir Path tempDir) throws Exception {
        Path subDir = tempDir.resolve("sub");
        Files.createDirectories(subDir);

        Path sharedDir = tempDir.resolve("shared");
        Files.createDirectories(sharedDir);

        Path sharedLib = sharedDir.resolve("profiles.json");
        ProfileLibrary library = new ProfileLibrary(Map.of("shared-cal", dummyCalibrationConfigWithWeights(77)), null);
        mapper.writeValue(sharedLib.toFile(), library);

        Path rootJson = subDir.resolve("harness.json");
        String json = """
            {
              "imports": [
                {
                  "path": "../shared/profiles.json",
                  "namespace": "shared"
                }
              ],
              "trials": [
                {
                  "calibrationProfile": "shared.shared-cal",
                  "forks": 1,
                  "warmups": 1,
                  "iterations": 1
                }
              ]
            }
            """;
        Files.writeString(rootJson, json);

        HarnessConfig loaded = ProfileLibraryLoader.loadAndResolve(rootJson.toFile(), mapper);
        HarnessConfig resolved = loaded.resolveCalibrationProfiles();
        assertEquals(77, resolved.trials().getFirst().calibrationConfig().workUnits());
    }

    /// Verifies absolute import path resolution.
    @Test
    void testAbsolutePathResolution(@TempDir Path tempDir) throws Exception {
        Path absLib = tempDir.resolve("abs_lib.json");
        ProfileLibrary library = new ProfileLibrary(Map.of("abs-cal", dummyCalibrationConfigWithWeights(88)), null);
        mapper.writeValue(absLib.toFile(), library);

        HarnessConfig config = new HarnessConfig(
                null,
                null,
                null,
                null,
                null,
                List.of(new ProfileImport(absLib.toAbsolutePath().toString(), "abs")),
                null,
                null,
                null,
                null,
                null,
                null,
                List.of(new TrialConfig(
                        "t1",
                        "name",
                        "group",
                        null,
                        null,
                        null,
                        null,
                        null,
                        true,
                        null,
                        1,
                        1,
                        1,
                        null,
                        null,
                        null,
                        "abs.abs-cal",
                        null)));

        HarnessConfig withImports = config.resolveImports(tempDir.toFile(), mapper);
        HarnessConfig resolved = withImports.resolveCalibrationProfiles();
        assertEquals(88, resolved.trials().getFirst().calibrationConfig().workUnits());
    }

    /// Verifies recursive library imports and explicit nested namespace composition (common.base.baseline).
    @Test
    void testRecursiveImportsAndNestedNamespaceComposition(@TempDir Path tempDir) throws Exception {
        Path libBFile = tempDir.resolve("libB.json");
        ProfileLibrary libB = new ProfileLibrary(
                Map.of("cal-b", dummyCalibrationConfig(33, "weights-b")),
                Map.of("weights-b", FragmentDecisionWeights.DEFAULT));
        mapper.writeValue(libBFile.toFile(), libB);

        Path libAFile = tempDir.resolve("libA.json");
        ProfileLibrary libA = new ProfileLibrary(
                List.of(new ProfileImport("libB.json", "base")),
                Map.of("cal-a", dummyCalibrationConfig(44, "base.weights-b")),
                null);
        mapper.writeValue(libAFile.toFile(), libA);

        Path rootFile = tempDir.resolve("root.json");
        String rootJson = """
            {
              "imports": [
                {
                  "path": "libA.json",
                  "namespace": "common"
                }
              ],
              "trials": [
                {
                  "id": "t-a",
                  "calibrationProfile": "common.cal-a",
                  "forks": 1,
                  "warmups": 1,
                  "iterations": 1
                },
                {
                  "id": "t-b",
                  "calibrationProfile": "common.base.cal-b",
                  "forks": 1,
                  "warmups": 1,
                  "iterations": 1
                }
              ]
            }
            """;
        Files.writeString(rootFile, rootJson);

        HarnessConfig loaded = ProfileLibraryLoader.loadAndResolve(rootFile.toFile(), mapper);
        assertTrue(loaded.calibrationProfiles().containsKey("common.cal-a"));
        assertTrue(loaded.calibrationProfiles().containsKey("common.base.cal-b"));
        assertTrue(loaded.decisionWeightProfiles().containsKey("common.base.weights-b"));

        HarnessConfig resolved = loaded.resolveCalibrationProfiles();
        assertEquals(44, resolved.trials().get(0).calibrationConfig().workUnits());
        assertNotNull(resolved.trials().get(0).calibrationConfig().decisionWeights());
        assertEquals(33, resolved.trials().get(1).calibrationConfig().workUnits());
        assertNotNull(resolved.trials().get(1).calibrationConfig().decisionWeights());
    }

    /// Verifies cyclic import detection across a chain: a.json -> b.json -> c.json -> a.json.
    @Test
    void testImportCycleDetection(@TempDir Path tempDir) throws Exception {
        Path fileA = tempDir.resolve("a.json");
        Path fileB = tempDir.resolve("b.json");
        Path fileC = tempDir.resolve("c.json");

        ProfileLibrary libA = new ProfileLibrary(List.of(new ProfileImport("b.json", "b")), null, null);
        ProfileLibrary libB = new ProfileLibrary(List.of(new ProfileImport("c.json", "c")), null, null);
        ProfileLibrary libC = new ProfileLibrary(List.of(new ProfileImport("a.json", "a")), null, null);

        mapper.writeValue(fileA.toFile(), libA);
        mapper.writeValue(fileB.toFile(), libB);
        mapper.writeValue(fileC.toFile(), libC);

        HarnessConfig config = new HarnessConfig(
                null,
                null,
                null,
                null,
                null,
                List.of(new ProfileImport("a.json", "a")),
                null,
                null,
                null,
                null,
                null,
                null,
                List.of(new TrialConfig(1, 1, 1, null, dummyCalibrationConfigWithWeights(1))));

        IllegalArgumentException ex =
                assertThrows(IllegalArgumentException.class, () -> config.resolveImports(tempDir.toFile(), mapper));

        assertTrue(ex.getMessage().contains("Cyclic profile library import detected"));
        assertTrue(ex.getMessage().contains("a.json"));
        assertTrue(ex.getMessage().contains("b.json"));
        assertTrue(ex.getMessage().contains("c.json"));
    }

    /// Verifies diamond imports (same canonical library imported via multiple branches) do not error and reuse cache.
    @Test
    void testDiamondImportsCacheReuse(@TempDir Path tempDir) throws Exception {
        Path sharedFile = tempDir.resolve("shared.json");
        ProfileLibrary shared = new ProfileLibrary(Map.of("leaf", dummyCalibrationConfigWithWeights(55)), null);
        mapper.writeValue(sharedFile.toFile(), shared);

        Path libA = tempDir.resolve("libA.json");
        ProfileLibrary a = new ProfileLibrary(List.of(new ProfileImport("shared.json", "s")), null, null);
        mapper.writeValue(libA.toFile(), a);

        Path libB = tempDir.resolve("libB.json");
        ProfileLibrary b = new ProfileLibrary(List.of(new ProfileImport("shared.json", "s")), null, null);
        mapper.writeValue(libB.toFile(), b);

        HarnessConfig config = new HarnessConfig(
                null,
                null,
                null,
                null,
                null,
                List.of(new ProfileImport("libA.json", "a"), new ProfileImport("libB.json", "b")),
                null,
                null,
                null,
                null,
                null,
                null,
                List.of(
                        new TrialConfig(
                                "t1",
                                "name",
                                "group",
                                null,
                                null,
                                null,
                                null,
                                null,
                                true,
                                null,
                                1,
                                1,
                                1,
                                null,
                                null,
                                null,
                                "a.s.leaf",
                                null),
                        new TrialConfig(
                                "t2",
                                "name",
                                "group",
                                null,
                                null,
                                null,
                                null,
                                null,
                                true,
                                null,
                                1,
                                1,
                                1,
                                null,
                                null,
                                null,
                                "b.s.leaf",
                                null)));

        HarnessConfig withImports = config.resolveImports(tempDir.toFile(), mapper);
        HarnessConfig resolved = withImports.resolveCalibrationProfiles();
        assertEquals(55, resolved.trials().get(0).calibrationConfig().workUnits());
        assertEquals(55, resolved.trials().get(1).calibrationConfig().workUnits());
    }

    /// Verifies referencing an unknown profile in a known namespace is rejected.
    @Test
    void testUnknownNamespacedProfileRejection(@TempDir Path tempDir) throws Exception {
        Path libFile = tempDir.resolve("lib.json");
        ProfileLibrary lib = new ProfileLibrary(Map.of("existing", dummyCalibrationConfigWithWeights(1)), null);
        mapper.writeValue(libFile.toFile(), lib);

        HarnessConfig config = new HarnessConfig(
                null,
                null,
                null,
                null,
                null,
                List.of(new ProfileImport("lib.json", "common")),
                null,
                null,
                null,
                null,
                null,
                null,
                List.of(new TrialConfig(
                        "t1",
                        "name",
                        "group",
                        null,
                        null,
                        null,
                        null,
                        null,
                        true,
                        null,
                        1,
                        1,
                        1,
                        null,
                        null,
                        null,
                        "common.non_existent",
                        null)));

        HarnessConfig withImports = config.resolveImports(tempDir.toFile(), mapper);
        assertThrows(IllegalArgumentException.class, withImports::resolveCalibrationProfiles);
    }

    /// Verifies referencing an unknown namespace is rejected.
    @Test
    void testUnknownNamespaceRejection(@TempDir Path tempDir) throws Exception {
        Path libFile = tempDir.resolve("lib.json");
        ProfileLibrary lib = new ProfileLibrary(Map.of("existing", dummyCalibrationConfigWithWeights(1)), null);
        mapper.writeValue(libFile.toFile(), lib);

        assertThrows(
                IllegalArgumentException.class,
                () -> new HarnessConfig(
                        null,
                        null,
                        null,
                        null,
                        null,
                        List.of(new ProfileImport("lib.json", "common")),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        List.of(new TrialConfig(
                                "t1",
                                "name",
                                "group",
                                null,
                                null,
                                null,
                                null,
                                null,
                                true,
                                null,
                                1,
                                1,
                                1,
                                null,
                                null,
                                null,
                                "unknown_ns.existing",
                                null))));
    }

    /// Verifies sweep expansion applied after imported profile resolution maintains fully concrete, independent trials.
    @Test
    void testSweepExpansionWithImportedProfiles(@TempDir Path tempDir) throws Exception {
        Path libFile = tempDir.resolve("lib.json");
        ProfileLibrary lib = new ProfileLibrary(Map.of("base-cal", dummyCalibrationConfigWithWeights(10)), null);
        mapper.writeValue(libFile.toFile(), lib);

        TrialConfig baseTrial = new TrialConfig(
                "base-001",
                "Base Trial",
                "sweep-group",
                null,
                null,
                null,
                null,
                null,
                true,
                null,
                1,
                1,
                1,
                null,
                null,
                null,
                "ns.base-cal",
                null);

        SweepConfig sweep = new SweepConfig(
                "sweep-1",
                "base-001",
                "Sweep work units",
                true,
                1,
                "sweep-group",
                null,
                List.of(new SweepParameter(
                        "/calibrationConfig/workUnits", List.of(mapper.valueToTree(20), mapper.valueToTree(30)))));

        HarnessConfig config = new HarnessConfig(
                null,
                null,
                null,
                null,
                null,
                List.of(new ProfileImport("lib.json", "ns")),
                null,
                null,
                null,
                null,
                List.of(sweep),
                null,
                List.of(baseTrial));

        HarnessConfig withImports = config.resolveImports(tempDir.toFile(), mapper);
        HarnessConfig expanded = TrialSweepExpander.expandHarnessConfig(withImports);

        assertEquals(3, expanded.trials().size());
        assertEquals(10, expanded.trials().get(0).calibrationConfig().workUnits());
        assertEquals(20, expanded.trials().get(1).calibrationConfig().workUnits());
        assertEquals(30, expanded.trials().get(2).calibrationConfig().workUnits());

        for (TrialConfig trial : expanded.trials()) {
            assertNotNull(trial.calibrationConfig());
            assertNotNull(trial.calibrationConfig().decisionWeights());
        }

        // Verify original template is unmodified
        assertEquals(10, lib.calibrationProfiles().get("base-cal").workUnits());
    }

    /// Verifies existing configuration without imports remains unchanged and round-trips cleanly.
    @Test
    void testExistingConfigWithoutImportsRemainsUnchanged() throws Exception {
        HarnessConfig config = new HarnessConfig(
                1,
                "no-imports",
                "No Imports Config",
                "Testing compatibility",
                null,
                null,
                null,
                Map.of("local-cal", dummyCalibrationConfigWithWeights(12)),
                Map.of("local-weights", FragmentDecisionWeights.DEFAULT),
                null,
                null,
                List.of(new TrialConfig(
                        "t1",
                        "name",
                        "group",
                        null,
                        null,
                        null,
                        null,
                        null,
                        true,
                        null,
                        1,
                        1,
                        1,
                        null,
                        null,
                        null,
                        "local-cal",
                        null)));

        assertNull(config.imports());
        HarnessConfig resolved = config.resolveCalibrationProfiles();
        assertNotNull(resolved.trials().getFirst().calibrationConfig());
        assertEquals(12, resolved.trials().getFirst().calibrationConfig().workUnits());

        String json = mapper.writeValueAsString(config);
        HarnessConfig roundTrip = mapper.readValue(json, HarnessConfig.class);
        assertEquals(config, roundTrip);
    }
}
