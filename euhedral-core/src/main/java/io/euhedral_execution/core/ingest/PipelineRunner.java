package io.euhedral_execution.core.ingest;

import io.euhedral_execution.core.frames.PipelineFrame;
import io.euhedral_execution.core.impl.FrameManager;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public final class PipelineRunner<I> extends QueueIngestSink {

    private final FrameManager<I, PipelineFrame<I>> manager;
    private final long password = ThreadLocalRandom.current().nextLong();

    private final AtomicBoolean killSwitch = new AtomicBoolean(false);

    public <O> PipelineRunner(PipelineFrame.Builder<I, O> builder, Consumer<O> consumer, boolean consumeInParallel) {
        Objects.requireNonNull(builder);
        Objects.requireNonNull(consumer);

        if (consumeInParallel) {
            this.manager = builder.composeFannedOut(this, consumer, password, this.killSwitch);
        } else {
            this.manager = builder.composeFannedIn(this, consumer, password, this.killSwitch);
        }
    }

    /// Offers the data to each partition starting from 0 until it finds room. The data will then be used to execute the
    /// pipeline.
    public void run(I data) {
        Objects.requireNonNull(data);
        super.offer(this.manager.getOrCreate(data, this.password));
    }

    /// Offers the data to a random partition based on the seed. If the seed does not change, the
    /// same partition will be picked. The data will then be used to execute the pipeline.
    public void run(long randomSeed, I data) {
        Objects.requireNonNull(data);
        super.offer(randomSeed, this.manager.getOrCreate(data, this.password));
    }

    /// Offers the data to a specific partition. The data will then be used to execute the pipeline.
    public void run(int partition, I data) {
        Objects.requireNonNull(data);
        super.offer(partition, this.manager.getOrCreate(data, this.password));
    }

    @Override
    public void complete() {
        this.killSwitch.setRelease(true);
        super.complete();
    }
}
