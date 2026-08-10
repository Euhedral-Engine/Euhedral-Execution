package io.euhedral_execution.hardware_utils.common;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import org.junit.jupiter.api.Test;

class UnmodifiableBitSetTest {

    @Test
    void bitSetRejectsMutationsAndClonesToAMutableCopy() {
        BitSet source = new BitSet();
        source.set(1);
        source.set(65);
        UnmodifiableBitSet set = UnmodifiableBitSet.wrap(source);
        source.set(2);

        assertEquals(2, set.cardinality());
        assertThrows(RuntimeException.class, () -> set.set(2));
        assertThrows(RuntimeException.class, () -> set.clear(1));
        assertThrows(RuntimeException.class, () -> set.and(new BitSet()));

        BitSet clone = (BitSet) set.clone();
        clone.set(2);
        assertEquals(3, clone.cardinality());
        assertEquals(2, set.cardinality());
    }

    @Test
    void doubleArrayCopiesAndIteratesOnlyTheRequestedRange() {
        UnmodifiableDoubleArray values = UnmodifiableDoubleArray.wrap(new double[] {1.0, 2.0, 3.0, 4.0});
        double[] target = new double[5];
        values.copy(target, 1, 4, 2);

        assertArrayEquals(new double[] {0.0, 3.0, 4.0, 0.0, 0.0}, target);

        List<Double> iterated = new ArrayList<>();
        values.iterate(1, 3, iterated::add);
        assertEquals(List.of(2.0, 3.0), iterated);
        assertEquals(4, values.length());
    }
}
