package euhedral.benchmarks.utils;

import euhedral.benchmarks.frames.NoOpFrame;
import euhedral.hardware_utils.PinnedThreadExecutor;
import euhedral.io.config.CloneConfig;
import euhedral.io.frames.AbstractFrame;
import euhedral.io.generics.AbstractExecutor;
import org.openjdk.jmh.infra.Blackhole;

public class NoOpExecutor extends AbstractExecutor {

    private final Blackhole blackhole;

    public NoOpExecutor(Blackhole blackhole) {
        this(-1, blackhole);
    }

    NoOpExecutor(int cpu, Blackhole blackhole) {
        super(cpu);
        this.blackhole = blackhole;
    }

    @Override
    public void execute(AbstractFrame frame) {
        if (frame instanceof NoOpFrame noOp) {
            noOp.cpu = super.cpu;
        }
        blackhole.consume(frame);
    }

    @Override
    public NoOpExecutor hookOnClone(int cpu) {
        return new NoOpExecutor(cpu, this.blackhole);
    }
}
