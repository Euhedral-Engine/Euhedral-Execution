package io.euhedral_execution.core.control_plane;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.euhedral_execution.core.config.IdlePolicy;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class ParticipationLogisticModelTest {
    private static final String FIXTURE = "io/euhedral_execution/core/control_plane/participation_logistic_parity.tsv";

    @Test
    void generatedScoreAndActionsMatchPythonFixtureExactly() throws Exception {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                getClass().getClassLoader().getResourceAsStream(FIXTURE), StandardCharsets.US_ASCII))) {
            String header = reader.readLine();
            assertTrue(header.endsWith("feature10"));
            String line;
            int rows = 0;
            boolean sawCache = false;
            boolean sawDefault = false;
            boolean sawR7 = false;
            boolean sawR15 = false;
            boolean sawR23 = false;
            while ((line = reader.readLine()) != null) {
                String[] fields = line.split("\\t");
                int k = Integer.parseInt(fields[1]);
                long productive = Long.parseLong(fields[2]);
                int workers = Integer.parseInt(fields[3]);
                double bodyCost = Double.parseDouble(fields[4]);
                double contention = Double.parseDouble(fields[5]);
                double pythonScore = Double.parseDouble(fields[6]);
                boolean pythonCache = fields[7].equals("CACHE");

                double javaScore = ParticipationLogisticModel.score(k, productive, workers, bodyCost, contention);
                assertEquals(pythonScore, javaScore, 2e-12, fields[0]);
                assertEquals(
                        pythonCache,
                        ParticipationLogisticModel.shouldIdle(k, productive, workers, bodyCost, contention),
                        fields[0]);

                double[] z = new double[5];
                z[0] = (k - 4.319044216248458) / 4.1181433661913065;
                z[1] = (productive / (double) workers - 0.4622763757547462) / 0.33724380301220946;
                z[2] = (Math.log(workers) - 2.667804090854188) / 0.4901351282564824;
                z[3] = (Math.log1p(bodyCost) - 4.65009657333485) / 0.9912481295736798;
                z[4] = (contention - 0.4474590877565059) / 0.38529526416306775;
                for (int index = 0; index < z.length; index++) {
                    assertEquals(Double.parseDouble(fields[8 + index]), z[index], 1e-14, fields[0]);
                }
                int[][] interactions = {{0, 1}, {0, 3}, {0, 4}, {3, 1}, {4, 1}, {3, 4}};
                for (int index = 0; index < interactions.length; index++) {
                    int[] pair = interactions[index];
                    assertEquals(Double.parseDouble(fields[13 + index]), z[pair[0]] * z[pair[1]], 1e-14, fields[0]);
                }
                sawCache |= pythonCache;
                sawDefault |= !pythonCache;
                sawR7 |= workers == 7;
                sawR15 |= workers == 15;
                sawR23 |= workers == 23;
                rows++;
            }
            assertTrue(rows >= 18);
            assertTrue(sawCache && sawDefault && sawR7 && sawR15 && sawR23);
        }
    }

    @Test
    void probabilityThresholdHasEquivalentLogitThresholdWithoutSigmoid() {
        assertEquals(
                Math.log(ParticipationLogisticModel.PROBABILITY_THRESHOLD
                        / (1.0 - ParticipationLogisticModel.PROBABILITY_THRESHOLD)),
                ParticipationLogisticModel.LOGIT_THRESHOLD,
                0.0);
        assertFalse(ParticipationLogisticModel.shouldIdle(2, 1L, 7, 1.0, 0.431856));
        assertTrue(ParticipationLogisticModel.shouldIdle(2, 1L, 7, 1.0, 0.431857));
    }

    @Test
    void modelOnlyOverridesTheIdleBranch() {
        FragmentDecisionTree tree = new FragmentDecisionTree(IdlePolicy.DEFAULT, 1_000L, 1_000L);
        for (int index = 0; index < 32; index++) {
            tree.recordBodyCost(1L);
        }

        assertFalse(tree.shouldIdle(431_856L, 1L, 7, 2));
        assertTrue(tree.shouldIdle(431_857L, 1L, 7, 2));
    }
}
