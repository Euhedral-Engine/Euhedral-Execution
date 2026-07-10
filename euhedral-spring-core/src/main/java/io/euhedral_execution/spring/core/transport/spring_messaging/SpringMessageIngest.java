package io.euhedral_execution.spring.core.transport.spring_messaging;

import io.euhedral_execution.core.control_plane.ControlPlaneLattice;
import io.euhedral_execution.core.impl.FrameFactory;
import io.euhedral_execution.core.impl.FrameFactory.FrameCreate;
import io.euhedral_execution.core.impl.FrameFactory.FrameReplace;
import io.euhedral_execution.core.impl.FrameManager;
import io.euhedral_execution.data_structures.queues.MpmcQueue;
import io.euhedral_execution.hashing.HasherApi;
import io.euhedral_execution.reactor.common.EuhedralSubscriber;
import io.euhedral_execution.spring.core.frames.SpringMessageFrame;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.messaging.Message;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

@SuppressWarnings("unused")
public class SpringMessageIngest {

    /// Takes a stream of messages and transforms them into frames. Frames will be reused if the
    /// stream is sent into the
    /// [ControlPlaneLattice][io.euhedral_execution.core.control_plane.ControlPlaneLattice]
    ///
    /// @param raw             Raw message stream to transform
    /// @param recycleCapacity Maximum size of the recycle buffer
    public static Flux<SpringMessageFrame> frameStream(Flux<Message<?>> raw,
            int recycleCapacity) {
        if (recycleCapacity <= 0) {
            throw new IllegalArgumentException("recycleCapacity must be positive");
        }

        long ingestPassword = HasherApi.mix(ThreadLocalRandom.current().nextLong());
        AtomicBoolean killSwitch = new AtomicBoolean();

        FrameManager<Message<?>, SpringMessageFrame> manager = new FrameManager<>(
                recycleCapacity,
                ingestPassword);

        FrameCreate<Message<?>, SpringMessageFrame> create = (idHash, message) -> new SpringMessageFrame(
                idHash,
                message, manager, killSwitch);
        FrameReplace<Message<?>, SpringMessageFrame> replace = (message, frame) -> {
            frame.replace(message);
        };
        manager.setFactory(new FrameFactory<>(create, replace));

        return raw.map(msg -> manager.getOrCreate(msg, ingestPassword))
                .doOnCancel(() -> {
                    killSwitch.setRelease(true);
                }).doOnError(err -> {
                    killSwitch.setRelease(true);
                });
    }

    /// Modifies all frames in the stream to make them execute in parallel.
    ///
    /// @param orderedFrames Stream of frames to make parallel.
    public static Flux<SpringMessageFrame> makeParallel(Flux<SpringMessageFrame> orderedFrames) {
        long[] seed = new long[]{HasherApi.mix(ThreadLocalRandom.current().nextLong())};
        return orderedFrames.map(frame -> {
            frame.randomizeHash(seed[0]++);
            return frame;
        });
    }

    public static void ingestStream(ControlPlaneLattice controlPlane,
            Flux<SpringMessageFrame> frameFlux) {
        Sinks.One<Void> killSwitch = Sinks.unsafe().one();

        EuhedralSubscriber subscriber = new EuhedralSubscriber();
        frameFlux.takeUntilOther(killSwitch.asMono())
                .doOnCancel(killSwitch::tryEmitEmpty)
                .doOnError(sig -> killSwitch.tryEmitEmpty())
                .subscribe(subscriber);
        controlPlane.addUpstream(subscriber);
    }

    /// Prepares a stream to be ingested by the [ControlPlaneLattice]. Starts ingest when the
    /// return stream is subscribed to.
    ///
    /// @param controlPlane      The ControlPlaneLattice to give the stream to.
    /// @param raw               The messages to frame and give the lattice.
    /// @param responseQueueSize The size of the queue for the response frames.
    /// @return The response stream.
    public static Flux<SpringMessageFrame> prepStream(ControlPlaneLattice controlPlane,
            Flux<Message<?>> raw, int responseQueueSize) {
        Sinks.Many<SpringMessageFrame> response = Sinks.many().unicast()
                .onBackpressureBuffer(new MpmcQueue<>(responseQueueSize));

        long idHash = HasherApi.mix(ThreadLocalRandom.current().nextLong());

        AtomicBoolean senderSwitch = new AtomicBoolean();
        Sinks.One<Void> killSwitch = Sinks.unsafe().one();

        Flux<SpringMessageFrame> frameFlux = raw
                .map(msg -> new SpringMessageFrame(idHash, msg, null, senderSwitch));

        EuhedralSubscriber subscriber = new EuhedralSubscriber();
        return response.asFlux().doOnSubscribe(sub -> {
            frameFlux.map(frame -> {
                        frame.setReturnSink(response);
                        return frame;
                    })
                    .takeUntilOther(killSwitch.asMono())
                    .doOnCancel(() -> {
                        senderSwitch.setRelease(true);
                        killSwitch.tryEmitEmpty();
                    }).doOnError(err -> {
                        senderSwitch.setRelease(true);
                        killSwitch.tryEmitEmpty();
                        response.tryEmitComplete();
                    })
                    .subscribe(subscriber);
            controlPlane.addUpstream(subscriber);
        }).doOnCancel(() -> {
            senderSwitch.setRelease(true);
            killSwitch.tryEmitEmpty();
        }).doOnError(err -> {
            senderSwitch.setRelease(true);
            killSwitch.tryEmitEmpty();
            response.tryEmitComplete();
        });
    }
}
