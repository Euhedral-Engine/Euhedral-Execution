package euhedral.io.test_utils;

import euhedral.hardware_utils.ThreadTools;
import euhedral.io.control_plane.RoutingPolicy;
import euhedral.io.frames.AbstractFrame;
import euhedral.io.utils.KeyHasher;
import euhedral.io.impl.FrameManager;
import java.util.concurrent.ThreadLocalRandom;
import org.jctools.util.PaddedAtomicLong;

public final class TestFrame extends AbstractFrame {
    public static final long PASSWORD = 123;

    public long startNs;
    public PaddedAtomicLong countDown;
    public boolean ordered;

    public TestFrame(long idHash, FrameManager<Void, TestFrame> recycler) {
        this(idHash, true, recycler);
    }

    public TestFrame(long idHash, boolean ordered, FrameManager<Void, TestFrame> recycler) {
        super(idHash, recycler);
        this.ordered = ordered;
        this.setOrigin(ThreadTools.getCpuInfo());
    }

    public static TestFrame[] generateParallel(int count) {
        FrameManager<Void, TestFrame> recycler = new FrameManager<>(count, PASSWORD);

        final long idHash = KeyHasher.mix(ThreadLocalRandom.current().nextLong());
        TestFrame[] frames = new TestFrame[count];
        for (int i = 0; i < count; i++) {
            frames[i] = new TestFrame(idHash, false, recycler);
            frames[i].randomizeHash(KeyHasher.getHash("seed-" + i));
        }
        return frames;
    }

    public static TestFrame[] generateOrdered(int count) {
        FrameManager<Void, TestFrame> recycler = new FrameManager<>(count, PASSWORD);

        final long idHash = KeyHasher.mix(ThreadLocalRandom.current().nextLong());
        final long orderedSeed = KeyHasher.mix(ThreadLocalRandom.current().nextLong());

        TestFrame[] frames = new TestFrame[count];
        for(int i = 0; i < count; i++) {
            frames[i] = new TestFrame(idHash, true, recycler);
            frames[i].randomizeHash(orderedSeed);
            frames[i].setRoutingPolicy(RoutingPolicy.CORE_LOCAL);
        }
        return frames;
    }

    @SuppressWarnings("unchecked")
    public FrameManager<Void, TestFrame> getRecycler() {
        return (FrameManager<Void, TestFrame>) recycler;
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
        if(!recycle() && countDown != null) {
            countDown.decrementAndGet();
        }
    }
}
