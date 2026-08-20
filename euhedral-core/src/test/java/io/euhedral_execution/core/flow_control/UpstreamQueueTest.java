package io.euhedral_execution.core.flow_control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import io.euhedral_execution.core.flow_control.UpstreamQueue.UpstreamHandle;
import io.euhedral_execution.core.frames.AbstractFrame;
import io.euhedral_execution.core.generics.LatticeSource;
import io.euhedral_execution.core.ingest.QueueIngestSink;
import io.euhedral_execution.core.utils.FlowThread;
import io.euhedral_execution.data_structures.atomics.PaddedAtomicLong;
import io.euhedral_execution.data_structures.queues.MpscQueue;
import io.euhedral_execution.hardware_utils.SystemInfo;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.BitSet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;
import lombok.Getter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import test_utils.TestFrame;
import test_utils.TestReceiver;

@SuppressWarnings("unchecked")
class UpstreamQueueTest {

    private UpstreamQueue queue;
    private PaddedAtomicLong count = new PaddedAtomicLong();
    private MpscQueue<UpstreamHandle> handles = new MpscQueue<>(64);
    private FlowThread.FlowContext flowContext;

    @BeforeEach
    void setup() {
        UpstreamQueue.UP_QUEUE.remove();
        FlowThread.clearContext();
        this.flowContext = FlowThread.initializeContext();

        queue = new UpstreamQueue(0, handles, count);
    }

    @AfterEach
    void cleanup() {
        UpstreamQueue.UP_QUEUE.remove();
        FlowThread.clearContext();
    }

    @Test
    void shouldCreateThreadLocalQueue() {
        MpscQueue<UpstreamHandle>[] arr = new MpscQueue[SystemInfo.getMaxCoreId() + 1];
        Arrays.fill(arr, this.handles);

        UpstreamQueue created = UpstreamQueue.get(arr, count);

        assertNotNull(created);
        assertSame(created, UpstreamQueue.UP_QUEUE.get());
    }

    @Test
    void shouldReuseThreadLocalQueue() {
        MpscQueue<UpstreamHandle>[] arr = new MpscQueue[SystemInfo.getMaxCoreId() + 1];
        Arrays.fill(arr, this.handles);

        UpstreamQueue first = UpstreamQueue.get(arr, count);
        UpstreamQueue second = UpstreamQueue.get(arr, count);

        assertSame(first, second);
    }

    @Test
    void shouldRequestFromOneUpstreamsWhenAtOrBelow32() {
        TestUpstreamHandle first = new TestUpstreamHandle();
        TestUpstreamHandle second = new TestUpstreamHandle();

        handles.offer(first);
        handles.offer(second);
        count.getAndAdd(2);

        queue.request(32);

        assertEquals(32, first.requested);
        assertEquals(0, second.requested);
    }

    @Test
    void shouldRequestWithoutBuffer() {
        TestUpstreamHandle upstream = new TestUpstreamHandle();

        handles.offer(upstream);
        count.incrementAndGet();

        queue.pull(null, frame -> false, 64);

        assertEquals(64, upstream.requested);
        assertEquals(0, upstream.pulled);
    }

    @Test
    void shouldKeepCachedAndTrueCountsAsLiveHandleCounts() {
        TestUpstreamHandle upstream = addHandle();

        assertEquals(1L, queue.getTrueUpstreamCount());
        upstream.pullResult = 0L;
        queue.pull(frame -> {}, frame -> false, 64L);

        assertEquals(1L, queue.getCachedUpCount());
        assertEquals(1L, queue.getTrueUpstreamCount());
        assertEquals(0L, queue.getProductiveHandleCount());
    }

    @Test
    void shouldInitiallyClassifyLiveHandlesAsProductive() {
        addHandle();
        addHandle();

        assertEquals(2L, queue.getProductiveHandleCount());
    }

