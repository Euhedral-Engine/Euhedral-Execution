package io.euhedral_execution.core.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.euhedral_execution.core.frames.AbstractFrame;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.Getter;
import lombok.Setter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FrameManagerTest {

    private static final long PASSWORD = 0xDEADBEEFL;
    private FrameManager<String, TestDummyFrame> manager;
    private FrameFactory<String, TestDummyFrame> factory;

    @BeforeEach
    void setUp() {
        manager = new FrameManager<>(16, PASSWORD);
        factory = new FrameFactory<>(TestDummyFrame::new, (data, frame) -> frame.setData(data));
    }

    @Test
    void constructsWithDefaultCapacityAndPassword() {
        FrameManager<String, TestDummyFrame> defaultManager = new FrameManager<>(PASSWORD);

        assertThat(defaultManager.getCapacity()).isEqualTo(8192);
        assertThat(defaultManager.getFactory()).isNull();
        assertThat(defaultManager.getTotalRecycled()).isZero();
        assertThat(defaultManager.getRecycleQueue()).isNotNull();
        assertThat(defaultManager.getRecycleQueue().sizeLong()).isZero();
    }

    @Test
    void constructsWithSpecifiedCapacityRoundingToPowerOfTwo() {
        assertThat(new FrameManager<String, TestDummyFrame>(1000, PASSWORD).getCapacity())
                .isEqualTo(1024);
        assertThat(new FrameManager<String, TestDummyFrame>(1024, PASSWORD).getCapacity())
                .isEqualTo(1024);
        assertThat(new FrameManager<String, TestDummyFrame>(8192, PASSWORD).getCapacity())
                .isEqualTo(8192);
        assertThat(new FrameManager<String, TestDummyFrame>(1, PASSWORD).getCapacity())
                .isEqualTo(1);
        assertThat(new FrameManager<String, TestDummyFrame>(0, PASSWORD).getCapacity())
                .isEqualTo(1);
        assertThat(new FrameManager<String, TestDummyFrame>(-10, PASSWORD).getCapacity())
                .isEqualTo(1);
    }

    @Test
    void factoryManagement_setAndGetFactory() {
        assertThat(manager.getFactory()).isNull();
        manager.setFactory(factory);
        assertThat(manager.getFactory()).isSameAs(factory);
    }

    @Test
    void factoryManagement_setNullFactoryThrowsNpe() {
        assertThatThrownBy(() -> manager.setFactory(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("factory cannot be null");
    }

    @Test
    void factoryManagement_reSettingSameFactoryAllowed() {
        manager.setFactory(factory);
        manager.setFactory(factory);
        assertThat(manager.getFactory()).isSameAs(factory);
    }

    @Test
    void factoryManagement_settingDifferentFactoryThrowsIllegalStateException() {
        manager.setFactory(factory);
        FrameFactory<String, TestDummyFrame> anotherFactory =
                new FrameFactory<>(TestDummyFrame::new, (data, frame) -> frame.setData(data));

        assertThatThrownBy(() -> manager.setFactory(anotherFactory))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("FrameFactory is already set for this FrameManager");
    }

    @Test
    void recycle_enqueuesFrameUntilCapacity() {
        FrameManager<String, TestDummyFrame> smallManager = new FrameManager<>(4, PASSWORD);

        assertThat(smallManager.recycle(new TestDummyFrame(1L, "f1"))).isTrue();
        assertThat(smallManager.recycle(new TestDummyFrame(2L, "f2"))).isTrue();
        assertThat(smallManager.recycle(new TestDummyFrame(3L, "f3"))).isTrue();
        assertThat(smallManager.recycle(new TestDummyFrame(4L, "f4"))).isFalse();
    }

    @Test
    void close_clearsRecycleQueue() {
        manager.recycle(new TestDummyFrame(1L, "f1"));
        manager.recycle(new TestDummyFrame(2L, "f2"));
        assertThat(manager.getRecycleQueue().sizeLong()).isEqualTo(2);

        manager.close();
        assertThat(manager.getRecycleQueue().sizeLong()).isZero();
    }

    @Test
    void get_withIncorrectPasswordReturnsNull() {
        manager.recycle(new TestDummyFrame(1L, "f1"));

        assertThat(manager.get(12345L)).isNull();
    }

    @Test
    void get_whenEmptyReturnsNull() {
        assertThat(manager.get(PASSWORD)).isNull();
    }

    @Test
    void get_drainsFromQueueAndNullsBufferReference() {
        TestDummyFrame frame1 = new TestDummyFrame(10L, "first");
        TestDummyFrame frame2 = new TestDummyFrame(20L, "second");

        manager.recycle(frame1);
        manager.recycle(frame2);

        assertThat(manager.getTotalRecycled()).isZero();

        TestDummyFrame retrieved1 = manager.get(PASSWORD);
        assertThat(retrieved1).isSameAs(frame2);
        assertThat(manager.getTotalRecycled()).isEqualTo(2);

        TestDummyFrame retrieved2 = manager.get(PASSWORD);
        assertThat(retrieved2).isSameAs(frame1);

        assertThat(manager.get(PASSWORD)).isNull();
    }

    @Test
    void getOrCreate_withIncorrectPasswordThrowsException() {
        manager.setFactory(factory);

        assertThatThrownBy(() -> manager.getOrCreate("data", 12345L))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("Incorrect password for this FrameFactory.");
    }

    @Test
    void getOrCreate_withNullFactoryThrowsException() {
        assertThatThrownBy(() -> manager.getOrCreate("data", PASSWORD))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("Cannot generate frames with a null FrameFactory.");
    }

    @Test
    void getOrCreate_createsNewFrameWhenQueueEmpty() {
        manager.setFactory(factory);

        TestDummyFrame frame = manager.getOrCreate("created", PASSWORD);
        assertThat(frame).isNotNull();
        assertThat(frame.getData()).isEqualTo("created");
        assertThat(manager.getTotalRecycled()).isZero();
    }

    @Test
    void getOrCreate_replacesFrameWhenRecycledAvailable() {
        manager.setFactory(factory);

        TestDummyFrame recycledFrame = new TestDummyFrame(100L, "old");
        manager.recycle(recycledFrame);

        TestDummyFrame frame = manager.getOrCreate("replaced", PASSWORD);
        assertThat(frame).isSameAs(recycledFrame);
        assertThat(frame.getData()).isEqualTo("replaced");
        assertThat(manager.getTotalRecycled()).isEqualTo(1);
    }

    @Test
    void dump_withIncorrectPasswordOrInvalidMaxReturnsZero() {
        manager.recycle(new TestDummyFrame(1L, "f1"));

        assertThat(manager.dump(10, 12345L)).isZero();
        assertThat(manager.dump(0, PASSWORD)).isZero();
        assertThat(manager.dump(-5, PASSWORD)).isZero();
    }

    @Test
    void dump_drainsFromBufferAndQueue() {
        for (int i = 0; i < 5; i++) {
            manager.recycle(new TestDummyFrame(i, "frame" + i));
        }

        // Call get() to drain queue into internal buffer (drains all 5, returns top frame)
        TestDummyFrame popped = manager.get(PASSWORD);
        assertThat(popped).isNotNull();
        // Now 4 frames remain in buffer (idx = 4)

        // Dump 2 items from buffer
        long dumped1 = manager.dump(2, PASSWORD);
        assertThat(dumped1).isEqualTo(2);

        // Dump remaining from buffer
        long dumped2 = manager.dump(10, PASSWORD);
        assertThat(dumped2).isEqualTo(2);

        assertThat(manager.get(PASSWORD)).isNull();

        // Recycle 3 frames into queue
        for (int i = 0; i < 3; i++) {
            manager.recycle(new TestDummyFrame(100 + i, "newFrame" + i));
        }

        // Dump from queue directly when buffer is empty
        long dumpedQueue = manager.dump(2, PASSWORD);
        assertThat(dumpedQueue).isEqualTo(2);

        assertThat(manager.dump(10, PASSWORD)).isEqualTo(1);
    }

    @Test
    void copy_createsNewInstanceWithSameCapacityAndPassword() {
        manager.setFactory(factory);
        manager.recycle(new TestDummyFrame(1L, "f1"));

        FrameManager<String, TestDummyFrame> copy = manager.copy();

        assertThat(copy.getCapacity()).isEqualTo(manager.getCapacity());
        assertThat(copy.getFactory()).isNull();
        assertThat(copy.getTotalRecycled()).isZero();
        assertThat(copy.getRecycleQueue().sizeLong()).isZero();
        assertThat(copy.get(PASSWORD)).isNull();

        // Check password validation on copied instance
        copy.setFactory(factory);
        TestDummyFrame frameOnCopy = copy.getOrCreate("copyData", PASSWORD);
        assertThat(frameOnCopy).isNotNull();
        assertThat(frameOnCopy.getData()).isEqualTo("copyData");
    }

    @Test
    void concurrentRecycleAndGetOrCreate() throws InterruptedException {
        manager.setFactory(factory);
        int threadCount = 4;
        int perThreadItems = 100;
        int totalItems = threadCount * perThreadItems;

        try (ExecutorService executor = Executors.newFixedThreadPool(threadCount + 1)) {
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(threadCount);

            for (int t = 0; t < threadCount; t++) {
                final int threadId = t;
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        for (int i = 0; i < perThreadItems; i++) {
                            manager.recycle(new TestDummyFrame(threadId * 1000L + i, "t" + threadId + "_" + i));
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            AtomicInteger retrievedCount = new AtomicInteger(0);
            CountDownLatch consumerDone = new CountDownLatch(1);

            executor.submit(() -> {
                try {
                    startLatch.await();
                    int retrieved = 0;
                    long deadline = System.currentTimeMillis() + 5000;
                    while (retrieved < totalItems && System.currentTimeMillis() < deadline) {
                        manager.getOrCreate("processed", PASSWORD);
                        retrieved++;
                    }
                    retrievedCount.set(retrieved);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    consumerDone.countDown();
                }
            });

            startLatch.countDown();
            assertThat(doneLatch.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(consumerDone.await(5, TimeUnit.SECONDS)).isTrue();

            executor.shutdownNow();

            assertThat(retrievedCount.get()).isEqualTo(totalItems);
        }
    }

    @Setter
    @Getter
    static class TestDummyFrame extends AbstractFrame {
        private String data;

        TestDummyFrame(long idHash, String data) {
            super(idHash);
            this.data = data;
        }
    }
}
