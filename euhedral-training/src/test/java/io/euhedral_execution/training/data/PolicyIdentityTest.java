package io.euhedral_execution.training.data;

import static org.assertj.core.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class PolicyIdentityTest {
    @Test
    void preservesRawBitsAndDefensivelyCopies() {
        double[] weights = new double[PolicyVector.WIDTH];
        for (int i = 0; i < weights.length; i++) {
            weights[i] = Double.longBitsToDouble(0x3ff0000000000000L + i);
        }
        weights[0] = 0.0;
        weights[1] = -0.0;
        PolicyVector policy = PolicyVector.of(weights);
        weights[2] = 99;
        assertThat(Double.doubleToRawLongBits(policy.weight(0))).isZero();
        assertThat(Double.doubleToRawLongBits(policy.weight(1))).isEqualTo(Long.MIN_VALUE);
        assertThat(policy.weight(2)).isNotEqualTo(99);
        assertThat(PolicyId.parse(policy.id().canonical())).isEqualTo(policy.id());
        assertThat(policy.id().canonical()).matches("p1-[0-9a-f]{16}");
    }

    @Test
    void signedZeroChangesIdentity() {
        double[] positive = new double[PolicyVector.WIDTH];
        double[] negative = positive.clone();
        negative[0] = -0.0;
        assertThat(PolicyVector.of(positive).id()).isNotEqualTo(PolicyVector.of(negative).id());
    }

    @Test
    void rejectsInvalidPoliciesAndIds() {
        assertThatIllegalArgumentException().isThrownBy(() -> PolicyVector.of(new double[27]));
        assertThatIllegalArgumentException().isThrownBy(() -> PolicyVector.of(new double[29]));
        for (double invalid : List.of(Double.NaN, Double.POSITIVE_INFINITY,
                Double.NEGATIVE_INFINITY)) {
            double[] weights = new double[PolicyVector.WIDTH];
            weights[2] = invalid;
            assertThatIllegalArgumentException().isThrownBy(() -> PolicyVector.of(weights));
        }
        assertThatIllegalArgumentException().isThrownBy(() -> PolicyId.parse("p1-1"));
        assertThatIllegalArgumentException().isThrownBy(() -> PolicyId.parse("p2-0000000000000000"));
    }

    @Test
    void ordersIdsUnsigned() {
        assertThat(new PolicyId(0).compareTo(new PolicyId(Long.MIN_VALUE))).isNegative();
        assertThat(new PolicyId(-1).compareTo(new PolicyId(1))).isPositive();
        assertThat(new PolicyId(1).canonical()).isEqualTo("p1-0000000000000001");
    }

    @Test
    void registryRejectsInjectedCollision() {
        PolicyRegistry registry = new PolicyRegistry();
        PolicyVector first = PolicyVector.of(new double[PolicyVector.WIDTH]);
        registry.register(first);
        assertThat(registry.require(first.id())).isSameAs(first);
        assertThatIllegalArgumentException().isThrownBy(
                () -> registry.require(new PolicyId(first.id().value() + 1)));
    }
}
