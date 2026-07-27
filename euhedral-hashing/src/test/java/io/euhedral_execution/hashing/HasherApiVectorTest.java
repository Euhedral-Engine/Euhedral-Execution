package io.euhedral_execution.hashing;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.jupiter.api.Test;

class HasherApiVectorTest {

    @Test
    void hashesDoubleVectorsWithoutChangingXxHashLayout() {
        double[] vector = {0.0, -0.0, 1.25, -9.5, Double.NaN, Double.POSITIVE_INFINITY};
        byte[] bytes = new byte[vector.length * Double.BYTES];
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        for (double value : vector) {
            buffer.putLong(Double.doubleToRawLongBits(value));
        }

        assertThat(HasherApi.getHash(vector)).isEqualTo(HasherApi.getHash(bytes));
    }

    @Test
    void seededVectorHashIsDeterministicAndOrderSensitive() {
        double[] first = {1, 2, 3, 4};
        double[] second = {4, 3, 2, 1};
        long seed = 42;

        assertThat(HasherApi.getHash(first, seed)).isEqualTo(HasherApi.getHash(first, seed));
        assertThat(HasherApi.getHash(first, seed)).isNotEqualTo(HasherApi.getHash(second, seed));
    }
}
