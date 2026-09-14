package io.euhedral_execution.benchmarks.cfd.frames;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.euhedral_execution.benchmarks.cfd.support.CfdTestRuntime;
import io.euhedral_execution.core.control_plane.ControlPlaneLattice;
import io.euhedral_execution.core.ingest.QueueIngestSink;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("integration")
@Isolated
@Execution(ExecutionMode.SAME_THREAD)
class CfdRoutingIntegrationTest {
    private ControlPlaneLattice lattice;
    private QueueIngestSink sink;

    void startRuntime(boolean singleWorker) {
        lattice = singleWorker ? CfdTestRuntime.singleWorker() : CfdTestRuntime.upToTwoWorkers();
        sink = new QueueIngestSink();
        lattice.addUpstream(sink);
        if (singleWorker) assertEquals(1, lattice.getActiveWorkers());
    }

    @AfterEach
    void closeRuntime() {
        try {
            if (sink != null) sink.complete();
        } finally {
            if (lattice != null) lattice.close();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void identicalOrderedIdsExecuteInInsertionOrderOnOneWorker(boolean singleWorker) {
        startRuntime(singleWorker);
        int[] cursor = {0};
        int[] observed = new int[256];
        long[] threads = new long[256];
        var frames = new RecordingFrame[256];
        for (int i = 0; i < frames.length; i++) {
            int index = i;
            frames[i] = new RecordingFrame(() -> {
                observed[cursor[0]++] = index;
                threads[index] = Thread.currentThread().threadId();
            });
            assertTrue(frames[i].isOrdered());
            assertTrue(sink.offer(frames[i]));
        }
        for (var frame : frames) awaitSuccess(frame);
        assertEquals(frames.length, cursor[0]);
        assertNotEquals(Thread.currentThread().threadId(), threads[0]);
        for (int i = 0; i < frames.length; i++) {
            assertEquals(i, observed[i]);
            assertEquals(threads[0], threads[i]);
        }
    }

    @Test
    void mixedHashesAllowProgressWhileAnotherFrameIsExecuting() throws Exception {
        startRuntime(false);
        assumeTrue(lattice.getActiveWorkers() >= 2, "requires two active physical-core workers");
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var progressed = new CountDownLatch(1);
        var blocked = new RecordingFrame(() -> {
            entered.countDown();
            awaitLatch(release);
        });
        /// Route the blocker before executing it. A direct pull can hold the shared source handle
        /// throughout execution, which would prevent other workers from ingesting the followers.
        assertTrue(blocked.isOrdered());
        var followers = new RecordingFrame[64];
        try {
            assertTrue(sink.offer(blocked));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            for (int i = 0; i < followers.length; i++) {
                followers[i] = new RecordingFrame(progressed::countDown);
                followers[i].randomizeHash(456 + i);
                assertFalse(followers[i].isOrdered());
                assertTrue(sink.offer(followers[i]));
            }
            assertTrue(progressed.await(5, TimeUnit.SECONDS), "another worker must execute while the first is blocked");
            assertFalse(blocked.isDone());
        } finally {
            release.countDown();
        }
        awaitSuccess(blocked);
        for (var frame : followers) awaitSuccess(frame);
    }

    private static void awaitSuccess(CfdFrame frame) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!frame.isDone()) {
            if (System.nanoTime() - deadline >= 0) fail("frame did not finish");
            LockSupport.parkNanos(10_000);
        }
        frame.requireSuccess();
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("test gate timed out");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("test gate interrupted", e);
        }
    }

    private static final class RecordingFrame extends CfdFrame {
        private final Runnable body;

        RecordingFrame(Runnable body) {
            super(1, null);
            this.body = body;
            beginPreparation();
            ready();
        }

        @Override
        protected void executeBody() {
            body.run();
        }

        @Override
        protected void publishSuccess() {}

        @Override
        protected long generation() {
            return 1;
        }
    }
}