    @Test
    void shouldRestoreProductivityAfterUsefulWorkReturns() {
        TestUpstreamHandle upstream = addHandle();
        upstream.pullResult = 0L;

        assertEquals(0L, queue.pull(frame -> {}, frame -> false, 64L));
        assertEquals(0L, queue.getProductiveHandleCount());

        upstream.pullResult = -1L;

        assertEquals(64L, queue.pull(frame -> {}, frame -> false, 64L));
        assertEquals(1L, queue.getProductiveHandleCount());
        assertEquals(0L, queue.nonproductiveCount);
    }

    @Test
    void shouldNotTreatRequestOnlyServiceAsEmptyPullEvidence() {
        TestUpstreamHandle upstream = addHandle();

        queue.request(64L);
        assertEquals(1L, queue.getProductiveHandleCount());

        upstream.pullResult = 0L;
        queue.pull(frame -> {}, frame -> false, 64L);
        queue.request(64L);

        assertEquals(0L, queue.getProductiveHandleCount());
    }

    @Test
    void shouldUseFlowThreadEvidenceForSynchronousRequestProduction() {
        CachedQueueFixture fixture = cachedQueueFixture();
        TestFrame frame = new TestFrame("requested");
        frame.randomizeHash(1L);
        fixture.sink.offer(frame);

        try {
            fixture.queue.request(64L);

            assertEquals(1L, this.flowContext.satisfiedRequest);
            assertEquals(0L, fixture.sink.size());
            assertEquals(1L, fixture.queue.getProductiveHandleCount());
        } finally {
            fixture.vertex.close();
        }
    }

    @Test
    void shouldPullWithConsumer() {
        TestUpstreamHandle upstream = new TestUpstreamHandle();

        handles.offer(upstream);
        count.incrementAndGet();

        queue.pull(frame -> {}, frame -> false, 64);

        assertEquals(0, upstream.requested);
        assertEquals(64, upstream.pulled);
    }

    /// Verifies a transient acquisition failure retains the live handle for a later pull.
    @Test
    void shouldRetainLiveHandleAfterFailedAcquisition() {
        TestUpstreamHandle upstream = new TestUpstreamHandle();
        upstream.available = false;
        handles.offer(upstream);
        count.incrementAndGet();

        assertEquals(0L, queue.pull(frame -> {}, frame -> false, 64));
        assertEquals(1L, handles.sizeLong());
        assertEquals(1L, upstream.acquisitionAttempts);
        assertTrue(queue.hasAcquireContention());
        assertEquals(UpstreamQueue.ACQUIRE_CONTENTION_SCALE, queue.getContention());

        upstream.available = true;

        assertEquals(64L, queue.pull(frame -> {}, frame -> false, 64));
        assertEquals(1L, handles.sizeLong());
        assertEquals(2L, upstream.acquisitionAttempts);
    }

    @Test
    void shouldRecordAllSuccessfulAcquisitionsAsZeroContention() {
        addHandle();

        queue.pull(frame -> {}, frame -> false, 64L);

        assertTrue(queue.hasAcquireContention());
        assertEquals(0L, queue.getContention());
        assertEquals(0.0, queue.getNormalizedAcquireContention());
    }

    @Test
    void shouldRecordOneFixedPointFractionForAMixedCycle() {
        TestUpstreamHandle unavailable = addHandle();
        unavailable.available = false;
        addHandle();

        queue.pull(frame -> {}, frame -> false, 64L);

        assertEquals(500_000L, queue.getContention());
        assertEquals(0.5, queue.getNormalizedAcquireContention());
        assertEquals(1L, unavailable.acquisitionAttempts);
    }

