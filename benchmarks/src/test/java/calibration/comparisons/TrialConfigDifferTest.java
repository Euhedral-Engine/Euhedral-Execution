package calibration.comparisons;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import calibration.comparisons.schema.ConfigurationDifference;
import calibration.comparisons.schema.DifferenceCategory;
import calibration.config.CalibrationBenchmarkConfig;
import calibration.config.TrialConfig;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.LongNode;
import io.euhedral_execution.core.config.FragmentDecisionWeights;
import io.euhedral_execution.core.control_plane.FragmentControlConfig.BodyCostWeights;
import io.euhedral_execution.core.control_plane.FragmentControlConfig.IdlePolicy;
import java.util.List;
import org.junit.jupiter.api.Test;

class TrialConfigDifferTest {

    private static TrialConfig baseConfig() {
        CalibrationBenchmarkConfig calConfig = new CalibrationBenchmarkConfig(
                List.of(1, 2),
                4,
                2,
                10,
                false,
                1000L,
                5000L,
                FragmentDecisionWeights.DEFAULT,
                1024,
                true,
                true,
                true,
                true,
                true,
                true);
        return new TrialConfig(
                "trial_1",
                "Trial One",
                "group_a",
                "description",
                "hypothesis",
                null,
                List.of("tag1"),
                null,
                true,
                null,
                1,
                1,
                3,
                "2s",
                "5s",
                List.of("-Xms2g"),
                null,
                calConfig);
    }

    @Test
    void testIdenticalTrialConfigProducesNoDifferences() {
        TrialConfig base = baseConfig();
        TrialConfig cand = baseConfig();
        List<ConfigurationDifference> diffs = TrialConfigDiffer.diff(base, cand);
        assertTrue(diffs.isEmpty());
    }

    @Test
    void testProductivityThresholdWeightIsPolicy() {
        assertEquals(
                DifferenceCategory.POLICY,
                TrialConfigDiffer.categorize("/calibrationConfig/productivityThresholdWeight"));
    }

    @Test
    void testIdlePolicyChangeProducesPolicyDifference() {
        TrialConfig base = baseConfig();

        FragmentDecisionWeights modifiedWeights = new FragmentDecisionWeights(
                FragmentDecisionWeights.DEFAULT.idleBodyCostWeights(), new IdlePolicy(51_000L, 0L, 0L, 0L, 0L));

        TrialConfig cand = base.withCalibrationConfig(base.calibrationConfig().withDecisionWeights(modifiedWeights));

        List<ConfigurationDifference> diffs = TrialConfigDiffer.diff(base, cand);
        assertEquals(1, diffs.size());

        ConfigurationDifference diff = diffs.getFirst();
        assertEquals("/calibrationConfig/decisionWeights/idleTimeNs/xsPark", diff.path());
        assertEquals(DifferenceCategory.POLICY, diff.category());
        assertEquals(LongNode.valueOf(50_000), diff.baselineValue());
        assertEquals(LongNode.valueOf(51_000), diff.candidateValue());
    }

    @Test
    void testBodyCostThresholdChangeIsPolicy() {
        TrialConfig base = baseConfig();

        FragmentDecisionWeights modifiedWeights = new FragmentDecisionWeights(
                new BodyCostWeights(100, 140, 220, 300), FragmentDecisionWeights.DEFAULT.idleTimeNs());

        TrialConfig cand = base.withCalibrationConfig(base.calibrationConfig().withDecisionWeights(modifiedWeights));

        List<ConfigurationDifference> diffs = TrialConfigDiffer.diff(base, cand);
        assertTrue(!diffs.isEmpty());
        for (ConfigurationDifference diff : diffs) {
            assertEquals(DifferenceCategory.POLICY, diff.category());
            assertTrue(diff.path().startsWith("/calibrationConfig/decisionWeights/idleBodyCostWeights"));
        }
    }

    @Test
    void testWorkUnitsChangeIsWorkload() {
        TrialConfig base = baseConfig();
        CalibrationBenchmarkConfig cal = base.calibrationConfig();
        CalibrationBenchmarkConfig candCal = new CalibrationBenchmarkConfig(
                cal.cpuSet(),
                cal.parallelSources(),
                cal.orderedSources(),
                50, // modified workUnits from 10 to 50
                cal.randomizeWork(),
                cal.totalRequiredExecutions(),
                cal.invocationTimeoutMillis(),
                cal.decisionWeights(),
                cal.rawSampleLimit(),
                cal.observeCycleStart(),
                cal.observeBatchProgress(),
                cal.observeBatchComplete(),
                cal.observeRawBodyCost(),
                cal.observeIdleDecision(),
                cal.observeExecDecision());
        TrialConfig cand = base.withCalibrationConfig(candCal);

        List<ConfigurationDifference> diffs = TrialConfigDiffer.diff(base, cand);
        assertEquals(1, diffs.size());
        assertEquals("/calibrationConfig/workUnits", diffs.getFirst().path());
        assertEquals(DifferenceCategory.WORKLOAD, diffs.getFirst().category());
        assertEquals(new IntNode(10), diffs.getFirst().baselineValue());
        assertEquals(new IntNode(50), diffs.getFirst().candidateValue());
    }

