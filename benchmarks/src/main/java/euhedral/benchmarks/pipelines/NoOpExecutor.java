package euhedral.benchmarks.pipelines;

import euhedral.benchmarks.frames.NoOpFrame;
import euhedral.hardware_utils.PinnedThreadExecutor;
import euhedral.io.config.CloneConfig;
import euhedral.io.frames.AbstractFrame;
import euhedral.io.generics.AbstractExecutor;
import org.openjdk.jmh.infra.Blackhole;

public class NoOpExecutor extends AbstractExecutor {

    private final Blackhole blackhole;

    public NoOpExecutor(Blackhole blackhole) {
        this(null, blackhole);
    }

    NoOpExecutor(PinnedThreadExecutor executor, Blackhole blackhole) {
        super(executor);
        this.blackhole = blackhole;
    }

    @Override
    public void execute(AbstractFrame frame) {
        if (frame instanceof NoOpFrame noOp) {
            noOp.cpu = this.executorService.getCpu();
        }
        blackhole.consume(frame);
    }

    @Override
    public NoOpExecutor clone(CloneConfig cloneConfig) {
        return new NoOpExecutor(super.executorService, this.blackhole);
    }

    @Override
    public NoOpExecutor clone(CloneConfig cloneConfig,
            PinnedThreadExecutor executor) {
        return new NoOpExecutor(executor, this.blackhole);
    }
}
