package io.euhedral_execution.benchmarks.utils;

import io.euhedral_execution.benchmarks.frames.NoOpFrame;
import io.euhedral_execution.core.frames.AbstractFrame;
import io.euhedral_execution.core.generics.AbstractExecutor;
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
