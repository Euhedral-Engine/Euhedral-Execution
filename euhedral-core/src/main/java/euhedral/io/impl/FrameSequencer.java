package euhedral.io.impl;

import euhedral.hashing.HasherApi;
import euhedral.io.frames.SequencedFrame;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongHeapPriorityQueue;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import org.jctools.queues.MpscUnboundedXaddArrayQueue;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.core.publisher.Sinks.EmitResult;
import reactor.core.scheduler.Schedulers;

public class FrameSequencer<R> {

    private static final SequencedFrame SIGNAL_FRAME = new SequencedFrame(-1, -1, null, null,
            null, null, null);

    private final long ingestPassword;
    private final long sequencePassword = HasherApi.combine(ThreadLocalRandom.current().nextLong(),
            ThreadLocalRandom.current()
                    .nextLong());
    private final Long2ObjectOpenHashMap<SequencedFrame> frameData = new Long2ObjectOpenHashMap<>(
            32_768);
    private final LongHeapPriorityQueue sequenceHeap = new LongHeapPriorityQueue(32_768);
    private final Sinks.Many<SequencedFrame> ingest = Sinks.many().unicast()
            .onBackpressureBuffer(new MpscUnboundedXaddArrayQueue<>(8192));

    private final Sinks.Many<R> output = Sinks.unsafe().many().unicast()
            .onBackpressureBuffer(new MpscUnboundedXaddArrayQueue<>(8192));

    public FrameSequencer(long ingestPassword) {
        this.ingestPassword = ingestPassword;
        Flux.merge(ingest.asFlux())
                .publishOn(Schedulers.boundedElastic())
                .subscribe(frame -> {
                    if (frame.getSequenceNumber() != -1) {
                        frameData.put(frame.getSequenceNumber(), frame);
                        sequenceHeap.enqueue(frame.getSequenceNumber());
                    }
                    drainInternal();
                });
    }

    @SuppressWarnings("unchecked")
    private void drainInternal() {
        while (!sequenceHeap.isEmpty()) {
            long minSequence = sequenceHeap.firstLong();
            SequencedFrame frame = frameData.get(minSequence);
            if (frame == null) {
                sequenceHeap.dequeueLong();
            } else if (frame.isReady()) {
                sequenceHeap.dequeueLong();
                frameData.remove(minSequence);

                EmitResult result;
                while (!(result = output.tryEmitNext((R) frame.getRetVal())).isSuccess()) {
                    if (result == EmitResult.FAIL_CANCELLED || result == EmitResult.FAIL_TERMINATED
                            || result == EmitResult.FAIL_ZERO_SUBSCRIBER) {
                        frame.kill();
                        sequenceHeap.clear();
                        frameData.clear();
                        ingest.tryEmitComplete();
                        output.tryEmitComplete();
                        return;
                    }
                    Thread.yield();
                }
            } else {
                break;
            }
        }
    }

    @SuppressWarnings("unchecked")
    public <T> Flux<SequencedFrame> map(Flux<T> flux, Function<T, R> function) {
        final AtomicBoolean killSwitch = new AtomicBoolean(false);

        final int[] idx = new int[]{0};

        FrameManager<Object, SequencedFrame> recycler = new FrameManager<>(32_768, ingestPassword);
        recycler.setFactory(new FrameFactory<>((idHash, data) -> {
            SequencedFrame frame = new SequencedFrame(idHash, idx[0]++, data,
                    (Function<Object, Object>) function,
                    killSwitch, (FrameSequencer<Object>) this, recycler);
            frame.setOrdered(false);
            registerFrame(frame);
            return frame;
        }, (data, oldFrame) -> {
            oldFrame.replace(idx[0]++, data);
            oldFrame.setOrdered(false);
            registerFrame(oldFrame);
        }));

        return flux.map(obj -> recycler.generate(obj, ingestPassword))
                .doFinally(sig -> {
                    killSwitch.set(true);
                    sequenceHeap.clear();
                    frameData.clear();
                    ingest.tryEmitComplete();
                    output.tryEmitComplete();
                    recycler.close();
                });
    }

    private void registerFrame(SequencedFrame frame) {
        frame.setSequencerPassword(sequencePassword);
        ingest.tryEmitNext(frame);
    }

    public Flux<R> output() {
        return output.asFlux();
    }

    public void notifyComplete(long password) {
        if (password == sequencePassword) {
            ingest.tryEmitNext(SIGNAL_FRAME);
        }
    }
}