    @Test
    void shouldTrackAcquireDiagnosticsOnlyWhenEnabled() {
        TestUpstreamHandle unavailable = addHandle();
        unavailable.available = false;
        addHandle();

        queue.pull(frame -> {}, frame -> false, 64L);

        assertEquals(0L, queue.getContentionObservationCount());
        assertEquals(-1L, queue.getLastRawContention());
        assertEquals(-1L, queue.getLastContentionObservationNs());

        queue.setAcquireDiagnosticsEnabled(true);
        queue.pull(frame -> {}, frame -> false, 64L);

        assertEquals(1L, queue.getContentionObservationCount());
        assertEquals(500_000L, queue.getLastRawContention());
        assertTrue(queue.getLastContentionObservationNs() > 0L);
        assertEquals(1L, queue.getSuccessfulAcquisitionCount());
        assertEquals(1L, queue.getFailedAcquisitionCount());
        assertEquals(2L, queue.getTotalAcquisitionAttempts());

        queue.resetAcquireContention();

        assertEquals(0L, queue.getContentionObservationCount());
        assertEquals(-1L, queue.getLastRawContention());
        assertEquals(-1L, queue.getLastContentionObservationNs());
        assertEquals(0L, queue.getSuccessfulAcquisitionCount());
        assertEquals(0L, queue.getFailedAcquisitionCount());
        assertEquals(0L, queue.getTotalAcquisitionAttempts());
    }

    @Test
    void shouldNotUpdateContentionWithoutAnAcquisitionAttempt() {
        assertFalse(queue.hasAcquireContention());
        assertEquals(-1L, queue.getAcquireContentionOrUninitialized());
        assertTrue(Double.isNaN(queue.getNormalizedAcquireContention()));

        queue.pull(frame -> {}, frame -> false, 0L);

        assertFalse(queue.hasAcquireContention());
        addHandle();
        queue.pull(frame -> {}, frame -> false, 64L);
        queue.pull(frame -> {}, frame -> false, 0L);
        assertTrue(queue.hasAcquireContention());
        assertEquals(0L, queue.getContention());
        assertEquals(0L, queue.getAcquireContentionOrUninitialized());
    }

    @Test
    void shouldNotCountCompletedHandlesAsAcquisitionMisses() {
        TestUpstreamHandle completed = addHandle();
        completed.complete = true;

        queue.pull(frame -> {}, frame -> false, 64L);

        assertEquals(0L, completed.acquisitionAttempts);
        assertFalse(queue.hasAcquireContention());
    }

    @Test
    void shouldResetContentionValidityAndValue() {
        TestUpstreamHandle unavailable = addHandle();
        unavailable.available = false;
        queue.pull(frame -> {}, frame -> false, 64L);

        queue.resetAcquireContention();

        assertFalse(queue.hasAcquireContention());
        assertEquals(0L, queue.getContention());
        assertEquals(-1L, queue.getAcquireContentionOrUninitialized());
        assertTrue(Double.isNaN(queue.getNormalizedAcquireContention()));
    }

    @Test
    void shouldScaleRealisticAndOverflowBoundaryFractionsExactly() {
        assertEquals(0L, UpstreamQueue.scaleAcquireContention(0L, 32L));
        assertEquals(1_000_000L, UpstreamQueue.scaleAcquireContention(32L, 32L));
        assertEquals(333_333L, UpstreamQueue.scaleAcquireContention(1L, 3L));
        assertEquals(666_666L, UpstreamQueue.scaleAcquireContention(2L, 3L));

        long failures = Long.MAX_VALUE - 1L;
        long attempts = Long.MAX_VALUE;
        long expected = BigInteger.valueOf(failures)
                .multiply(BigInteger.valueOf(UpstreamQueue.ACQUIRE_CONTENTION_SCALE))
                .divide(BigInteger.valueOf(attempts))
                .longValueExact();
        assertEquals(expected, UpstreamQueue.scaleAcquireContention(failures, attempts));
        assertThrows(IllegalArgumentException.class, () -> UpstreamQueue.scaleAcquireContention(1L, 0L));
        assertThrows(IllegalArgumentException.class, () -> UpstreamQueue.scaleAcquireContention(2L, 1L));
    }

