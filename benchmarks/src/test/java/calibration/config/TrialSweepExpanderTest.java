package calibration.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import calibration.config.HarnessConfig.OriginType;
import calibration.config.HarnessConfig.SweepConfig;
import calibration.config.HarnessConfig.SweepParameter;
import calibration.config.HarnessConfig.TrialConfig;
import calibration.config.HarnessConfig.TrialOrigin;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.LongNode;
import com.fasterxml.jackson.databind.node.TextNode;
import io.euhedral_execution.core.config.FragmentDecisionWeights;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TrialSweepExpanderTest {

    private ObjectMapper mapper;
    private TrialSweepExpander expander;
    private TrialConfig baseTrial;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
        expander = new TrialSweepExpander(mapper, 10_000);
        baseTrial = dummyBaseTrial("trial-001");
    }

    private TrialConfig dummyBaseTrial(String id) {
        return new TrialConfig(
                id,
                "Base Trial Name",
                "group-1",
                "desc",
                "hyp",
                null,
                List.of("tag1"),
                true,
                1,
                1,
                5,
                List.of("-Xmx1g"),
                dummyCalibrationConfig());
    }

    private CalibrationBenchmarkConfig dummyCalibrationConfig() {
        return new CalibrationBenchmarkConfig(
                4,
                2,
                100,
                true,
                1_000_000,
                60_000,
                FragmentDecisionWeights.DEFAULT,
                false,
                false,
                false,
                false,
                false,
                false);
    }

    /// Verifies single parameter sweep expansion.
    @Test
    void oneParameterSweep() {
        SweepParameter param =
                new SweepParameter("/calibrationConfig/workUnits", List.of(new IntNode(200), new IntNode(300)));
        SweepConfig sweep = new SweepConfig("sweep-1", "trial-001", List.of(param));

        List<TrialConfig> generated = expander.expandSweep(baseTrial, sweep);
        assertEquals(2, generated.size());

        assertEquals(200, generated.get(0).calibrationConfig().workUnits());
        assertEquals(300, generated.get(1).calibrationConfig().workUnits());

        assertEquals("trial-001__sweep-1__0", generated.get(0).id());
        assertEquals("trial-001__sweep-1__1", generated.get(1).id());

        assertEquals("Base Trial Name__sweep-1__0", generated.get(0).name());
        assertEquals("Base Trial Name__sweep-1__1", generated.get(1).name());

        assertEquals(Boolean.TRUE, generated.get(0).enabled());
        assertEquals(Boolean.TRUE, generated.get(1).enabled());

        TrialOrigin origin0 = generated.get(0).origin();
        assertNotNull(origin0);
        assertEquals(OriginType.SWEEP, origin0.type());
        assertEquals("sweep-1", origin0.sourceId());
        assertEquals(0, origin0.candidateIndex());

        TrialOrigin origin1 = generated.get(1).origin();
        assertNotNull(origin1);
        assertEquals(OriginType.SWEEP, origin1.type());
        assertEquals("sweep-1", origin1.sourceId());
        assertEquals(1, origin1.candidateIndex());
    }

    /// Verifies two-parameter Cartesian product expansion and ordering.
    @Test
    void twoParameterCartesianProduct() {
        SweepParameter param1 =
                new SweepParameter("/calibrationConfig/workUnits", List.of(new IntNode(10), new IntNode(20)));
        SweepParameter param2 =
                new SweepParameter("/calibrationConfig/parallelSources", List.of(new IntNode(2), new IntNode(4)));
        SweepConfig sweep = new SweepConfig("sweep-cart", "trial-001", List.of(param1, param2));

        List<TrialConfig> generated = expander.expandSweep(baseTrial, sweep);
        assertEquals(4, generated.size());

        // Cartesian product order: param1 val 0 -> param2 val 0, param2 val 1; param1 val 1 -> param2 val 0, param2 val
        // 1
        assertEquals(10, generated.get(0).calibrationConfig().workUnits());
        assertEquals(2, generated.get(0).calibrationConfig().parallelSources());
        assertEquals("trial-001__sweep-cart__0", generated.get(0).id());

        assertEquals(10, generated.get(1).calibrationConfig().workUnits());
        assertEquals(4, generated.get(1).calibrationConfig().parallelSources());
        assertEquals("trial-001__sweep-cart__1", generated.get(1).id());

        assertEquals(20, generated.get(2).calibrationConfig().workUnits());
        assertEquals(2, generated.get(2).calibrationConfig().parallelSources());
        assertEquals("trial-001__sweep-cart__2", generated.get(2).id());

        assertEquals(20, generated.get(3).calibrationConfig().workUnits());
        assertEquals(4, generated.get(3).calibrationConfig().parallelSources());
        assertEquals("trial-001__sweep-cart__3", generated.get(3).id());
    }

    /// Verifies nested object property mutation via JSON Pointer.
    @Test
    void nestedObjectProperty() {
        SweepParameter param = new SweepParameter(
                "/calibrationConfig/decisionWeights/execContentionThresholds/xsContention",
                List.of(new LongNode(500_000L), new LongNode(600_000L)));
        SweepConfig sweep = new SweepConfig("sweep-nested", "trial-001", List.of(param));

        List<TrialConfig> generated = expander.expandSweep(baseTrial, sweep);
        assertEquals(2, generated.size());

        assertEquals(
                500_000L,
                generated
                        .get(0)
                        .calibrationConfig()
                        .decisionWeights()
                        .execContentionThresholds()
                        .xsContention());
        assertEquals(
                600_000L,
                generated
                        .get(1)
                        .calibrationConfig()
                        .decisionWeights()
                        .execContentionThresholds()
                        .xsContention());
    }

    /// Verifies array element property mutation via JSON Pointer.
    @Test
    void arrayElementProperty() {
        SweepParameter param = new SweepParameter(
                "/calibrationConfig/decisionWeights/idleTimeNs/4/sPark",
                List.of(new LongNode(1000L), new LongNode(2000L)));
        SweepConfig sweep = new SweepConfig("sweep-array", "trial-001", List.of(param));

        List<TrialConfig> generated = expander.expandSweep(baseTrial, sweep);
        assertEquals(2, generated.size());

        assertEquals(
                1000L,
                generated
                        .get(0)
                        .calibrationConfig()
                        .decisionWeights()
                        .idleTimeNs()
                        .get(4)
                        .sPark());
        assertEquals(
                2000L,
                generated
                        .get(1)
                        .calibrationConfig()
                        .decisionWeights()
                        .idleTimeNs()
                        .get(4)
                        .sPark());
    }

    /// Verifies enum value property mutation (e.g. DIRECT / STAGED / SKIP).
    @Test
    void enumValueProperty() {
        SweepParameter param = new SweepParameter(
                "/calibrationConfig/decisionWeights/executionPolicies/4/mBody",
                List.of(new TextNode("DIRECT"), new TextNode("STAGED")));
        SweepConfig sweep = new SweepConfig("sweep-enum", "trial-001", List.of(param));

        List<TrialConfig> generated = expander.expandSweep(baseTrial, sweep);
        assertEquals(2, generated.size());

        assertEquals(
                io.euhedral_execution.core.control_plane.FragmentControlConfig.ExecutionPath.DIRECT,
                generated
                        .get(0)
                        .calibrationConfig()
                        .decisionWeights()
                        .executionPolicies()
                        .get(4)
                        .mBody());
        assertEquals(
                io.euhedral_execution.core.control_plane.FragmentControlConfig.ExecutionPath.STAGED,
                generated
                        .get(1)
                        .calibrationConfig()
                        .decisionWeights()
                        .executionPolicies()
                        .get(4)
                        .mBody());
    }

    /// Verifies disabled base template generates enabled candidates.
    @Test
    void disabledBaseTemplate() {
        TrialConfig disabledBase = new TrialConfig(
                "disabled-trial", null, null, null, null, null, null, false, 1, 1, 5, null, dummyCalibrationConfig());

        SweepParameter param = new SweepParameter("/calibrationConfig/workUnits", List.of(new IntNode(50)));
        SweepConfig sweep = new SweepConfig("sweep-dis", "disabled-trial", List.of(param));

        List<TrialConfig> generated = expander.expandSweep(disabledBase, sweep);
        assertEquals(1, generated.size());
        assertEquals(Boolean.TRUE, generated.get(0).enabled());
        assertEquals("disabled-trial__sweep-dis__0", generated.get(0).id());
        assertNull(generated.get(0).name());
    }

    /// Verifies invalid base trial id is rejected during HarnessConfig construction and expander expand.
    @Test
    void invalidBaseId() {
        SweepParameter param = new SweepParameter("/calibrationConfig/workUnits", List.of(new IntNode(50)));
        SweepConfig sweep = new SweepConfig("sweep-bad", "non-existent-trial", List.of(param));

        assertThrows(
                IllegalArgumentException.class,
                () -> new HarnessConfig(
                        null, null, null, null, null, null, null, null, null, List.of(sweep), List.of(baseTrial)));
        assertThrows(IllegalArgumentException.class, () -> expander.expandSweep(baseTrial, sweep));
    }

    /// Verifies invalid JSON pointer syntax is rejected.
    @Test
    void invalidPointer() {
        SweepParameter param = new SweepParameter("/calibrationConfig/foo~2bar", List.of(new IntNode(50)));
        SweepConfig sweep = new SweepConfig("sweep-bad-ptr", "trial-001", List.of(param));

        assertThrows(IllegalArgumentException.class, () -> expander.expandSweep(baseTrial, sweep));
    }

    /// Verifies pointer outside calibrationConfig is rejected.
    @Test
    void pointerOutsideCalibrationConfig() {
        SweepParameter param = new SweepParameter("/forks", List.of(new IntNode(2)));
        SweepConfig sweep = new SweepConfig("sweep-outside", "trial-001", List.of(param));

        assertThrows(IllegalArgumentException.class, () -> expander.expandSweep(baseTrial, sweep));
    }

    /// Verifies missing target path in base TrialConfig is rejected.
    @Test
    void missingTarget() {
        SweepParameter param = new SweepParameter("/calibrationConfig/nonExistentProperty", List.of(new IntNode(2)));
        SweepConfig sweep = new SweepConfig("sweep-missing", "trial-001", List.of(param));

        assertThrows(IllegalArgumentException.class, () -> expander.expandSweep(baseTrial, sweep));
    }

    /// Verifies incompatible value type that cannot deserialize back into TrialConfig is rejected.
    @Test
    void incompatibleValueType() {
        SweepParameter param = new SweepParameter("/calibrationConfig/workUnits", List.of(new TextNode("not-an-int")));
        SweepConfig sweep = new SweepConfig("sweep-incompat", "trial-001", List.of(param));

        assertThrows(IllegalArgumentException.class, () -> expander.expandSweep(baseTrial, sweep));
    }

    /// Verifies deterministic generation order across multiple executions.
    @Test
    void deterministicGenerationOrder() {
        SweepParameter param1 =
                new SweepParameter("/calibrationConfig/workUnits", List.of(new IntNode(10), new IntNode(20)));
        SweepParameter param2 =
                new SweepParameter("/calibrationConfig/parallelSources", List.of(new IntNode(2), new IntNode(4)));
        SweepConfig sweep = new SweepConfig("sweep-det", "trial-001", List.of(param1, param2));

        List<TrialConfig> run1 = expander.expandSweep(baseTrial, sweep);
        List<TrialConfig> run2 = expander.expandSweep(baseTrial, sweep);

        assertEquals(run1.size(), run2.size());
        for (int i = 0; i < run1.size(); i++) {
            assertEquals(run1.get(i).id(), run2.get(i).id());
            assertEquals(run1.get(i).name(), run2.get(i).name());
            assertEquals(run1.get(i).origin(), run2.get(i).origin());
            assertEquals(run1.get(i).calibrationConfig(), run2.get(i).calibrationConfig());
        }
    }

    /// Verifies generated trial provenance metadata and trial IDs.
    @Test
    void generatedProvenanceAndIds() {
        SweepParameter param =
                new SweepParameter("/calibrationConfig/workUnits", List.of(new IntNode(100), new IntNode(200)));
        SweepConfig sweep = new SweepConfig("sweep-prov", "trial-001", List.of(param));

        List<TrialConfig> generated = expander.expandSweep(baseTrial, sweep);
        assertEquals(2, generated.size());

        for (int i = 0; i < generated.size(); i++) {
            TrialConfig trial = generated.get(i);
            assertEquals("trial-001__sweep-prov__" + i, trial.id());
            assertEquals("Base Trial Name__sweep-prov__" + i, trial.name());
            assertNotNull(trial.origin());
            assertEquals(OriginType.SWEEP, trial.origin().type());
            assertEquals("sweep-prov", trial.origin().sourceId());
            assertNull(trial.origin().seed());
            assertEquals(i, trial.origin().candidateIndex());
        }
    }

    /// Verifies Cartesian product count exceeding maximum allowed generated limit is rejected.
    @Test
    void rejectExceedingMaxGeneratedLimit() {
        TrialSweepExpander lowLimitExpander = new TrialSweepExpander(mapper, 3);
        SweepParameter param1 =
                new SweepParameter("/calibrationConfig/workUnits", List.of(new IntNode(10), new IntNode(20)));
        SweepParameter param2 =
                new SweepParameter("/calibrationConfig/parallelSources", List.of(new IntNode(2), new IntNode(4)));
        SweepConfig sweep = new SweepConfig("sweep-limit", "trial-001", List.of(param1, param2));

        assertThrows(IllegalArgumentException.class, () -> lowLimitExpander.expandSweep(baseTrial, sweep));
    }
}
