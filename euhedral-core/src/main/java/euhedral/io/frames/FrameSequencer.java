package euhedral.io.frames;

import euhedral.io.utils.KeyHasher;
import euhedral.io.utils.MpscFrameRecycler;
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

    private static final SequencedFrame SIGNAL_FRAME = new SequencedFrame("", -1, -1, 0, null, null,
            null, null, null);

    private final long ingestPassword;
    private final long sequencePassword = KeyHasher.combine(ThreadLocalRandom.current().nextLong(),
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
                    if(frame.getSequenceNumber() != -1) {
                        frameData.put(frame.getSequenceNumber(), frame);
                        sequenceHeap.enqueue(frame.getSequenceNumber());
                    }
                    drainInternal();
                });
    }

    @SuppressWarnings("unchecked")
    public <T> Flux<AbstractFrame> map(Flux<T> flux, Function<T, R> function) {
        final String id = ThreadLocalRandom.current().nextLong() + "";
        final long idHash = KeyHasher.getHash(id);
        final long destinationHash = KeyHasher.mix(ThreadLocalRandom.current().nextLong());
        final AtomicBoolean killSwitch = new AtomicBoolean(false);

        final long[] seed = new long[]{KeyHasher.combine(ThreadLocalRandom.current().nextLong(), ThreadLocalRandom.current().nextLong())};

        final MpscFrameRecycler recycler = new MpscFrameRecycler(32_768, ingestPassword);

        final int[] idx = new int[]{-1, 0};
        final SequencedFrame[] buffer = new SequencedFrame[1024];

        return flux.map(obj -> {
            if (idx[0] < 0) {
                idx[0] = recycler.batchPoll(buffer, ingestPassword);
            }

            SequencedFrame sequencedFrame;
            if (--idx[0] >= 0) {
                sequencedFrame = buffer[idx[0]];
                sequencedFrame.replace(idx[1]++, obj);
            } else {
                sequencedFrame = new SequencedFrame(id, idHash, idx[1]++, destinationHash, obj,
                        (Function<Object, Object>) function,
                        killSwitch, (FrameSequencer<Object>) this, recycler);
            }
            sequencedFrame.setOrdered(false);
            sequencedFrame.randomizeHash(seed[0]++);
            registerFrame(sequencedFrame);

            return (AbstractFrame) sequencedFrame;
        }).doFinally(sig -> {
            killSwitch.set(true);
            sequenceHeap.clear();
            frameData.clear();
            ingest.tryEmitComplete();
            output.tryEmitComplete();
            recycler.close();
        });
    }

    public Flux<R> output() {
        return output.asFlux();
    }

    public void notifyComplete(long password) {
        if(password == sequencePassword) {
            ingest.tryEmitNext(SIGNAL_FRAME);
        }
    }

    private void registerFrame(SequencedFrame frame) {
        frame.setSequencerPassword(sequencePassword);
        ingest.tryEmitNext(frame);
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
}
