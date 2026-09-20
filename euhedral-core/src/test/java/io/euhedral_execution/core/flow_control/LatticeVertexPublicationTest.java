package io.euhedral_execution.core.flow_control;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.euhedral_execution.core.frames.AbstractFrame;
import io.euhedral_execution.core.generics.LatticeReceiver;
import io.euhedral_execution.core.generics.LatticeSource;
import java.util.BitSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
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

    @Test
    void sourcePushAfterRequestReturnsUsesTheFullyLiveCurrentGeneration() throws Exception {
        TestReceiver retired = new TestReceiver();
        TestReceiver replacement = new TestReceiver();
        TestFrame frame = new TestFrame("asynchronous-after-remap");
        try (LatticeVertex vertex = new LatticeVertex("asynchronous-remap", 1)) {
            LatticeEdge retiredHandle = new LatticeEdge(vertex.getDrainFlag());
            retiredHandle.addDownstream(retired);
            vertex.setDrain(true);
            assertTrue(vertex.setDownstreamMapping(active(0), new LatticeEdge[] {retiredHandle}));
            vertex.setDrain(false);

            LatticeVertex.UpstreamInterceptor interceptor = vertex.new UpstreamInterceptor();
            DeferredSource source = new DeferredSource();
            source.addDownstream(interceptor);
            interceptor.addUpstream(source);
            assertTrue(interceptor.acquireLock());

            ExecutorService producer = Executors.newSingleThreadExecutor();
            try {
                interceptor.request(1);
                vertex.setDrain(true);
                LatticeEdge replacementHandle = new LatticeEdge(vertex.getDrainFlag());
                replacementHandle.addDownstream(replacement);
                assertTrue(vertex.setDownstreamMapping(active(0), new LatticeEdge[] {replacementHandle}));
                vertex.setDrain(false);

                producer.submit(() -> source.emit(frame)).get(5, TimeUnit.SECONDS);

                assertTrue(retiredHandle.isClosed());
                assertTrue(retired.received.isEmpty());
                assertEquals(1, replacement.received.size());
                assertSame(frame, replacement.received.getFirst());
            } finally {
                interceptor.releaseLock();
                if (!interceptor.isComplete()) {
                    interceptor.complete();
                }
                producer.shutdownNow();
                assertTrue(producer.awaitTermination(5, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void sourcePushAfterRequestReturnsAndEmptyRemapFinalizesOnTheProducerThread() throws Exception {
        TestReceiver receiver = new TestReceiver();
        try (LatticeVertex vertex = new LatticeVertex("asynchronous-empty-remap", 1)) {
            LatticeEdge handle = new LatticeEdge(vertex.getDrainFlag());
            handle.addDownstream(receiver);
            vertex.setDrain(true);
            assertTrue(vertex.setDownstreamMapping(active(0), new LatticeEdge[] {handle}));
            vertex.setDrain(false);

            LatticeVertex.UpstreamInterceptor interceptor = vertex.new UpstreamInterceptor();
            DeferredSource source = new DeferredSource();
            source.addDownstream(interceptor);
            interceptor.addUpstream(source);
            assertTrue(interceptor.acquireLock());

            ExecutorService producer = Executors.newSingleThreadExecutor();
            try {
                interceptor.request(1);
                vertex.setDrain(true);
                assertTrue(vertex.setDownstreamMapping(new BitSet(), new LatticeEdge[] {handle}));

                TrackingFrame frame = new TrackingFrame("asynchronous-after-empty-remap");
                producer.submit(() -> source.emit(frame)).get(5, TimeUnit.SECONDS);

                assertTrue(handle.isClosed());
                assertTrue(receiver.received.isEmpty());
                assertEquals(1, frame.finalizations.get());
                assertTrue(frame.failure.get() instanceof IllegalStateException);
                assertFalse(interceptor.isProductive());
            } finally {
                interceptor.releaseLock();
                if (!interceptor.isComplete()) {
                    interceptor.complete();
                }
                producer.shutdownNow();
                assertTrue(producer.awaitTermination(5, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void pushToEmptyMappingRejectsTheFrameInsteadOfSilentlyDroppingIt() {
        try (LatticeVertex vertex = new LatticeVertex("empty-map-rejection", 1)) {
            TestFrame frame = new TestFrame("empty-map");
            assertThrows(IllegalStateException.class, () -> vertex.push(frame));
        }
    }

    @Test
    void closePublishesNoActiveReverseMappings() {
        LatticeVertex vertex = new LatticeVertex("closed-empty-map", 2);
        LatticeEdge handle = new LatticeEdge(vertex.getDrainFlag());
        handle.addDownstream(new TestReceiver());
        vertex.setDrain(true);
        assertTrue(vertex.setDownstreamMapping(active(1), new LatticeEdge[] {null, handle}));

        vertex.close();

        assertEquals(-1, vertex.getActiveDownstreamIndex(0));
        assertEquals(-1, vertex.getActiveDownstreamIndex(1));
    }

    @Test
    void remapMakesTheRetiredGenerationNonAdmissibleBeforePublishingItsReplacement() throws Exception {
        try (PublicationOrderVertex vertex = new PublicationOrderVertex()) {
            TestReceiver retired = new TestReceiver();
            TestReceiver replacement = new TestReceiver();
            LatticeEdge retiredHandle = new LatticeEdge(vertex.getDrainFlag());
            retiredHandle.addDownstream(retired);
            LatticeEdge replacementHandle = new LatticeEdge(vertex.getDrainFlag());
            replacementHandle.addDownstream(replacement);
            vertex.setDrain(true);
            assertTrue(vertex.setDownstreamMapping(active(0), new LatticeEdge[] {retiredHandle}));
            vertex.expectRetirement(snapshot(vertex));

            ExecutorService executor = Executors.newFixedThreadPool(2);
            try {
                var remap = executor.submit(
                        () -> vertex.setDownstreamMapping(active(0), new LatticeEdge[] {replacementHandle}));
                assertTrue(vertex.publicationEntered.await(5, TimeUnit.SECONDS));
                var push = executor.submit(() -> vertex.push(new TestFrame("replacement")));
                assertTrue(vertex.acquisitionEntered.await(5, TimeUnit.SECONDS));
                assertFalse(push.isDone());
                assertTrue(retired.received.isEmpty());

                vertex.allowPublication.countDown();
                assertTrue(remap.get(5, TimeUnit.SECONDS));
                push.get(5, TimeUnit.SECONDS);
                assertTrue(vertex.retiredBeforePublication.get());
                assertTrue(retired.received.isEmpty());
                assertEquals(1, replacement.received.size());
            } finally {
                vertex.allowPublication.countDown();
                executor.shutdownNow();
                assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void stateLoadedBeforeReaderProtectionCannotEnterAfterQuiescentRemap() {
        TestReceiver retired = new TestReceiver();
        TestReceiver replacement = new TestReceiver();
        try (LatticeVertex vertex = new LatticeVertex("stale-reader-protection", 1)) {
            LatticeEdge retiredHandle = new LatticeEdge(vertex.getDrainFlag());
            retiredHandle.addDownstream(retired);
            LatticeEdge replacementHandle = new LatticeEdge(vertex.getDrainFlag());
            replacementHandle.addDownstream(replacement);
            vertex.setDrain(true);
            assertTrue(vertex.setDownstreamMapping(active(0), new LatticeEdge[] {retiredHandle}));

            LatticeVertex.RoutingState loadedBeforeProtection = snapshot(vertex);
            assertTrue(vertex.setDownstreamMapping(active(0), new LatticeEdge[] {replacementHandle}));

            assertTrue(retiredHandle.isClosed());
            assertFalse(vertex.tryAcquireRoutingState(loadedBeforeProtection));

            TestFrame frame = new TestFrame("replacement-after-stale-load");
            vertex.push(frame);
            assertTrue(retired.received.isEmpty());
            assertEquals(1, replacement.received.size());
            assertSame(frame, replacement.received.getFirst());
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

    private static final class DeferredSource implements LatticeSource {
        private LatticeReceiver downstream;
        private boolean complete;

        @Override
        public void addDownstream(LatticeReceiver downstream) {
            this.downstream = downstream;
        }

        @Override
        public long pull(
                Consumer<AbstractFrame> consumer, Function<AbstractFrame, Boolean> stopCondition, long demand) {
            return 0;
        }

        @Override
        public void request(long demand) {}

        private void emit(AbstractFrame frame) {
            this.downstream.push(frame);
        }

        @Override
        public void complete() {
            this.complete = true;
        }

        @Override
        public boolean isComplete() {
            return this.complete;
        }
    }

    private static final class TrackingFrame extends TestFrame {
        private final AtomicInteger finalizations = new AtomicInteger();
        private final AtomicReference<Throwable> failure = new AtomicReference<>();

        private TrackingFrame(String value) {
            super(value);
        }

        @Override
        public void doFinallyWithError(Throwable throwable) {
            this.failure.set(throwable);
            this.finalizations.incrementAndGet();
        }
    }

    private static final class PublicationOrderVertex extends LatticeVertex {
        private RoutingState expectedRetired;
        private final AtomicBoolean retiredBeforePublication = new AtomicBoolean();
        private final CountDownLatch publicationEntered = new CountDownLatch(1);
        private final CountDownLatch acquisitionEntered = new CountDownLatch(1);
        private final CountDownLatch allowPublication = new CountDownLatch(1);

        private PublicationOrderVertex() {
            super("publication-order", 1);
        }

        private void expectRetirement(RoutingState state) {
            this.expectedRetired = state;
        }

        @Override
        boolean tryAcquireRoutingState(RoutingState state) {
            if (state == this.expectedRetired) {
                this.acquisitionEntered.countDown();
            }
            return super.tryAcquireRoutingState(state);
        }

        @Override
        void publishRoutingState(RoutingState state) {
            if (this.expectedRetired != null) {
                this.retiredBeforePublication.set(this.expectedRetired.isRetired());
                this.publicationEntered.countDown();
                try {
                    await(this.allowPublication);
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(failure);
                }
            }
            super.publishRoutingState(state);
        }
    }
}
