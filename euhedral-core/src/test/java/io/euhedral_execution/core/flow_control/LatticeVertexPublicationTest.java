package io.euhedral_execution.core.flow_control;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.BitSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import test_utils.TestFrame;
import test_utils.TestReceiver;

class LatticeVertexPublicationTest {

    @Test
    void remapReplacesBothDirectionsWithoutMutatingRetainedSnapshot() {
        try (LatticeVertex vertex = new LatticeVertex("publication-snapshot", 5)) {
            LatticeEdge[] handles = handles(5);
            BitSet active = active(1, 4);
            vertex.setDrain(true);
            assertTrue(vertex.setDownstreamMapping(active, handles));
            LatticeVertex.RoutingState previous = snapshot(vertex);
            assertArrayEquals(new int[] {1, 4}, previous.mappings);
            assertArrayEquals(new int[] {-1, 0, -1, -1, 1}, previous.activeIndexes);

            // The caller owns its BitSet; mutating it must not edit the published generation.
            active.clear(1);
            active.set(2);
            assertArrayEquals(new int[] {1, 4}, previous.mappings);
            assertTrue(vertex.setDownstreamMapping(active, handles));
            LatticeVertex.RoutingState current = snapshot(vertex);

            assertNotSame(previous, current);
            assertNotSame(previous.mappings, current.mappings);
            assertNotSame(previous.activeIndexes, current.activeIndexes);
            assertArrayEquals(new int[] {2, 4}, current.mappings);
            assertArrayEquals(new int[] {-1, -1, 0, -1, 1}, current.activeIndexes);
            assertArrayEquals(new int[] {1, 4}, previous.mappings);
            assertArrayEquals(new int[] {-1, 0, -1, -1, 1}, previous.activeIndexes);
            assertEquals(-1, vertex.getActiveDownstreamIndex(1));
            assertEquals(0, vertex.getActiveDownstreamIndex(2));
            assertEquals(1, vertex.getActiveDownstreamIndex(4));
            assertTrue(handles[1].isClosed());
            assertFalse(handles[4].isClosed());
        }
    }

    @Test
    void externallyQuiescedRemapRoutesExactFramesToNewGeneration() throws Exception {
        CountDownLatch firstDelivered = new CountDownLatch(1);
        CountDownLatch mappingInstalled = new CountDownLatch(1);
        TestReceiver retired = new TestReceiver();
        TestReceiver replacement = new TestReceiver();
        TestFrame before = new TestFrame("before");
        TestFrame after = new TestFrame("after");
        try (LatticeVertex vertex = new LatticeVertex("publication-handoff", 5)) {
            LatticeEdge[] handles = handles(5);
            handles[1].addDownstream(retired);
            handles[2].addDownstream(replacement);
            vertex.setDrain(true);
            assertTrue(vertex.setDownstreamMapping(active(1, 4), handles));
            vertex.setDrain(false);
            LatticeEdge retainedRetiredHandle = handles[1];
            FutureTask<Void> task = new FutureTask<>(() -> {
                vertex.push(before);
                firstDelivered.countDown();
                await(mappingInstalled);
                vertex.push(after);
                return null;
            });
            Thread producer = new Thread(task, "routing-publication-producer");
            producer.start();
            try {
                await(firstDelivered);
                assertEquals(1, retired.received.size());
                assertSame(before, retired.received.getFirst());
                assertTrue(replacement.received.isEmpty());

                // The test supplies quiescence; setDrain(true) alone is not a join.
                vertex.setDrain(true);
                assertTrue(vertex.setDownstreamMapping(active(2, 4), handles));
                assertSame(retainedRetiredHandle, handles[1]);
                assertTrue(retainedRetiredHandle.isClosed());
                assertTrue(retired.completed);
                vertex.setDrain(false);
                mappingInstalled.countDown();
                task.get(5, TimeUnit.SECONDS);

                assertEquals(1, retired.received.size());
                assertSame(before, retired.received.getFirst());
                assertEquals(1, replacement.received.size());
                assertSame(after, replacement.received.getFirst());
            } finally {
                mappingInstalled.countDown();
                producer.interrupt();
                producer.join(5_000);
                assertFalse(producer.isAlive(), "Producer must terminate before fixture retirement");
            }
        }
    }

    private static LatticeVertex.RoutingState snapshot(LatticeVertex vertex) {
        return (LatticeVertex.RoutingState) LatticeVertex.ROUTING_STATE.getOpaque(vertex);
    }

    private static LatticeEdge[] handles(int count) {
        LatticeEdge[] handles = new LatticeEdge[count];
        for (int i = 0; i < count; i++) {
            handles[i] = new LatticeEdge(new AtomicBoolean());
        }
        return handles;
    }

    private static BitSet active(int... ids) {
        BitSet active = new BitSet();
        for (int id : ids) {
            active.set(id);
        }
        return active;
    }

    private static void await(CountDownLatch latch) throws InterruptedException {
        assertTrue(latch.await(5, TimeUnit.SECONDS), "Timed out waiting for routing handoff");
    }
}
