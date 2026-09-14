package io.euhedral_execution.benchmarks.cfd.execution;

import static org.junit.jupiter.api.Assertions.*;

import io.euhedral_execution.core.frames.RunnableFrame;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.IdentityHashMap;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class EuhedralQueueReuseTest {
    @org.junit.jupiter.api.Test
    void backendsWithoutIngestSourcesNeedNoQueueStorage() {
        assertEquals(0, EuhedralBackend.sourceStorageBytes(512, 0));
        assertTrue(EuhedralBackend.sourceStorageBytes(512, 1) >= 3 * 1024 * 8L);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 512, 2500})
    void repeatedBacklogsReuseChunkArraysAfterWarmup(int frames) throws Exception {
        var queue = EuhedralBackend.sourceQueue(frames);
        Field partitions = field(queue.getClass(), "queues");
        Object partition = ((Object[]) partitions.get(queue))[0];
        Field tail = field(partition.getClass(), "tailQueue");
        var seen = Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>());
        var payload = new RunnableFrame(1, () -> {});
        int retained = 0;
        for (int generation = 0; generation < 20; generation++) {
            for (int i = 0; i < frames; i++) {
                assertTrue(queue.offer(payload));
                seen.add(tail.get(partition));
            }
            for (int i = 0; i < frames; i++) {
                assertSame(payload, queue.poll());
            }
            assertTrue(queue.isEmpty());
            if (generation == 4) {
                retained = seen.size();
            } else if (generation > 4) {
                assertEquals(retained, seen.size(), "no new chunk arrays after the pool is warm");
            }
        }
        assertEquals(1, ((Object[]) partitions.get(queue)).length);
        assertTrue(((Object[]) tail.get(partition)).length >= 1024);
        assertTrue(queue.maxPooledChunks() > 0);
    }

    private static Field field(Class<?> type, String name) throws Exception {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                Field field = current.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
                /// Queue state lives in padded superclasses.
            }
        }
        throw new NoSuchFieldException(name);
    }
}
