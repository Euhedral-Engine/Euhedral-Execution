package io.euhedral_execution.hardware_utils.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class UnmodifiableDoubleArrayTest {

    @Test
    void ownsValuesAndEnforcesExactRanges() {
        double[] source = {1, -0.0, Double.NaN};
        UnmodifiableDoubleArray values = new UnmodifiableDoubleArray(source);
        source[0] = 9;
        assertEquals(1, values.get(0));
        assertEquals(new UnmodifiableDoubleArray(new double[]{1, -0.0, Double.NaN}), values);
        assertEquals(values.hashCode(), new UnmodifiableDoubleArray(
                new double[]{1, -0.0, Double.NaN}).hashCode());

        double[] target = new double[2];
        values.copy(target, 0, 2, 2);
        assertEquals(Double.NaN, target[0]);
        assertEquals(0, target[1]);
        values.copy(target, 0, 2, values.length());

        List<Double> visited = new ArrayList<>();
        values.iterate(1, 3, visited::add);
        assertEquals(List.of(-0.0, Double.NaN), visited);
        assertThrows(IndexOutOfBoundsException.class, () -> values.copy(target, -1, 1, 0));
        assertThrows(IndexOutOfBoundsException.class, () -> values.copy(target, 0, 1, -1));
        assertThrows(IndexOutOfBoundsException.class, () -> values.iterate(2, 1, ignored -> {
        }));
        assertThrows(NullPointerException.class, () -> values.iterate(0, 0, null));
    }
}