    @Test
    void testParallelSourcesChangeIsWorkload() {
        TrialConfig base = baseConfig();
        CalibrationBenchmarkConfig cal = base.calibrationConfig();
        CalibrationBenchmarkConfig candCal = new CalibrationBenchmarkConfig(
                cal.cpuSet(),
                8, // modified parallelSources from 4 to 8
                cal.orderedSources(),
                cal.workUnits(),
                cal.randomizeWork(),
                cal.totalRequiredExecutions(),
                cal.invocationTimeoutMillis(),
                cal.decisionWeights(),
                cal.rawSampleLimit(),
                cal.observeCycleStart(),
                cal.observeBatchProgress(),
                cal.observeBatchComplete(),
                cal.observeRawBodyCost(),
                cal.observeIdleDecision(),
                cal.observeExecDecision());
        TrialConfig cand = base.withCalibrationConfig(candCal);

        List<ConfigurationDifference> diffs = TrialConfigDiffer.diff(base, cand);
        assertEquals(1, diffs.size());
        assertEquals("/calibrationConfig/parallelSources", diffs.getFirst().path());
        assertEquals(DifferenceCategory.WORKLOAD, diffs.getFirst().category());
    }

    @Test
    void testRawSampleLimitChangeIsObservation() {
        TrialConfig base = baseConfig();
        CalibrationBenchmarkConfig cal = base.calibrationConfig();
        CalibrationBenchmarkConfig candCal = new CalibrationBenchmarkConfig(
                cal.cpuSet(),
                cal.parallelSources(),
                cal.orderedSources(),
                cal.workUnits(),
                cal.randomizeWork(),
                cal.totalRequiredExecutions(),
                cal.invocationTimeoutMillis(),
                cal.decisionWeights(),
                2048, // modified rawSampleLimit from 1024 to 2048
                cal.observeCycleStart(),
                cal.observeBatchProgress(),
                cal.observeBatchComplete(),
                cal.observeRawBodyCost(),
                cal.observeIdleDecision(),
                cal.observeExecDecision());
        TrialConfig cand = base.withCalibrationConfig(candCal);

        List<ConfigurationDifference> diffs = TrialConfigDiffer.diff(base, cand);
        assertEquals(1, diffs.size());
        assertEquals("/calibrationConfig/rawSampleLimit", diffs.getFirst().path());
        assertEquals(DifferenceCategory.OBSERVATION, diffs.getFirst().category());
    }

    @Test
    void testObservationToggleChangeIsObservation() {
        TrialConfig base = baseConfig();
        CalibrationBenchmarkConfig cal = base.calibrationConfig();
        CalibrationBenchmarkConfig candCal = new CalibrationBenchmarkConfig(
                cal.cpuSet(),
                cal.parallelSources(),
                cal.orderedSources(),
                cal.workUnits(),
                cal.randomizeWork(),
                cal.totalRequiredExecutions(),
                cal.invocationTimeoutMillis(),
                cal.decisionWeights(),
                cal.rawSampleLimit(),
                false, // observeCycleStart modified to false
                cal.observeBatchProgress(),
                cal.observeBatchComplete(),
                cal.observeRawBodyCost(),
                false, // observeIdleDecision modified to false
                cal.observeExecDecision());
        TrialConfig cand = base.withCalibrationConfig(candCal);

        List<ConfigurationDifference> diffs = TrialConfigDiffer.diff(base, cand);
        assertEquals(2, diffs.size());
        assertEquals("/calibrationConfig/observeCycleStart", diffs.get(0).path());
        assertEquals(DifferenceCategory.OBSERVATION, diffs.get(0).category());
        assertEquals("/calibrationConfig/observeIdleDecision", diffs.get(1).path());
        assertEquals(DifferenceCategory.OBSERVATION, diffs.get(1).category());
    }

