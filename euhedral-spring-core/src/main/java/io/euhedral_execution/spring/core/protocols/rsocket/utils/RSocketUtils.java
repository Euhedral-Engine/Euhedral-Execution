package io.euhedral_execution.spring.core.protocols.rsocket.utils;

import io.euhedral_execution.core.impl.FrameFactory;
import io.euhedral_execution.core.impl.FrameFactory.FrameCreate;
import io.euhedral_execution.core.impl.FrameFactory.FrameReplace;
import io.euhedral_execution.core.impl.FrameManager;
import io.euhedral_execution.hashing.HasherApi;
import io.euhedral_execution.spring.core.frames.RSocketFrame;
import io.euhedral_execution.spring.core.utils.KillSwitch;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import org.jspecify.annotations.Nullable;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

@SuppressWarnings("unused")
public class RSocketUtils {

    public static RSocketFrame toFrame(long idHash, Message<byte[]> message,
            @Nullable FrameManager<Message<byte[]>, RSocketFrame> recycler,
            @Nullable KillSwitch killSwitch) {
        return toFrame(idHash, message.getHeaders(), message.getPayload(), recycler, killSwitch);
    }

    public static RSocketFrame toFrame(long idHash, @Nullable Map<String, Object> headers,
            byte[] payload, @Nullable FrameManager<Message<byte[]>, RSocketFrame> recycler,
            @Nullable KillSwitch killSwitch) {
        return new RSocketFrame(idHash, headers, payload, recycler, killSwitch);
    }

    public static Message<byte[]> fromFrame(RSocketFrame frame) {
        return MessageBuilder.withPayload(frame.getPayload()).copyHeaders(frame.getHeaders())
                .build();
    }

    public static Flux<RSocketFrame> frameStream(Flux<Message<byte[]>> raw) {
        return frameStream(raw, 8_192);
    }

    public static Flux<RSocketFrame> frameStream(Flux<Message<byte[]>> raw, int recycleCapacity) {
        long ingestPassword = HasherApi.mix(ThreadLocalRandom.current().nextLong());

        Sinks.One<Void> killSwitch = Sinks.one();
        KillSwitch senderKillSwitch = new KillSwitch();

        FrameManager<Message<byte[]>, RSocketFrame> manager = new FrameManager<>(recycleCapacity,
                ingestPassword);

        FrameCreate<Message<byte[]>, RSocketFrame> create = (idHash, message) -> toFrame(idHash,
                message, manager, senderKillSwitch);
        FrameReplace<Message<byte[]>, RSocketFrame> replace = (message, frame) -> {
            frame.replace(message);
        };
        manager.setFactory(new FrameFactory<>(create, replace));

        return raw.takeUntilOther(killSwitch.asMono())
                .map(msg -> manager.getOrCreate(msg, ingestPassword))
                .doOnCancel(() -> {
                    senderKillSwitch.boop();
                    killSwitch.tryEmitEmpty();
                }).doOnError(t -> {
                    senderKillSwitch.boop();
                    killSwitch.tryEmitEmpty();
                });
    }

    public static Flux<RSocketFrame> makeParallel(Flux<RSocketFrame> orderedFrames) {
        long[] seed = new long[]{HasherApi.mix(ThreadLocalRandom.current().nextLong())};
        return orderedFrames.map(frame -> {
            frame.randomizeHash(seed[0]++);
            return frame;
        });
    }
}
