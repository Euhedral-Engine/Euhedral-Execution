package io.euhedral_execution.data_structures.queues;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SingleConsumerDrainTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void stopPredicateSeesOnlyPayloadsAcrossChunkRolloverAndReuse(boolean multipleProducers) {
        BaseConcurrentQueue<Integer> queue = multipleProducers ? new MpscQueue<>(4, 4) : new SpscQueue<>(4);
        for (int generation = 0; generation < 3; generation++) {
            for (int value = 0; value < 100; value++) {
                assertTrue(queue.offer(value));
            }
            List<Integer> drained = new ArrayList<>();
            assertEquals(25, queue.drain(drained::add, value -> value == 25, 100));
            assertEquals(25, queue.peek());
            assertEquals(10, queue.drain(drained::add, value -> value < 0, 10));
            assertEquals(65, queue.drain(drained::add, value -> value < 0, 100));
            assertEquals(100, drained.size());
            for (int value = 0; value < 100; value++) {
                assertEquals(value, drained.get(value));
            }
            assertNull(queue.poll());
        }
    }
}
