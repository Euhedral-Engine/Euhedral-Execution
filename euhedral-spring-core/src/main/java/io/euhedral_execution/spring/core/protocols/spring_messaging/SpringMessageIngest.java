package io.euhedral_execution.spring.core.protocols.spring_messaging;

import io.euhedral_execution.core.control_plane.ControlPlaneLattice;
import io.euhedral_execution.core.impl.FrameFactory;
import io.euhedral_execution.core.impl.FrameFactory.FrameCreate;
import io.euhedral_execution.core.impl.FrameFactory.FrameReplace;
import io.euhedral_execution.core.impl.FrameManager;
import io.euhedral_execution.data_structures.queues.MpmcQueue;
import io.euhedral_execution.hashing.HasherApi;
import io.euhedral_execution.reactor.common.EuhedralSubscriber;
import io.euhedral_execution.spring.core.frames.SpringMessageFrame;
import io.euhedral_execution.spring.core.utils.KillSwitch;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.messaging.Message;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

@SuppressWarnings("unused")
public class SpringMessageIngest {

    /// Takes a stream of messages and transforms them into frames. A new frame will be created for
    /// each message.
    ///
    /// @param raw Raw message stream to transform
    public static Flux<SpringMessageFrame> frameStream(Flux<Message<?>> raw) {
        long idHash = HasherApi.mix(ThreadLocalRandom.current().nextLong());

        Sinks.One<Void> killSwitch = Sinks.one();
        KillSwitch senderKillSwitch = new KillSwitch();

        return raw.takeUntilOther(killSwitch.asMono())
                .map(msg -> new SpringMessageFrame(idHash, msg, null, senderKillSwitch))
                .doOnCancel(() -> {
                    senderKillSwitch.boop();
                    killSwitch.tryEmitEmpty();
                }).doOnError(t -> {
                    senderKillSwitch.boop();
                    killSwitch.tryEmitEmpty();
                });
    }

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

        FrameManager<Message<?>, SpringMessageFrame> manager = new FrameManager<>(
                recycleCapacity,
                ingestPassword);

        FrameCreate<Message<?>, SpringMessageFrame> create = (idHash, message) -> new SpringMessageFrame(
                idHash,
                message, manager, null);
        FrameReplace<Message<?>, SpringMessageFrame> replace = (message, frame) -> {
            frame.replace(message);
        };
        manager.setFactory(new FrameFactory<>(create, replace));

        return raw.map(msg -> manager.getOrCreate(msg, ingestPassword));
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

    /// Prepares a stream of frames to be ingested by the [ControlPlaneLattice]. Starts the ingest
    /// when the return stream is subscribed to.
    ///
    /// @param controlPlane      The ControlPlaneLattice to give the stream to.
    /// @param frameFlux         The frames to give the lattice.
    /// @param responseQueueSize The size of the queue for the response frames.
    /// @return The response stream.
    public static Flux<SpringMessageFrame> prepStream(ControlPlaneLattice controlPlane,
            Flux<SpringMessageFrame> frameFlux, int responseQueueSize) {
        Sinks.Many<SpringMessageFrame> response = Sinks.many().unicast()
                .onBackpressureBuffer(new MpmcQueue<>(responseQueueSize));

        Sinks.One<Void> killSwitch = Sinks.unsafe().one();
        KillSwitch receiverKillSwitch = new KillSwitch();

        EuhedralSubscriber subscriber = new EuhedralSubscriber();
        return response.asFlux().doOnSubscribe(sub -> {
            frameFlux.map(frame -> {
                        frame.setReturnSink(response);
                        frame.setReceiverKillSwitch(receiverKillSwitch);
                        return frame;
                    }).takeUntilOther(killSwitch.asMono())
                    .doOnCancel(() -> {
                        receiverKillSwitch.boop();
                        killSwitch.tryEmitEmpty();
                    }).doOnError(err -> {
                        receiverKillSwitch.boop();
                        killSwitch.tryEmitEmpty();
                        response.tryEmitComplete();
                    })
                    .subscribe(subscriber);
            controlPlane.addUpstream(subscriber);
        }).doOnCancel(() -> {
            receiverKillSwitch.boop();
            killSwitch.tryEmitEmpty();
        }).doOnError(err -> {
            receiverKillSwitch.boop();
            killSwitch.tryEmitEmpty();
            response.tryEmitComplete();
        });
    }

    public static void ingestStream(ControlPlaneLattice controlPlane,
            Flux<SpringMessageFrame> frameFlux) {
        Sinks.One<Void> killSwitch = Sinks.unsafe().one();
        KillSwitch receiverKillSwitch = new KillSwitch();

        EuhedralSubscriber subscriber = new EuhedralSubscriber();
        frameFlux.map(frame -> {
                    frame.setReceiverKillSwitch(receiverKillSwitch);
                    return frame;
                }).takeUntilOther(killSwitch.asMono())
                .doOnCancel(() -> {
                    receiverKillSwitch.boop();
                    killSwitch.tryEmitEmpty();
                }).doOnError(err -> {
                    receiverKillSwitch.boop();
                    killSwitch.tryEmitEmpty();
                }).subscribe(subscriber);
        controlPlane.addUpstream(subscriber);
    }

    public static SpringMessageFrame toFrame(long idHash, Message<?> message) {
        return new SpringMessageFrame(idHash, message);
    }
}