    @Test
    void testIterationsChangeIsJmh() {
        TrialConfig base = baseConfig();
        TrialConfig cand = new TrialConfig(
                base.id(),
                base.name(),
                base.group(),
                base.description(),
                base.hypothesis(),
                base.comparison(),
                base.tags(),
                base.labels(),
                base.enabled(),
                base.origin(),
                base.forks(),
                base.warmups(),
                5, // iterations modified from 3 to 5
                base.warmupTime(),
                base.measurementTime(),
                base.jvmArgs(),
                base.calibrationProfile(),
                base.calibrationConfig());

        List<ConfigurationDifference> diffs = TrialConfigDiffer.diff(base, cand);
        assertEquals(1, diffs.size());
        assertEquals("/iterations", diffs.getFirst().path());
        assertEquals(DifferenceCategory.JMH, diffs.getFirst().category());
    }

    @Test
    void testJvmArgsChangeIsJvm() {
        TrialConfig base = baseConfig();
        TrialConfig cand = new TrialConfig(
                base.id(),
                base.name(),
                base.group(),
                base.description(),
                base.hypothesis(),
                base.comparison(),
                base.tags(),
                base.labels(),
                base.enabled(),
                base.origin(),
                base.forks(),
                base.warmups(),
                base.iterations(),
                base.warmupTime(),
                base.measurementTime(),
                List.of("-Xms4g"), // jvmArgs modified from -Xms2g to -Xms4g
                base.calibrationProfile(),
                base.calibrationConfig());

        List<ConfigurationDifference> diffs = TrialConfigDiffer.diff(base, cand);
        assertEquals(1, diffs.size());
        assertEquals("/jvmArgs/0", diffs.getFirst().path());
        assertEquals(DifferenceCategory.JVM, diffs.getFirst().category());
    }

    @Test
    void testMetadataOnlyChangesAreIdentity() {
        TrialConfig base = baseConfig();
        TrialConfig cand = new TrialConfig(
                "trial_2",
                "New Name",
                "group_b",
                "New desc",
                "New hyp",
                base.comparison(),
                List.of("tag2"),
                base.labels(),
                base.enabled(),
                base.origin(),
                base.forks(),
                base.warmups(),
                base.iterations(),
                base.warmupTime(),
                base.measurementTime(),
                base.jvmArgs(),
                base.calibrationProfile(),
                base.calibrationConfig());

        List<ConfigurationDifference> diffs = TrialConfigDiffer.diff(base, cand);
        assertTrue(!diffs.isEmpty());
        for (ConfigurationDifference diff : diffs) {
            assertEquals(DifferenceCategory.IDENTITY, diff.category());
        }
    }

    @Test
    void testDifferenceOutputOrderingIsDeterministic() {
        TrialConfig base = baseConfig();
        CalibrationBenchmarkConfig cal = base.calibrationConfig();
        CalibrationBenchmarkConfig candCal = new CalibrationBenchmarkConfig(
                cal.cpuSet(),
                8, // parallelSources (WORKLOAD)
                cal.orderedSources(),
                50, // workUnits (WORKLOAD)
                cal.randomizeWork(),
                cal.totalRequiredExecutions(),
                cal.invocationTimeoutMillis(),
                cal.decisionWeights(),
                2048, // rawSampleLimit (OBSERVATION)
                false, // observeCycleStart (OBSERVATION)
                cal.observeBatchProgress(),
                cal.observeBatchComplete(),
                cal.observeRawBodyCost(),
                cal.observeIdleDecision(),
                cal.observeExecDecision());

        TrialConfig cand = new TrialConfig(
                "trial_2", // id (IDENTITY)
                base.name(),
                base.group(),
                base.description(),
                base.hypothesis(),
                base.comparison(),
                base.tags(),
                base.labels(),
                base.enabled(),
                base.origin(),
                base.forks(),
                base.warmups(),
                5, // iterations (JMH)
                base.warmupTime(),
                base.measurementTime(),
                List.of("-Xms4g"), // jvmArgs (JVM)
                base.calibrationProfile(),
                candCal);

        List<ConfigurationDifference> diffs1 = TrialConfigDiffer.diff(base, cand);
        List<ConfigurationDifference> diffs2 = TrialConfigDiffer.diff(base, cand);

        assertEquals(diffs1.size(), diffs2.size());
        for (int i = 0; i < diffs1.size(); i++) {
            assertEquals(diffs1.get(i).path(), diffs2.get(i).path());
            assertEquals(diffs1.get(i).category(), diffs2.get(i).category());
        }

        // Verify strictly sorted paths
        for (int i = 0; i < diffs1.size() - 1; i++) {
            assertTrue(diffs1.get(i).path().compareTo(diffs1.get(i + 1).path()) <= 0);
        }
    }
}
