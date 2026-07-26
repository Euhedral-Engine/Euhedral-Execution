from pathlib import Path

score_test = r'''package io.euhedral_execution.training.optimization;

import static org.assertj.core.api.Assertions.assertThat;

import io.euhedral_execution.training.optimization.CmaEsOptimizer.ScoredVector;
import io.euhedral_execution.training.utils.CommonFunctions;
import java.util.List;
import org.junit.jupiter.api.Test;

class ScoreBandSamplerTest {

    @Test
    void retainsTheConfiguredNumberFromEveryScoreBand() {
        double[] thresholds = {1, 2, 3, 4, 5, 6, 7, 8, 9};
        int[] capacities = ScoreBandSampler.topHeavyCapacities(100);
        ScoreBandSampler sampler = new ScoreBandSampler(thresholds, capacities, 123L);

        for (int i = 0; i < 10_000; i++) {
            int band = i % 10;
            double[] vector = new double[28];
            vector[band % 7] = 1.0;
            vector[7 + ((i / 10) % 7)] = 1.0;
            vector[14 + ((i / 70) % 7)] = 1.0;
            vector[21 + ((i / 490) % 7)] = 1.0;
            vector[(i / 3430) % 7] += i * 1.0e-9;
            CommonFunctions.normalizePolicyVector(vector);
            sampler.accept(vector, band + 0.5f);
        }

        List<ScoredVector> selected = sampler.finish();
        assertThat(selected).hasSize(100);
        for (int band = 0; band < 10; band++) {
            int expected = capacities[band];
            int currentBand = band;
            assertThat(selected.stream()
                    .filter(candidate -> (int) candidate.score() == currentBand)
                    .count()).isEqualTo(expected);
        }
        assertThat(capacities[9]).isGreaterThan(capacities[0]);
    }
}
'''

cma_test = r'''package io.euhedral_execution.training.optimization;

import static org.assertj.core.api.Assertions.assertThat;

import io.euhedral_execution.training.optimization.CmaEsOptimizer.MeasuredPolicy;
import io.euhedral_execution.training.optimization.CmaEsOptimizer.ScoredVector;
import io.euhedral_execution.training.utils.CommonFunctions;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class CmaEsOptimizerTest {

    @Test
    void producesFiniteNormalizedPoliciesWithFullVectorScoring() {
        String islands = System.getProperty("candidate.cmaIslands");
        String generations = System.getProperty("candidate.cmaGenerations");
        String population = System.getProperty("candidate.cmaPopulation");
        try {
            System.setProperty("candidate.cmaIslands", "2");
            System.setProperty("candidate.cmaGenerations", "3");
            System.setProperty("candidate.cmaPopulation", "16");

            Random random = new Random(7L);
            List<MeasuredPolicy> measured = new ArrayList<>();
            for (int row = 0; row < 80; row++) {
                double[] vector = new double[28];
                for (int i = 0; i < vector.length; i++) {
                    vector[i] = random.nextGaussian();
                }
                CommonFunctions.normalizePolicyVector(vector);
                double quality = vector[0] + vector[7] - vector[14] + 0.5 * vector[21];
                measured.add(new MeasuredPolicy(vector,
                        new double[]{quality - 0.2, quality - 0.1, quality,
                                quality + 0.1, quality + 0.2}));
            }

            CmaEsOptimizer optimizer = new CmaEsOptimizer();
            List<ScoredVector> generated = optimizer.optimize(measured,
                    (features, rows, scores) -> {
                        for (int row = 0; row < rows; row++) {
                            int offset = row * 28;
                            scores[row] = features[offset] + features[offset + 7]
                                    - features[offset + 14] + 0.5f * features[offset + 21];
                        }
                    }, 99L);

            assertThat(generated).hasSize(96);
            for (ScoredVector candidate : generated) {
                assertThat(Float.isFinite(candidate.score())).isTrue();
                for (int chunk = 0; chunk < 4; chunk++) {
                    double norm = 0;
                    for (int i = 0; i < 7; i++) {
                        double value = candidate.vector()[chunk * 7 + i];
                        assertThat(Double.isFinite(value)).isTrue();
                        norm += value * value;
                    }
                    assertThat(Math.sqrt(norm)).isCloseTo(1.0,
                            org.assertj.core.data.Offset.offset(1.0e-9));
                }
            }
        } finally {
            restore("candidate.cmaIslands", islands);
            restore("candidate.cmaGenerations", generations);
            restore("candidate.cmaPopulation", population);
        }
    }

    private static void restore(String property, String value) {
        if (value == null) {
            System.clearProperty(property);
        } else {
            System.setProperty(property, value);
        }
    }
}
'''

Path("euhedral-training/src/test/java/io/euhedral_execution/training/optimization").mkdir(parents=True, exist_ok=True)
Path("euhedral-training/src/test/java/io/euhedral_execution/training/optimization/ScoreBandSamplerTest.java").write_text(score_test)
Path("euhedral-training/src/test/java/io/euhedral_execution/training/optimization/CmaEsOptimizerTest.java").write_text(cma_test)

closed_loop = Path("euhedral-training/CLOSED_LOOP.md")
text = closed_loop.read_text()
anchor = """Candidate generation:\n\n"""
addition = """Candidate generation now combines three sources:\n\n- a two-pass low-discrepancy Sobol screen, sampled from every empirical classifier-score decile\n- multi-island, full-covariance CMA-ES proposals seeded by measured historical winners\n- direct unscreened Sobol vectors that bypass the classifier completely\n\nThe score-band budget is intentionally top-heavy but nonzero in every band. Candidate order is\nshuffled after selection so classifier score does not line up with thermal or temporal benchmark\ndrift. CMA-ES normalizes each seven-weight action chunk after sampling while retaining a full 28x28\ncovariance matrix, so it can learn both within-action and cross-action relationships.\n\nCandidate generation:\n\n"""
if text.count(anchor) != 1:
    raise RuntimeError("CLOSED_LOOP candidate section mismatch")
text = text.replace(anchor, addition, 1)
source_anchor = """Benchmarking:\n\n"""
source_addition = """Source-count coverage:\n\n- `benchmark.sourceCounts=1,2,4,8` sets explicit absolute source counts\n- `benchmark.sourceRatios=0.25,0.5,1.0` derives counts from available cores\n- `benchmark.sourceConfigurationsPerIteration=2` controls the rotating subset per iteration\n\nEach source count is benchmarked in a newly constructed lattice and written to a separate raw file.\nThe merger normalizes those files independently before combining equal policy vectors, preventing\nhigh-source-count raw throughput from dominating the universal policy objective. Between policy\ntrials, sources are paused behind an in-flight callback barrier and the lattice explicitly resets all\nsocket and fragment caches.\n\nBenchmarking:\n\n"""
if text.count(source_anchor) != 1:
    raise RuntimeError("CLOSED_LOOP benchmark section mismatch")
closed_loop.write_text(text.replace(source_anchor, source_addition, 1))