    /// Verifies unavailable live handles remain bounded to one attempt per pull cycle.
    @Test
    void shouldBoundRetriesWhileRetainingUnavailableHandles() {
        TestUpstreamHandle first = new TestUpstreamHandle();
        TestUpstreamHandle second = new TestUpstreamHandle();
        first.available = false;
        second.available = false;
        handles.offer(first);
        handles.offer(second);
        count.getAndAdd(2L);

        assertEquals(0L, queue.pull(frame -> {}, frame -> false, 64));

        assertEquals(2L, handles.sizeLong());
        assertEquals(1L, first.acquisitionAttempts);
        assertEquals(1L, second.acquisitionAttempts);
    }

    @Test
    void shouldIgnoreZeroDemand() {
        TestUpstreamHandle upstream = new TestUpstreamHandle();

        handles.offer(upstream);

        queue.request(0);

        assertEquals(0, upstream.requested);
        assertEquals(0, upstream.pulled);
    }

    @Test
    void shouldRemoveCompletedUpstreams() {
        TestUpstreamHandle upstream = new TestUpstreamHandle();

        upstream.complete = true;

        handles.offer(upstream);

        queue.request(10);

        assertEquals(0, queue.getTrueUpstreamCount());
    }

    @Test
    void shouldReconcileCompletedNonproductiveHandle() {
        TestUpstreamHandle productive = addHandle();
        TestUpstreamHandle nonproductive = addHandle();
        nonproductive.pullResult = 0L;
        queue.pull(frame -> {}, frame -> false, 4_096L);
        assertEquals(1L, queue.getProductiveHandleCount());

        nonproductive.complete(count);

        assertEquals(1L, queue.getProductiveHandleCount());
        assertEquals(1L, queue.getTrueUpstreamCount());
        assertTrue(productive.isProductive());
    }

    @Test
    void shouldPreserveProductivityWhenStopConditionPreventsObservation() {
        QueueFixture fixture = queueFixture();
        fixture.sink.offer(new TestFrame("blocked"));

        assertEquals(0L, fixture.queue.pull(frame -> {}, frame -> true, 64L));

        assertEquals(0L, this.flowContext.satisfiedPull);
        assertEquals(1L, fixture.sink.size());
        assertEquals(1L, fixture.queue.getProductiveHandleCount());
    }

    @Test
    void shouldReportQueueSignal() {
        QueueFixture productive = queueFixture();
        QueueFixture empty = queueFixture();
        productive.sink.offer(new TestFrame("work"));
        MpscQueue<UpstreamHandle> combinedHandles = new MpscQueue<>(64);
        PaddedAtomicLong combinedCount = new PaddedAtomicLong(2L);
        UpstreamQueue combined = new UpstreamQueue(0, combinedHandles, combinedCount);
        combinedHandles.offer(empty.handle);
        combinedHandles.offer(productive.handle);

        assertEquals(1L, combined.pull(frame -> {}, LatticeVertex.NO_STOP, 4_096L));

        assertEquals(2L, combined.getTrueUpstreamCount());
        assertEquals(1L, combined.getProductiveHandleCount());
        assertFalse(empty.sink.isComplete());
        assertEquals(0L, empty.sink.size());
    }

    @Test
    void shouldRequestFromHandleWithoutBuffer() {
        TestUpstreamHandle upstream = new TestUpstreamHandle();

        UpstreamQueue.drain(upstream, null, null, 123);

        assertEquals(123, upstream.requested);
        assertEquals(0, upstream.pulled);
    }

    @Test
    void shouldDefaultUnsupportedOperations() {
        UpstreamQueue.UpstreamHandle handle = new TestUpstreamHandle();

        LatticeSource source = mock(LatticeSource.class);

        handle.addUpstream(source);

        verify(source).complete();
    }

    @Test
    void shouldRejectTerminalDownstreamByDefault() {
        UpstreamQueue.UpstreamHandle handle = new TestUpstreamHandle();

        TestReceiver terminal = new TestReceiver();

        handle.addDownstream(terminal);

        assertNotNull(terminal.error);
        assertInstanceOf(IllegalStateException.class, terminal.error);

        assertEquals("Not supported", terminal.error.getMessage());
    }

