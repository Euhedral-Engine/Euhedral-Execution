package io.euhedral_execution.benchmarks.frames;

import io.euhedral_execution.core.frames.AbstractFrame;
import io.euhedral_execution.data_structures.atomics.PaddedLongAdder;
import io.euhedral_execution.hashing.HasherApi;
import java.util.concurrent.atomic.AtomicInteger;

public class NoOpFrame extends AbstractFrame {
    private static final AtomicInteger GENERATION = new AtomicInteger(1);

    public final PaddedLongAdder counters;

    public Runnable trigger;
    public int cpu;

    public NoOpFrame(long idHash, PaddedLongAdder counters) {
        super(idHash);
        this.counters = counters;
    }

    public static NoOpFrame[] generate(long idHash, int length, PaddedLongAdder counters) {
        return generate(idHash, length, counters, false);
    }

    public static NoOpFrame[] generate(long idHash, int length, PaddedLongAdder counters, boolean ordered) {
        long seed = HasherApi.mix(HasherApi.BASE_SEED + GENERATION.getAndIncrement());

        NoOpFrame[] frames = new NoOpFrame[length];
        for (int i = 0; i < length; i++) {
            frames[i] = new NoOpFrame(idHash, counters);
            if (!ordered) {
                frames[i].randomizeHash(seed++);
            }
        }
        return frames;
    }

    @Override
    public void doFinally() {
        if (trigger != null) {
            trigger.run();
        }
        if (counters != null) {
            counters.increment(cpu);
        }
    }
}
