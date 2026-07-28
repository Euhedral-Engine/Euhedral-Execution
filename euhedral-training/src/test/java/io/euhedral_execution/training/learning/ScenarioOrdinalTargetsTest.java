package io.euhedral_execution.training.learning;

import static org.assertj.core.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class ScenarioOrdinalTargetsTest {
    @Test
    void encodesEveryFixedThresholdInclusively() {
        for (int output = 0; output < 9; output++) {
            double threshold = ScenarioOrdinalTargets.threshold(output);
            float[] below = new float[9], at = new float[9], above = new float[9];
            ScenarioOrdinalTargets.encode(Math.nextDown(threshold), below, 0);
            ScenarioOrdinalTargets.encode(threshold, at, 0);
            ScenarioOrdinalTargets.encode(Math.nextUp(threshold), above, 0);
            assertThat(below[output]).isZero();
            assertThat(at[output]).isOne();
            assertThat(above[output]).isOne();
        }
        float[] zero = new float[9], one = new float[9];
        ScenarioOrdinalTargets.encode(0, zero, 0);
        ScenarioOrdinalTargets.encode(1, one, 0);
        assertThat(zero).containsOnly(0);
        assertThat(one).containsOnly(1);
    }

    @Test
    void appliesExactPoolAdjacentViolatorsAndBuildsValidMasses() {
        OrdinalDistribution distribution =
                ScenarioOrdinalTargets.decode(new double[]{-2, 2, 1, 0, -1, -2, -3, -4, -5});
        assertThat(distribution.cumulativeProbabilities()[0])
                .isEqualTo(distribution.cumulativeProbabilities()[1]);
        for (double mass : distribution.binMasses()) assertThat(mass).isNotNegative();
        assertThat(java.util.Arrays.stream(distribution.binMasses()).sum())
                .isCloseTo(1, within(1.0e-15));
        assertThat(distribution.entropy()).isBetween(0.0, 1.0);
    }

    @Test
    void combinesKnownMembersWithSampleDisagreement() {
        OrdinalDistribution low = ScenarioOrdinalTargets.decode(
                new double[]{-2, -2, -2, -2, -2, -2, -2, -2, -2});
        OrdinalDistribution middle = ScenarioOrdinalTargets.decode(new double[9]);
        OrdinalDistribution high = ScenarioOrdinalTargets.decode(
                new double[]{2, 2, 2, 2, 2, 2, 2, 2, 2});
        EnsembleOrdinalDistribution ensemble =
                ScenarioOrdinalTargets.combine(List.of(low, middle, high));
        assertThat(ensemble.predictedQuality()).isCloseTo(0.5, within(1.0e-15));
        assertThat(ensemble.epistemicStdDev()).isPositive();
        assertThat(ensemble.disagreementRange()).isPositive();
        assertThat(ensemble.qualityIntervalLow())
                .isLessThanOrEqualTo(ensemble.qualityIntervalHigh());
    }

    @Test
    void exactOneHotBinsDecodeToCentersAndDiscreteIntervals() {
        for (int bin = 0; bin < 10; bin++) {
            double[] mass = new double[10];
            mass[bin] = 1;
            EnsembleOrdinalDistribution distribution =
                    ScenarioOrdinalTargets.combineAggregatedUncertainty(
                            mass, bin == 9 ? 1 : 0, 0, 0);
            double center = 0.05 + 0.10 * bin;
            assertThat(distribution.predictedQuality()).isEqualTo(center);
            assertThat(distribution.qualityIntervalLow()).isEqualTo(center);
            assertThat(distribution.qualityIntervalHigh()).isEqualTo(center);
            assertThat(distribution.ordinalEntropy()).isZero();
        }
    }

    @Test
    void knownMemberMeansUseSampleStandardDeviationAndStableMemberOrder() {
        List<OrdinalDistribution> members =
                List.of(oneHot(0), oneHot(4), oneHot(9));
        EnsembleOrdinalDistribution forward =
                ScenarioOrdinalTargets.combine(members);
        EnsembleOrdinalDistribution reverse =
                ScenarioOrdinalTargets.combine(members.reversed());
        double mean = (0.05 + 0.45 + 0.95) / 3;
        double variance = (StrictMath.pow(0.05 - mean, 2)
                + StrictMath.pow(0.45 - mean, 2)
                + StrictMath.pow(0.95 - mean, 2)) / 2;
        assertThat(forward.predictedQuality()).isEqualTo(mean);
        assertThat(forward.epistemicStdDev())
                .isCloseTo(StrictMath.sqrt(variance), within(1.0e-15));
        assertThat(forward.disagreementRange()).isEqualTo(0.90);
        assertThat(reverse.meanBinMasses())
                .containsExactly(forward.meanBinMasses());
    }

    @Test
    void rejectsNonFiniteAndWrongWidthInputs() {
        assertThatThrownBy(() -> ScenarioOrdinalTargets.decode(new double[8]))
                .isInstanceOf(IllegalArgumentException.class);
        double[] invalid = new double[9];
        invalid[3] = Double.NaN;
        assertThatThrownBy(() -> ScenarioOrdinalTargets.decode(invalid))
                .isInstanceOf(IllegalArgumentException.class);
        invalid[3] = Double.POSITIVE_INFINITY;
        assertThatThrownBy(() -> ScenarioOrdinalTargets.decode(invalid))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ScenarioOrdinalTargets.combine(List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        double[] negativeMass = new double[10];
        negativeMass[0] = 1.01;
        negativeMass[1] = -0.01;
        assertThatThrownBy(() -> ScenarioOrdinalTargets.combineAggregatedUncertainty(
                negativeMass, 0, 0, 0)).isInstanceOf(IllegalArgumentException.class);
        double[] crossing = new double[9];
        crossing[1] = 1;
        double[] mass = new double[10];
        mass[0] = 1;
        assertThatThrownBy(() -> new OrdinalDistribution(
                crossing, mass, 0.05, 0, 0, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static OrdinalDistribution oneHot(int bin) {
        double[] cumulative = new double[9];
        for (int output = 0; output < bin; output++) {
            cumulative[output] = 1;
        }
        double[] mass = new double[10];
        mass[bin] = 1;
        return new OrdinalDistribution(cumulative, mass, 0.05 + 0.10 * bin,
                0, 0, cumulative[8]);
    }
}