    private TestUpstreamHandle addHandle() {
        TestUpstreamHandle handle = new TestUpstreamHandle();
        handles.offer(handle);
        count.incrementAndGet();
        return handle;
    }

    private static UpstreamQueue queueWith(UpstreamHandle handle, PaddedAtomicLong count) {
        MpscQueue<UpstreamHandle> handles = new MpscQueue<>(64);
        handles.offer(handle);
        return new UpstreamQueue(0, handles, count);
    }

    private static QueueFixture queueFixture() {
        QueueIngestSink sink = new QueueIngestSink();
        LatticeVertex vertex = new LatticeVertex("productive-queue-test", 1);
        vertex.downstreams[0] = new LatticeEdge(new AtomicBoolean());
        LatticeVertex.UpstreamInterceptor handle = vertex.new UpstreamInterceptor();
        handle.upstream = sink.getDelegate();
        sink.getDelegate().addDownstream(handle);
        PaddedAtomicLong count = new PaddedAtomicLong(1L);
        return new QueueFixture(sink, handle, queueWith(handle, count));
    }

    private static CachedQueueFixture cachedQueueFixture() {
        QueueIngestSink sink = new QueueIngestSink();
        LatticeVertex vertex = new LatticeVertex(
                "productive-request-test", 2, LatticeVertex.RoutingFunction.DEFAULT, 32, RoutingPolicy.ANYWHERE);
        BitSet active = new BitSet(2);
        active.set(0, 2);
        LatticeEdge[] downstreams = {new LatticeEdge(vertex.getDrainFlag()), new LatticeEdge(vertex.getDrainFlag())};
        vertex.setDrain(true);
        vertex.setDownstreamMapping(active, downstreams);
        vertex.setDrain(false);
        LatticeVertex.UpstreamInterceptor handle = vertex.new UpstreamInterceptor();
        handle.upstream = sink.getDelegate();
        sink.getDelegate().addDownstream(handle);
        PaddedAtomicLong count = new PaddedAtomicLong(1L);
        return new CachedQueueFixture(sink, vertex, queueWith(handle, count));
    }

    private record QueueFixture(QueueIngestSink sink, LatticeVertex.UpstreamInterceptor handle, UpstreamQueue queue) {}

    private record CachedQueueFixture(QueueIngestSink sink, LatticeVertex vertex, UpstreamQueue queue) {}

    static class TestUpstreamHandle extends UpstreamQueue.UpstreamHandle {

        @Getter
        long id = 0;

        long requested;
        long pulled;
        long pullCalls;
        long acquisitionAttempts;
        boolean complete;
        boolean available = true;
        boolean productive = true;
        long pullResult = -1L;

        /// Returns the configured availability while retaining the number of bounded attempts.
        @Override
        public boolean acquireLock() {
            this.acquisitionAttempts++;
            if (this.available) {
                this.productive = false;
                return true;
            }
            return false;
        }

        @Override
        public long pull(
                Consumer<AbstractFrame> consumer, Function<AbstractFrame, Boolean> stopCondition, long demand) {
            this.pullCalls++;
            this.pulled += demand;
            long result = this.pullResult < 0L ? demand : Math.min(this.pullResult, demand);
            if (result > 0L) {
                this.productive = true;
            }
            return result;
        }

        @Override
        public void request(long num) {
            this.requested += num;
        }

        @Override
        public boolean isComplete() {
            return complete;
        }

        @Override
        public boolean isProductive() {
            return this.productive;
        }

        @Override
        public void setProductivity(boolean productive) {
            this.productive = productive;
        }

        void complete(PaddedAtomicLong count) {
            if (!this.complete) {
                this.complete = true;
                count.decrementAndGet();
            }
        }

        @Override
        public void push(AbstractFrame frame) {
            // Test
        }

        @Override
        public void onError(Throwable throwable) {
            // Test
        }
    }
}
