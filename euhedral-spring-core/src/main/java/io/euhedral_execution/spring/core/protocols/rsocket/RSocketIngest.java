package io.euhedral_execution.spring.core.protocols.rsocket;

import io.euhedral_execution.core.control_plane.ControlPlaneLattice;
import io.euhedral_execution.core.ingest.SingleUseSource;
import io.euhedral_execution.data_structures.queues.PartitionedMpmcQueue;
import io.euhedral_execution.reactor.common.EuhedralSubscriber;
import io.euhedral_execution.spring.core.frames.RSocketFrame;
import io.euhedral_execution.spring.core.utils.KillSwitch;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.stereotype.Controller;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

@Controller
@SuppressWarnings("unused")
public class RSocketIngest {

    public static final String UNARY_REQUEST = "unary-request";
    public static final String UNARY_REQUEST_RESPONSE = "unary-request-response";
    public static final String CLIENT_STREAM = "client-stream";
    public static final String SERVER_STREAM = "server-stream";
    public static final String CLIENT_STREAM_UNARY_RESPONSE = "client-stream-unary-response";
    public static final String BIDI_STREAM = "bidi-stream";

    private final ControlPlaneLattice controlPlane;
    private final int responseQueueSize;

    public RSocketIngest(ControlPlaneLattice controlPlane) {
        this(controlPlane, 2048);
    }

    public RSocketIngest(ControlPlaneLattice controlPlane, int responseQueueSize) {
        this.controlPlane = controlPlane;
        this.responseQueueSize = responseQueueSize;
    }

    @MessageMapping(UNARY_REQUEST)
    public void receiveSingle(RSocketFrame frame) {
        controlPlane.addUpstream(SingleUseSource.wrap(frame));
    }

    @MessageMapping(UNARY_REQUEST_RESPONSE)
    public Mono<RSocketFrame> receiveSingleRespondSingle(RSocketFrame message) {
        Sinks.Many<RSocketFrame> response = Sinks.unsafe().many().unicast().onBackpressureError();
        message.setReturnSink(response);
        return Mono.from(response.asFlux())
                .doOnSubscribe(sub -> controlPlane.addUpstream(SingleUseSource.wrap(message)));
    }

    @MessageMapping(CLIENT_STREAM)
    public void receiveStream(Flux<RSocketFrame> messageFlux) {
        Sinks.One<Void> killSwitch = Sinks.unsafe().one();
        KillSwitch receiverKillSwitch = new KillSwitch();

        messageFlux.takeUntilOther(killSwitch.asMono()).map(frame -> {
            frame.setReceiverKillSwitch(receiverKillSwitch);
            return frame;
        }).doOnCancel(() -> {
            receiverKillSwitch.boop();
            killSwitch.tryEmitEmpty();
        }).doOnError(t -> {
            receiverKillSwitch.boop();
            killSwitch.tryEmitEmpty();
        });
    }

    @MessageMapping(SERVER_STREAM)
    public Flux<RSocketFrame> receiveSingleRespondStream(RSocketFrame frame) {
        Sinks.Many<RSocketFrame> response = Sinks.many().unicast()
                .onBackpressureBuffer(new PartitionedMpmcQueue<>(1, this.responseQueueSize));
        frame.setReturnSink(response);
        return response.asFlux().doOnSubscribe(sub -> {
            sub.request(Long.MAX_VALUE);
            this.controlPlane.addUpstream(SingleUseSource.wrap(frame));
        }).doOnError(err -> {
            response.tryEmitComplete();
        });
    }

    @MessageMapping(CLIENT_STREAM_UNARY_RESPONSE)
    public Mono<RSocketFrame> receiveStreamRespondSingle(Flux<RSocketFrame> messageFlux) {
        return Mono.from(prepStream(messageFlux));
    }

    @MessageMapping(BIDI_STREAM)
    public Flux<RSocketFrame> receiveStreamRespondStream(Flux<RSocketFrame> messageFlux) {
        return prepStream(messageFlux);
    }

    public Flux<RSocketFrame> prepStream(Flux<RSocketFrame> messageFlux) {
        Sinks.Many<RSocketFrame> response = Sinks.many().unicast()
                .onBackpressureBuffer(new PartitionedMpmcQueue<>(1, this.responseQueueSize));

        Sinks.One<Void> killSwitch = Sinks.unsafe().one();
        KillSwitch receiverKillSwitch = new KillSwitch();

        EuhedralSubscriber subscriber = new EuhedralSubscriber();
        return response.asFlux().doOnSubscribe(sub -> {
            messageFlux.map(frame -> {
                frame.setReturnSink(response);
                frame.setReceiverKillSwitch(receiverKillSwitch);
                return frame;
            }).takeUntilOther(killSwitch.asMono()).subscribe(subscriber);
            this.controlPlane.addUpstream(subscriber);
        }).doOnCancel(() -> {
            killSwitch.tryEmitEmpty();
            receiverKillSwitch.boop();
        }).doOnError(err -> {
            receiverKillSwitch.boop();
            killSwitch.tryEmitEmpty();
            response.tryEmitComplete();
        });
    }
}
