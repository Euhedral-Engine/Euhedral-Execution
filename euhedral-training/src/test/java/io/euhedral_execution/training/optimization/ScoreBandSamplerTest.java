package io.euhedral_execution.training.optimization;

import static org.assertj.core.api.Assertions.assertThat;

import io.euhedral_execution.hashing.HasherApi;
import io.euhedral_execution.training.data.PolicyId;
import io.euhedral_execution.training.data.PolicyVector;
import io.euhedral_execution.training.data.SourceScenario;
import io.euhedral_execution.training.learning.data.PolicyPredictionCurve;
import io.euhedral_execution.training.learning.data.ScenarioPrediction;
import io.euhedral_execution.training.optimization.data.PredictedCandidate;
import io.euhedral_execution.training.optimization.data.PredictedPolicySummary;
import io.euhedral_execution.training.optimization.enums.CandidateOrigin;
import io.euhedral_execution.training.utils.CommonFunctions;
import java.util.List;
import org.junit.jupiter.api.Test;

class ScoreBandSamplerTest {

    @Test
    void retainsTheConfiguredNumberFromEveryScoreBand() {
        int[] capacities = ScoreBandSampler.topHeavyCapacities(100);
        ScoreBandSampler sampler = new ScoreBandSampler(100,
                new int[]{1, 1, 1, 1, 2, 2, 3, 5, 8, 16}, 123L, 0, 100);

        for (int i = 0; i < 10_000; i++) {
            int band = i % 10;
            double[] vector = new double[28];
            vector[band % 7] = 1.0;
            vector[7 + ((i / 10) % 7)] = 1.0;
            vector[14 + ((i / 70) % 7)] = 1.0;
            vector[21 + ((i / 490) % 7)] = 1.0;
            vector[(i / 3430) % 7] += i * 1.0e-9;
            CommonFunctions.normalizePolicyVector(vector);
            PredictedPolicySummary summary = summary(vector, band / 10.0 + 0.05);
            sampler.accept(new PredictedCandidate(summary.policy(), summary,
                    CandidateOrigin.SCORE_BAND));
        }

        List<PredictedCandidate> selected = sampler.finish();
        assertThat(selected).hasSize(100);
        for (int band = 0; band < 10; band++) {
            int expected = capacities[band];
            int currentBand = band;
            assertThat(selected.stream()
                    .filter(candidate -> ScoreBandSampler.band(
                            candidate.prediction().predictedWorstQuality()) == currentBand)
                    .count()).isEqualTo(expected);
        }
        assertThat(capacities[9]).isGreaterThan(capacities[0]);
    }

    @Test
    void samplingKeysIncludeIteration() {
        int[] bandWeights = {0, 0, 0, 0, 0, 0, 0, 0, 0, 1};
        List<PredictedCandidate> candidates = java.util.stream.IntStream.range(0, 128)
                .mapToObj(index -> candidate(index, 0.95))
                .collect(java.util.stream.Collectors.toMap(
                        candidate -> candidate.policy().id(),
                        java.util.function.Function.identity(),
                        (first, ignored) -> first,
                        java.util.LinkedHashMap::new))
                .values()
                .stream()
                .toList();

        ScoreBandSampler first = new ScoreBandSampler(4, bandWeights, 123L, 1, 0);
        ScoreBandSampler second = new ScoreBandSampler(4, bandWeights, 123L, 2, 0);
        candidates.forEach(first::accept);
        candidates.forEach(second::accept);

        assertThat(ids(first.finish()))
                .containsExactlyElementsOf(expectedIds(candidates, 1, 4));
        assertThat(ids(second.finish()))
                .containsExactlyElementsOf(expectedIds(candidates, 2, 4));
        assertThat(ids(first.finish())).isNotEqualTo(ids(second.finish()));
    }

    private static List<PolicyId> expectedIds(List<PredictedCandidate> candidates,
            int iteration, int limit) {
        return candidates.stream()
                .sorted((left, right) -> {
                    int result = Long.compareUnsigned(
                            samplingKey(left.policy().id(), iteration),
                            samplingKey(right.policy().id(), iteration));
                    return result != 0 ? result
                            : left.policy().id().compareTo(right.policy().id());
                })
                .limit(limit)
                .map(candidate -> candidate.policy().id())
                .toList();
    }

    private static List<PolicyId> ids(List<PredictedCandidate> candidates) {
        return candidates.stream().map(candidate -> candidate.policy().id()).toList();
    }

    private static long samplingKey(PolicyId policyId, int iteration) {
        String material = "score-band-v1\n"
                + "iteration=" + iteration + "\n"
                + "band=9\n"
                + "policy=" + policyId.canonical() + "\n";
        return HasherApi.getHash(material, 123L);
    }

    private static PredictedCandidate candidate(int index, double quality) {
        double[] vector = new double[28];
        vector[index % vector.length] = 1.0;
        vector[(index * 7 + 3) % vector.length] += index * 1.0e-6;
        CommonFunctions.normalizePolicyVector(vector);
        PredictedPolicySummary summary = summary(vector, quality);
        return new PredictedCandidate(summary.policy(), summary, CandidateOrigin.SCORE_BAND);
    }

    private static PredictedPolicySummary summary(double[] vector, double quality) {
        PolicyVector policy = PolicyVector.of(vector);
        SourceScenario scenario = SourceScenario.of("env-a", 1, 1);
        ScenarioPrediction prediction = new ScenarioPrediction(scenario, quality, 0.0, quality,
                quality, 0.0, 0.0, 0.0, 0.0);
        return new PredictedPolicySummary(
                new PolicyPredictionCurve(policy,
                        List.of(prediction)), quality, quality, quality,
                0.0, 0.0, 0.0, 0.0, 0.0, quality);
    }
}
