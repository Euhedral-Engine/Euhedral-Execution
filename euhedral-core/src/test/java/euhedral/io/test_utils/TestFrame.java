package euhedral.io.test_utils;

import euhedral.io.control_plane.RoutingPolicy;
import euhedral.io.frames.AbstractFrame;
import euhedral.io.resource_monitoring.NumaMapper;
import euhedral.io.utils.KeyHasher;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;
import org.jctools.util.PaddedAtomicLong;

public final class TestFrame extends AbstractFrame {

    public long startNs;
    public PaddedAtomicLong countDown;
    public CountDownLatch trigger;
    public boolean ordered;

    public TestFrame(long idHash) {
        this(idHash, true);
    }

    public TestFrame(long idHash, boolean ordered) {
        super(idHash, null);
        this.ordered = ordered;
        this.setOrigin(NumaMapper.locateMe());
    }

    public static TestFrame[] generateParallel(int count) {
        final long idHash = KeyHasher.mix(ThreadLocalRandom.current().nextLong());
        TestFrame[] frames = new TestFrame[count];
        for (int i = 0; i < count; i++) {
            frames[i] = new TestFrame(idHash, false);
            frames[i].randomizeHash(KeyHasher.getHash("seed-" + i));
        }
        return frames;
    }

    public static TestFrame[] generateOrdered(int count) {
        final long idHash = KeyHasher.mix(ThreadLocalRandom.current().nextLong());
        final long orderedSeed = KeyHasher.mix(ThreadLocalRandom.current().nextLong());

        TestFrame[] frames = new TestFrame[count];
        for(int i = 0; i < count; i++) {
            frames[i] = new TestFrame(idHash, true);
            frames[i].randomizeHash(orderedSeed);
            frames[i].setRoutingPolicy(RoutingPolicy.CORE_LOCAL);
        }
        return frames;
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
        if(countDown.decrementAndGet() == 0) {
            if(trigger != null) {
                trigger.countDown();
            }
        }
    }
}
