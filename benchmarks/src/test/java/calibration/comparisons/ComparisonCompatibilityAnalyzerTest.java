package calibration.comparisons;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import calibration.comparisons.schema.ComparisonCompatibility;
import calibration.comparisons.schema.CompatibilityStatus;
import calibration.comparisons.schema.CompletedRun;
import calibration.comparisons.schema.RunArtifacts;
import calibration.comparisons.schema.RunIdentity;
import calibration.comparisons.schema.ThroughputResult;
import calibration.config.CalibrationBenchmarkConfig;
import calibration.config.TrialConfig;
import calibration.statistics.fork.SystemForkResult;
import io.euhedral_execution.core.config.FragmentDecisionWeights;
import io.euhedral_execution.core.control_plane.FragmentControlConfig.ContentionThresholds;
import io.euhedral_execution.core.control_plane.FragmentControlConfig.ExecutionPath;
import io.euhedral_execution.core.control_plane.FragmentControlConfig.ExecutionPolicy;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ComparisonCompatibilityAnalyzerTest {

    private static TrialConfig baseTrialConfig() {
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

    private static CompletedRun createCompletedRun(TrialConfig trialConfig, String scoreUnit) {
        RunIdentity id = new RunIdentity(
                trialConfig.id() != null ? trialConfig.id() : "trial",
                trialConfig.name(),
                trialConfig.group(),
                0,
                null,
                "/path/to/" + trialConfig.id());
        ThroughputResult tp = ThroughputResult.of(10000.0, 100.0, scoreUnit);
        RunArtifacts artifacts = RunArtifacts.standard("/path/to/" + trialConfig.id());
        return new CompletedRun(id, trialConfig, tp, SystemForkResult.EMPTY, List.of(), artifacts);
    }

    @Test
    void testIdenticalExecutionSetupIsCompatible() {
        CompletedRun base = createCompletedRun(baseTrialConfig(), "ops/s");
        CompletedRun cand = createCompletedRun(baseTrialConfig(), "ops/s");

        ComparisonCompatibility compat = ComparisonCompatibilityAnalyzer.analyze(base, cand);
        assertEquals(CompatibilityStatus.COMPATIBLE, compat.status());
        assertTrue(compat.isComparable());
        assertTrue(compat.differences().isEmpty());
        assertTrue(compat.reasons().isEmpty());
    }

    @Test
    void testPolicyOnlyChangeIsCompatible() {
        TrialConfig baseConfig = baseTrialConfig();

        List<ExecutionPolicy> policies = new ArrayList<>(FragmentDecisionWeights.DEFAULT.executionPolicies());
        ExecutionPolicy p4 = policies.get(4);
        policies.set(4, new ExecutionPolicy(ExecutionPath.STAGED, p4.sBody(), p4.mBody(), p4.hBody(), p4.xhBody()));
        FragmentDecisionWeights modifiedWeights = new FragmentDecisionWeights(
                FragmentDecisionWeights.DEFAULT.idleContentionThresholds(),
                FragmentDecisionWeights.DEFAULT.idleBodyCostWeights(),
                FragmentDecisionWeights.DEFAULT.idleTimeNs(),
                FragmentDecisionWeights.DEFAULT.execContentionThresholds(),
                FragmentDecisionWeights.DEFAULT.execBodyCostWeights(),
                policies);

        TrialConfig candConfig =
                baseConfig.withCalibrationConfig(baseConfig.calibrationConfig().withDecisionWeights(modifiedWeights));

        CompletedRun base = createCompletedRun(baseConfig, "ops/s");
        CompletedRun cand = createCompletedRun(candConfig, "ops/s");

        ComparisonCompatibility compat = ComparisonCompatibilityAnalyzer.analyze(base, cand);
        assertEquals(CompatibilityStatus.COMPATIBLE, compat.status());
        assertTrue(compat.isComparable());
        assertFalse(compat.differences().isEmpty());
        assertTrue(compat.reasons().isEmpty());
    }

    @Test
    void testPolicyPlusIdentityChangesRemainCompatible() {
        TrialConfig baseConfig = baseTrialConfig();

        ContentionThresholds thresholds = new ContentionThresholds(10, 20, 30, 40);
        FragmentDecisionWeights modifiedWeights = new FragmentDecisionWeights(
                thresholds,
                FragmentDecisionWeights.DEFAULT.idleBodyCostWeights(),
                FragmentDecisionWeights.DEFAULT.idleTimeNs(),
                FragmentDecisionWeights.DEFAULT.execContentionThresholds(),
                FragmentDecisionWeights.DEFAULT.execBodyCostWeights(),
                FragmentDecisionWeights.DEFAULT.executionPolicies());

        TrialConfig candConfig = new TrialConfig(
                "trial_2",
                "New Name",
                "group_b",
                "new desc",
                "new hyp",
                null,
                List.of("tag2"),
                null,
                true,
                null,
                baseConfig.forks(),
                baseConfig.warmups(),
                baseConfig.iterations(),
                baseConfig.warmupTime(),
                baseConfig.measurementTime(),
                baseConfig.jvmArgs(),
                baseConfig.calibrationProfile(),
                baseConfig.calibrationConfig().withDecisionWeights(modifiedWeights));

        CompletedRun base = createCompletedRun(baseConfig, "ops/s");
        CompletedRun cand = createCompletedRun(candConfig, "ops/s");

        ComparisonCompatibility compat = ComparisonCompatibilityAnalyzer.analyze(base, cand);
        assertEquals(CompatibilityStatus.COMPATIBLE, compat.status());
        assertTrue(compat.isComparable());
        assertTrue(compat.reasons().isEmpty());
    }

    @Test
    void testObservationOnlyDifferenceIsPartial() {
        TrialConfig baseConfig = baseTrialConfig();
        CalibrationBenchmarkConfig cal = baseConfig.calibrationConfig();
        CalibrationBenchmarkConfig candCal = new CalibrationBenchmarkConfig(
                cal.cpuSet(),
                cal.parallelSources(),
                cal.orderedSources(),
                cal.workUnits(),
                cal.randomizeWork(),
                cal.totalRequiredExecutions(),
                cal.invocationTimeoutMillis(),
                cal.decisionWeights(),
                2048, // rawSampleLimit modified
                cal.observeCycleStart(),
                cal.observeBatchProgress(),
                cal.observeBatchComplete(),
                cal.observeRawBodyCost(),
                false, // observeIdleDecision modified
                cal.observeExecDecision());

        TrialConfig candConfig = baseConfig.withCalibrationConfig(candCal);

        CompletedRun base = createCompletedRun(baseConfig, "ops/s");
        CompletedRun cand = createCompletedRun(candConfig, "ops/s");

        ComparisonCompatibility compat = ComparisonCompatibilityAnalyzer.analyze(base, cand);
        assertEquals(CompatibilityStatus.PARTIAL, compat.status());
        assertTrue(compat.isComparable());
        assertEquals(2, compat.reasons().size());
        assertTrue(compat.reasons().get(0).contains("observeIdleDecision")
                || compat.reasons().get(0).contains("rawSampleLimit"));
    }

    @Test
    void testPolicyPlusObservationDifferenceIsPartial() {
        TrialConfig baseConfig = baseTrialConfig();

        ContentionThresholds thresholds = new ContentionThresholds(10, 20, 30, 40);
        FragmentDecisionWeights modifiedWeights = new FragmentDecisionWeights(
                thresholds,
                FragmentDecisionWeights.DEFAULT.idleBodyCostWeights(),
                FragmentDecisionWeights.DEFAULT.idleTimeNs(),
                FragmentDecisionWeights.DEFAULT.execContentionThresholds(),
                FragmentDecisionWeights.DEFAULT.execBodyCostWeights(),
                FragmentDecisionWeights.DEFAULT.executionPolicies());

        CalibrationBenchmarkConfig cal = baseConfig.calibrationConfig();
        CalibrationBenchmarkConfig candCal = new CalibrationBenchmarkConfig(
                cal.cpuSet(),
                cal.parallelSources(),
                cal.orderedSources(),
                cal.workUnits(),
                cal.randomizeWork(),
                cal.totalRequiredExecutions(),
                cal.invocationTimeoutMillis(),
                modifiedWeights, // policy change
                2048, // observation change
                cal.observeCycleStart(),
                cal.observeBatchProgress(),
                cal.observeBatchComplete(),
                cal.observeRawBodyCost(),
                cal.observeIdleDecision(),
                cal.observeExecDecision());

        TrialConfig candConfig = baseConfig.withCalibrationConfig(candCal);

        CompletedRun base = createCompletedRun(baseConfig, "ops/s");
        CompletedRun cand = createCompletedRun(candConfig, "ops/s");

        ComparisonCompatibility compat = ComparisonCompatibilityAnalyzer.analyze(base, cand);
        assertEquals(CompatibilityStatus.PARTIAL, compat.status());
        assertTrue(compat.isComparable());
        assertEquals(1, compat.reasons().size());
        assertTrue(compat.reasons().getFirst().contains("rawSampleLimit"));
    }

    @Test
    void testCpuSetDifferenceIsIncompatible() {
        TrialConfig baseConfig = baseTrialConfig();
        CalibrationBenchmarkConfig cal = baseConfig.calibrationConfig();
        CalibrationBenchmarkConfig candCal = new CalibrationBenchmarkConfig(
                List.of(1, 2, 3, 4), // cpuSet changed
                cal.parallelSources(),
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

        TrialConfig candConfig = baseConfig.withCalibrationConfig(candCal);

        CompletedRun base = createCompletedRun(baseConfig, "ops/s");
        CompletedRun cand = createCompletedRun(candConfig, "ops/s");

        ComparisonCompatibility compat = ComparisonCompatibilityAnalyzer.analyze(base, cand);
        assertEquals(CompatibilityStatus.INCOMPATIBLE, compat.status());
        assertFalse(compat.isComparable());
        assertTrue(compat.reasons().stream().anyMatch(r -> r.contains("cpuSet")));
    }

    @Test
    void testSourceCountDifferenceIsIncompatible() {
        TrialConfig baseConfig = baseTrialConfig();
        CalibrationBenchmarkConfig cal = baseConfig.calibrationConfig();
        CalibrationBenchmarkConfig candCal = new CalibrationBenchmarkConfig(
                cal.cpuSet(),
                8, // parallelSources changed
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

        TrialConfig candConfig = baseConfig.withCalibrationConfig(candCal);

        CompletedRun base = createCompletedRun(baseConfig, "ops/s");
        CompletedRun cand = createCompletedRun(candConfig, "ops/s");

        ComparisonCompatibility compat = ComparisonCompatibilityAnalyzer.analyze(base, cand);
        assertEquals(CompatibilityStatus.INCOMPATIBLE, compat.status());
        assertFalse(compat.isComparable());
        assertTrue(compat.reasons().stream().anyMatch(r -> r.contains("parallelSources")));
    }

    @Test
    void testWorkUnitsDifferenceIsIncompatible() {
        TrialConfig baseConfig = baseTrialConfig();
        CalibrationBenchmarkConfig cal = baseConfig.calibrationConfig();
        CalibrationBenchmarkConfig candCal = new CalibrationBenchmarkConfig(
                cal.cpuSet(),
                cal.parallelSources(),
                cal.orderedSources(),
                100, // workUnits changed
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

        TrialConfig candConfig = baseConfig.withCalibrationConfig(candCal);

        CompletedRun base = createCompletedRun(baseConfig, "ops/s");
        CompletedRun cand = createCompletedRun(candConfig, "ops/s");

        ComparisonCompatibility compat = ComparisonCompatibilityAnalyzer.analyze(base, cand);
        assertEquals(CompatibilityStatus.INCOMPATIBLE, compat.status());
        assertFalse(compat.isComparable());
        assertTrue(compat.reasons().stream().anyMatch(r -> r.contains("workUnits")));
    }

    @Test
    void testRandomizeWorkDifferenceIsIncompatible() {
        TrialConfig baseConfig = baseTrialConfig();
        CalibrationBenchmarkConfig cal = baseConfig.calibrationConfig();
        CalibrationBenchmarkConfig candCal = new CalibrationBenchmarkConfig(
                cal.cpuSet(),
                cal.parallelSources(),
                cal.orderedSources(),
                cal.workUnits(),
                true, // randomizeWork changed from false to true
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

        TrialConfig candConfig = baseConfig.withCalibrationConfig(candCal);

        CompletedRun base = createCompletedRun(baseConfig, "ops/s");
        CompletedRun cand = createCompletedRun(candConfig, "ops/s");

        ComparisonCompatibility compat = ComparisonCompatibilityAnalyzer.analyze(base, cand);
        assertEquals(CompatibilityStatus.INCOMPATIBLE, compat.status());
        assertFalse(compat.isComparable());
        assertTrue(compat.reasons().stream().anyMatch(r -> r.contains("randomizeWork")));
    }

    @Test
    void testTotalRequiredExecutionsDifferenceIsIncompatible() {
        TrialConfig baseConfig = baseTrialConfig();
        CalibrationBenchmarkConfig cal = baseConfig.calibrationConfig();
        CalibrationBenchmarkConfig candCal = new CalibrationBenchmarkConfig(
                cal.cpuSet(),
                cal.parallelSources(),
                cal.orderedSources(),
                cal.workUnits(),
                cal.randomizeWork(),
                50000L, // totalRequiredExecutions changed
                cal.invocationTimeoutMillis(),
                cal.decisionWeights(),
                cal.rawSampleLimit(),
                cal.observeCycleStart(),
                cal.observeBatchProgress(),
                cal.observeBatchComplete(),
                cal.observeRawBodyCost(),
                cal.observeIdleDecision(),
                cal.observeExecDecision());

        TrialConfig candConfig = baseConfig.withCalibrationConfig(candCal);

        CompletedRun base = createCompletedRun(baseConfig, "ops/s");
        CompletedRun cand = createCompletedRun(candConfig, "ops/s");

        ComparisonCompatibility compat = ComparisonCompatibilityAnalyzer.analyze(base, cand);
        assertEquals(CompatibilityStatus.INCOMPATIBLE, compat.status());
        assertFalse(compat.isComparable());
        assertTrue(compat.reasons().stream().anyMatch(r -> r.contains("totalRequiredExecutions")));
    }

    @Test
    void testJmhMeasurementConfigurationDifferenceIsIncompatible() {
        TrialConfig baseConfig = baseTrialConfig();
        TrialConfig candConfig = new TrialConfig(
                baseConfig.id(),
                baseConfig.name(),
                baseConfig.group(),
                baseConfig.description(),
                baseConfig.hypothesis(),
                baseConfig.comparison(),
                baseConfig.tags(),
                baseConfig.labels(),
                baseConfig.enabled(),
                baseConfig.origin(),
                baseConfig.forks(),
                baseConfig.warmups(),
                baseConfig.iterations(),
                baseConfig.warmupTime(),
                "10s", // measurementTime changed from 5s to 10s
                baseConfig.jvmArgs(),
                baseConfig.calibrationProfile(),
                baseConfig.calibrationConfig());

        CompletedRun base = createCompletedRun(baseConfig, "ops/s");
        CompletedRun cand = createCompletedRun(candConfig, "ops/s");

        ComparisonCompatibility compat = ComparisonCompatibilityAnalyzer.analyze(base, cand);
        assertEquals(CompatibilityStatus.INCOMPATIBLE, compat.status());
        assertFalse(compat.isComparable());
        assertTrue(compat.reasons().stream().anyMatch(r -> r.contains("measurementTime")));
    }

    @Test
    void testJvmArgumentDifferenceIsIncompatible() {
        TrialConfig baseConfig = baseTrialConfig();
        TrialConfig candConfig = new TrialConfig(
                baseConfig.id(),
                baseConfig.name(),
                baseConfig.group(),
                baseConfig.description(),
                baseConfig.hypothesis(),
                baseConfig.comparison(),
                baseConfig.tags(),
                baseConfig.labels(),
                baseConfig.enabled(),
                baseConfig.origin(),
                baseConfig.forks(),
                baseConfig.warmups(),
                baseConfig.iterations(),
                baseConfig.warmupTime(),
                baseConfig.measurementTime(),
                List.of("-Xms4g", "-XX:+UseG1GC"), // jvmArgs changed
                baseConfig.calibrationProfile(),
                baseConfig.calibrationConfig());

        CompletedRun base = createCompletedRun(baseConfig, "ops/s");
        CompletedRun cand = createCompletedRun(candConfig, "ops/s");

        ComparisonCompatibility compat = ComparisonCompatibilityAnalyzer.analyze(base, cand);
        assertEquals(CompatibilityStatus.INCOMPATIBLE, compat.status());
        assertFalse(compat.isComparable());
        assertTrue(compat.reasons().stream().anyMatch(r -> r.contains("jvmArgs")));
    }

    @Test
    void testThroughputUnitDifferenceIsIncompatible() {
        TrialConfig config = baseTrialConfig();
        CompletedRun base = createCompletedRun(config, "ops/s");
        CompletedRun cand = createCompletedRun(config, "ops/ms");

        ComparisonCompatibility compat = ComparisonCompatibilityAnalyzer.analyze(base, cand);
        assertEquals(CompatibilityStatus.INCOMPATIBLE, compat.status());
        assertFalse(compat.isComparable());
        assertTrue(compat.reasons().stream().anyMatch(r -> r.contains("Throughput unit mismatch")));
    }

    @Test
    void testCompatibilityReasonsAreDeterministic() {
        TrialConfig baseConfig = baseTrialConfig();
        CalibrationBenchmarkConfig cal = baseConfig.calibrationConfig();
        CalibrationBenchmarkConfig candCal = new CalibrationBenchmarkConfig(
                List.of(1, 2, 3), // cpuSet (WORKLOAD)
                8, // parallelSources (WORKLOAD)
                cal.orderedSources(),
                50, // workUnits (WORKLOAD)
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

        TrialConfig candConfig = baseConfig.withCalibrationConfig(candCal);

        CompletedRun base = createCompletedRun(baseConfig, "ops/s");
        CompletedRun cand = createCompletedRun(candConfig, "ops/s");

        ComparisonCompatibility compat1 = ComparisonCompatibilityAnalyzer.analyze(base, cand);
        ComparisonCompatibility compat2 = ComparisonCompatibilityAnalyzer.analyze(base, cand);

        assertEquals(compat1.status(), compat2.status());
        assertEquals(compat1.reasons(), compat2.reasons());
        assertEquals(compat1.differences().size(), compat2.differences().size());
    }
}
