package euhedral.common.io.test_utils;

import euhedral.common.io.dispatch.frames.AbstractFrame;
import euhedral.common.io.dispatch.utils.KeyHasher;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;

public final class TestFrame extends AbstractFrame {

    public long startNs;
    public CountDownLatch trigger;
    public boolean ordered;

    public TestFrame(long idHash, long destinationHash) {
        this(idHash, destinationHash, true);
    }

    public TestFrame(long idHash, long destinationHash, boolean ordered) {
        super(idHash, destinationHash, null);
        this.ordered = ordered;
    }

    public static TestFrame[] generateParallel(int count) {
        final long idHash = KeyHasher.mix(ThreadLocalRandom.current().nextLong());
        TestFrame[] frames = new TestFrame[count];
        for (int i = 0; i < count; i++) {
            frames[i] = new TestFrame(idHash, KeyHasher.getHash("seed-" + i), false);
        }
        return frames;
    }

    public static TestFrame[] generateOrdered(int count) {
        final long idHash = KeyHasher.mix(ThreadLocalRandom.current().nextLong());
        final long orderedSeed = KeyHasher.mix(ThreadLocalRandom.current().nextLong());

        TestFrame[] frames = new TestFrame[count];
        for(int i = 0; i < count; i++) {
            frames[i] = new TestFrame(idHash, orderedSeed, true);
        }
        return frames;
    }

    @Override
    public String getId() {
        return "";
    }

    @Override
    public long getSizeBytes() {
        return 64;
    }

    @Override
    public boolean isOrdered() {
        return ordered;
    }

    @Override
    public boolean isAlive() {
        return true;
    }

    @Override
    public void kill() {

    }

    @Override
    public void doFinally() {
        if(trigger != null) {
            trigger.countDown();
        }
    }
}
