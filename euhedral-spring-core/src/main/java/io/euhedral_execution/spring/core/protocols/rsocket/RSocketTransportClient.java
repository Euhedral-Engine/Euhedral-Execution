package io.euhedral_execution.spring.core.protocols.rsocket;

import static io.euhedral_execution.spring.core.protocols.rsocket.RSocketIngest.BIDI_STREAM;
import static io.euhedral_execution.spring.core.protocols.rsocket.RSocketIngest.CLIENT_STREAM;
import static io.euhedral_execution.spring.core.protocols.rsocket.RSocketIngest.CLIENT_STREAM_UNARY_RESPONSE;
import static io.euhedral_execution.spring.core.protocols.rsocket.RSocketIngest.SERVER_STREAM;
import static io.euhedral_execution.spring.core.protocols.rsocket.RSocketIngest.UNARY_REQUEST;
import static io.euhedral_execution.spring.core.protocols.rsocket.RSocketIngest.UNARY_REQUEST_RESPONSE;

import io.euhedral_execution.spring.core.frames.RSocketFrame;
import org.springframework.messaging.rsocket.RSocketRequester;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Component
@SuppressWarnings("unused")
public class RSocketTransportClient {

    private final RSocketRequester requester;

    public RSocketTransportClient(RSocketRequester requester) {
        this.requester = requester;
    }

    public void sendSingle(RSocketFrame message) {
        this.requester.route(UNARY_REQUEST).data(message).send().subscribe();
    }

    public void sendStream(Flux<RSocketFrame> messageFlux) {
        this.requester.route(CLIENT_STREAM).data(messageFlux).send().subscribe();
    }

    public Mono<RSocketFrame> sendSingleRespondSingle(RSocketFrame message) {
        return this.requester.route(UNARY_REQUEST_RESPONSE).data(message)
                .retrieveMono(RSocketFrame.class);
    }

    public Flux<RSocketFrame> sendSingleRespondStream(RSocketFrame message) {
        return this.requester.route(SERVER_STREAM).data(message).retrieveFlux(RSocketFrame.class);
    }

    public Mono<RSocketFrame> sendStreamRespondSingle(Flux<RSocketFrame> messageFlux) {
        return this.requester.route(CLIENT_STREAM_UNARY_RESPONSE).data(messageFlux)
                .retrieveMono(RSocketFrame.class);
    }

    public Flux<RSocketFrame> sendStreamRespondStream(Flux<RSocketFrame> messageFlux) {
        return this.requester.route(BIDI_STREAM).data(messageFlux).retrieveFlux(RSocketFrame.class);
    }
}



