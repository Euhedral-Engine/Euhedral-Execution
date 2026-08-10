package io.euhedral_execution.core.frames;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;

@SuppressWarnings("unused")
public class BenchmarkFrame extends AbstractFrame {

    public BenchmarkFrame(long idHash) {
        super(idHash);
    }

    public BenchmarkFrame(long idHash, AtomicBoolean killSwitch) {
        super(idHash, null, killSwitch);
    }

    public static BenchmarkFrame[] generate(int count, boolean ordered, long idHash) {
        return generate(count, ordered, idHash, null);
    }

    public static BenchmarkFrame[] generate(int count, boolean ordered, long idHash, AtomicBoolean killSwitch) {
        long seed = ThreadLocalRandom.current().nextLong();
        return generate(count, ordered, idHash, seed, killSwitch);
    }

    public static BenchmarkFrame[] generate(int count, boolean ordered, long idHash, long routingSeed) {
        return generate(count, ordered, idHash, routingSeed, null);
    }

    public static BenchmarkFrame[] generate(
            int count, boolean ordered, long idHash, long routingSeed, AtomicBoolean killSwitch) {
        if (count < 0) {
            throw new IllegalArgumentException("count must not be negative");
        }
        BenchmarkFrame[] frames = new BenchmarkFrame[count];
        for (int i = 0; i < count; i++) {
            frames[i] = new BenchmarkFrame(idHash, killSwitch);
            if (!ordered) {
                frames[i].randomizeHash(routingSeed + i);
            }
        }
        return frames;
    }
}
