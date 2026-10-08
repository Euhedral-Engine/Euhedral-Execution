package io.euhedral_execution.core.ingest;

import io.euhedral_execution.core.frames.AbstractFrame;
import io.euhedral_execution.core.frames.PipelineFrame;
import io.euhedral_execution.core.impl.FrameManager;
import io.euhedral_execution.data_structures.queues.PartitionedMpscQueue;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/// A single-submission-owner pipeline with end-to-end graceful completion.
/// Run/submit checkout is lock-free: FrameManager still has one consumer, and admission is a single atomic word.
/// Close may race submission; future callbacks run on finalizers and must respect submission ownership.
public final class PipelineRunner<I> extends QueueIngestSink {

    // The sign bit is set once admission closes; the remaining bits count accepted chains that have not finished.
    private static final long ADMISSION_CLOSED = Long.MIN_VALUE;

    private final FrameManager<I, PipelineFrame<I>> manager;
    private final long password = ThreadLocalRandom.current().nextLong();

    private final AtomicBoolean killSwitch = new AtomicBoolean(false);
    private final Runnable completion = this::chainFinished;
    private final AtomicLong admission = new AtomicLong();
    private final int partitions;

    private PipelineFrame<I> checkout(I data) {
        Objects.requireNonNull(data);
        long state;
        do {
            state = this.admission.get();
            if (state < 0) throw new IllegalStateException("Pipeline admission is closed");
        } while (!this.admission.compareAndSet(state, state + 1));
        // FrameManager checkout remains single-owner; admission does not serialize it.
        try {
            var root = this.manager.getOrCreate(data, this.password);
            root.onCompletion(this.completion);
            return root;
        } catch (RuntimeException e) {
            chainFinished();
            throw e;
        }
    }

    private void chainFinished() {
        if (this.admission.decrementAndGet() == ADMISSION_CLOSED) super.complete();
    }

    private long closeAdmission() {
        return this.admission.accumulateAndGet(ADMISSION_CLOSED, (state, closed) -> state | closed);
    }

    @Override
    public void completeGracefully() {
        if (closeAdmission() == ADMISSION_CLOSED) super.complete();
    }

    public <O> PipelineRunner(PipelineFrame.Builder<I, O> builder, Consumer<O> consumer, boolean consumeInParallel) {
        this(builder, consumer, consumeInParallel, 1);
    }

    public <O> PipelineRunner(
            PipelineFrame.Builder<I, O> builder, Consumer<O> consumer, boolean consumeInParallel, int partitions) {
        super(new PartitionedMpscQueue<>(validatePartitions(partitions), 8192));
        this.partitions = partitions;
        Objects.requireNonNull(builder);
        Objects.requireNonNull(consumer);

        if (consumeInParallel) {
            this.manager = builder.composeFannedOut(this, consumer, password, this.killSwitch);
        } else {
            this.manager = builder.composeFannedIn(this, consumer, password, this.killSwitch);
        }
    }

    private static int validatePartitions(int partitions) {
        if (partitions <= 0) throw new IllegalArgumentException("partitions must be positive");
        return partitions;
    }

    /// Submits with optional outcome observation. Legacy run does not allocate a future.
    public CompletableFuture<PipelineFrame.Outcome> submit(I data) {
        var root = checkout(data);
        var future = new CompletableFuture<PipelineFrame.Outcome>();
        root.observe(future);
        if (!super.offer(root)) root.doFinallyWithError(AbstractFrame.CANCEL_SIGNAL);
        return future;
    }

    public CompletableFuture<PipelineFrame.Outcome> submit(int partition, I data) {
        Objects.checkIndex(partition, this.partitions);
        var root = checkout(data);
        var future = new CompletableFuture<PipelineFrame.Outcome>();
        root.observe(future);
        if (!super.offer(partition, root)) root.doFinallyWithError(AbstractFrame.CANCEL_SIGNAL);
        return future;
    }

    public CompletableFuture<PipelineFrame.Outcome> submit(long randomSeed, I data) {
        var root = checkout(data);
        var future = new CompletableFuture<PipelineFrame.Outcome>();
        root.observe(future);
        if (!super.offer(randomSeed, root)) root.doFinallyWithError(AbstractFrame.CANCEL_SIGNAL);
        return future;
    }

    /// Offers the data to each partition starting from 0 until it finds room. The data will then be used to execute the
    /// pipeline.
    public void run(I data) {
        var root = checkout(data);
        if (!super.offer(root)) root.doFinallyWithError(AbstractFrame.CANCEL_SIGNAL);
    }

    /// Offers the data to a random partition based on the seed. If the seed does not change, the
    /// same partition will be picked. The data will then be used to execute the pipeline.
    public void run(long randomSeed, I data) {
        var root = checkout(data);
        if (!super.offer(randomSeed, root)) root.doFinallyWithError(AbstractFrame.CANCEL_SIGNAL);
    }

    /// Offers the data to a specific partition. The data will then be used to execute the pipeline.
    public void run(int partition, I data) {
        Objects.checkIndex(partition, this.partitions);
        var root = checkout(data);
        if (!super.offer(partition, root)) root.doFinallyWithError(AbstractFrame.CANCEL_SIGNAL);
    }

    @Override
    protected void onDrainFinished() {
        if (this.killSwitch.getAcquire()) {
            discardQueued(frame -> frame.doFinallyWithError(AbstractFrame.CANCEL_SIGNAL));
        }
    }

    @Override
    protected void onPublishedAfterClose() {
        onDrainFinished();
    }

    @Override
    public void complete() {
        this.killSwitch.setRelease(true);
        closeAdmission();
        try {
            super.complete();
        } finally {
            onDrainFinished();
        }
    }
}
