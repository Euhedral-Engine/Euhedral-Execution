package io.euhedral_execution.benchmarks.cfd.frames;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.Test;

class CfdFrameLifecycleTest {
    @Test
    void replacementWaitsForBodyAndTerminalPublicationAndAcquiresPlainResults() throws Exception {
        var bodyEntered = new CountDownLatch(1);
        var releaseBody = new CountDownLatch(1);
        var publishing = new CountDownLatch(1);
        var releasePublication = new CountDownLatch(1);
        var failure = new AtomicReference<Throwable>();
        class Frame extends CfdFrame {
            int result;

            Frame() {
                super(1, null);
                prepare();
            }

            void prepare() {
                beginPreparation();
                result = 0;
                ready();
            }

            protected void executeBody() {
                bodyEntered.countDown();
                await(releaseBody);
                result = 41;
            }

            protected void publishSuccess() {
                publishing.countDown();
                await(releasePublication);
                result++;
            }

            protected long generation() {
                return 1;
            }
        }
        var frame = new Frame();
        var worker = Thread.ofPlatform().start(() -> {
            try {
                frame.execute();
                frame.doFinally();
            } catch (Throwable error) {
                failure.set(error);
                frame.doFinallyWithError(error);
            }
        });
        try {
            assertTrue(bodyEntered.await(5, TimeUnit.SECONDS));
            assertFalse(frame.isDone());
            assertThrows(IllegalStateException.class, frame::prepare);
            assertThrows(IllegalStateException.class, frame::execute);
            releaseBody.countDown();
            assertTrue(publishing.await(5, TimeUnit.SECONDS));
            assertFalse(frame.isDone());
            assertThrows(IllegalStateException.class, frame::prepare);
            releasePublication.countDown();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!frame.isDone()) {
                assertTrue(System.nanoTime() < deadline, "terminal publication timed out");
                LockSupport.parkNanos(10_000);
            }
            /// No join or latch follows the final plain write: terminal acquire publishes the result.
            frame.requireSuccess();
            assertEquals(42, frame.result);
        } finally {
            releaseBody.countDown();
            releasePublication.countDown();
            worker.join(5000);
        }
        assertFalse(worker.isAlive());
        assertNull(failure.get());
        frame.prepare();
        assertEquals(CfdFrame.Status.READY, frame.status());
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("test gate timed out");
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(error);
        }
    }
}
