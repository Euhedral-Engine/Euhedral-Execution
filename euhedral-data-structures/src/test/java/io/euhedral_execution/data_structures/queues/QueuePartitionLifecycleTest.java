package io.euhedral_execution.data_structures.queues;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class QueuePartitionLifecycleTest {

    @Test
    void wholeQueueBudgetContinuesPastAStoppedPartitionWithoutConsumingItsHead() {
        PartitionedMpscQueue<Object> queue = new PartitionedMpscQueue<>(3, 4, 2);
        Object stopped = new Object();
        Object behindStop = new Object();
        Object first = new Object();
        Object second = new Object();
        Object remainder = new Object();
        assertTrue(queue.offer(0, stopped));
        assertTrue(queue.offer(0, behindStop));
        assertTrue(queue.offer(1, first));
        assertTrue(queue.offer(2, second));
        assertTrue(queue.offer(2, remainder));

        List<Object> received = new ArrayList<>();
        assertEquals(2, queue.drain(received::add, value -> value == stopped, 2));
        assertSame(first, received.get(0));
        assertSame(second, received.get(1));
        assertSame(stopped, queue.peek(0));
        assertEquals(2, queue.size(0));
        assertSame(remainder, queue.peek(2));
        assertEquals(3, queue.sizeLong());
        assertEquals(0, queue.drain(received::add, 0));
        assertEquals(0, queue.drain(received::add, -1));
        assertEquals(2, received.size());
    }

    @Test
    void quiescentPartitionClearDoesNotReplayOldPayloadsOrClearOtherPartitions() {
        PartitionedMpscQueue<Object> queue = new PartitionedMpscQueue<>(2, 4, 2);
        Object retained = new Object();
        assertTrue(queue.offer(1, retained));
        for (int generation = 0; generation < 4; generation++) {
            for (int i = 0; i < 32; i++) {
                assertTrue(queue.offer(0, new Object()));
            }
            queue.clear(0);
            assertTrue(queue.isEmpty(0));
            assertSame(retained, queue.peek(1));

            List<Object> expected = new ArrayList<>();
            for (int i = 0; i < 32; i++) {
                Object payload = new Object();
                expected.add(payload);
                assertTrue(queue.offer(0, payload));
            }
            List<Object> received = new ArrayList<>();
            assertEquals(expected.size(), queue.drain(0, received::add, Long.MAX_VALUE));
            for (int i = 0; i < expected.size(); i++) {
                assertSame(expected.get(i), received.get(i));
            }
            assertNull(queue.poll(0));
            assertSame(retained, queue.peek(1));
        }
        assertSame(retained, queue.poll(1));
        assertTrue(queue.isEmpty());
        assertEquals(Long.MAX_VALUE, queue.capacity());
    }

    @Test
    void retiredChunkIsActuallyReusedWithoutReplayingItsPreviousPayloads() {
        MpscQueue<Object> queue = new MpscQueue<>(4, 8);
        Object[] firstChunk = queue.headQueue;
        int payloadCount = firstChunk.length * 2;
        List<Object> original = new ArrayList<>();
        for (int i = 0; i < payloadCount; i++) {
            Object payload = new Object();
            original.add(payload);
            assertTrue(queue.offer(payload));
        }
        List<Object> received = new ArrayList<>();
        assertEquals(payloadCount, queue.drain(received::add, Long.MAX_VALUE));
        assertEquals(original, received);

        List<Object> replacement = new ArrayList<>();
        boolean reusedFirstChunk = false;
        for (int i = 0; i < payloadCount; i++) {
            Object payload = new Object();
            replacement.add(payload);
            assertTrue(queue.offer(payload));
            // Observe array identity only, under sequential producer/consumer ownership.
            reusedFirstChunk |= queue.tailQueue == firstChunk;
        }
        assertTrue(reusedFirstChunk, "the test must exercise actual chunk reuse");
        received.clear();
        assertEquals(payloadCount, queue.drain(received::add, Long.MAX_VALUE));
        for (int i = 0; i < replacement.size(); i++) {
            assertSame(replacement.get(i), received.get(i));
        }
        assertNull(queue.poll());
        assertTrue(queue.isEmpty());
    }
}
