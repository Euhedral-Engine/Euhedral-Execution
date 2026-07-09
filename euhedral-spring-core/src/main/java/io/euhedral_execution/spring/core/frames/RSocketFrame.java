package io.euhedral_execution.spring.core.frames;

import io.euhedral_execution.core.frames.AbstractFrame;
import io.euhedral_execution.core.impl.FrameManager;
import io.euhedral_execution.reactor.common.BackpressureHandler;
import io.euhedral_execution.spring.core.utils.KillSwitch;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.Getter;
import lombok.Setter;
import org.jspecify.annotations.Nullable;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageHeaders;
import org.springframework.messaging.support.MessageBuilder;
import reactor.core.publisher.Sinks;

@SuppressWarnings("unused")
public class RSocketFrame extends AbstractFrame {

    private final KillSwitch senderKillSwitch;

    @Setter
    private KillSwitch receiverKillSwitch;

    @Setter
    private Sinks.Many<RSocketFrame> returnSink;

    @Getter
    private Map<String, Object> headers;
    @Getter
    private byte[] payload;

    public RSocketFrame(long idHash, Message<byte[]> message, @Nullable FrameManager<Message<byte[]>, RSocketFrame> recycler,
            @Nullable KillSwitch senderKillSwitch) {
        this(idHash, message.getHeaders(), message.getPayload(), recycler, senderKillSwitch);
    }

    public RSocketFrame(long idHash, Map<String, Object> headers, byte[] payload,
            @Nullable FrameManager<Message<byte[]>, RSocketFrame> recycler, @Nullable KillSwitch senderKillSwitch) {
        super(idHash, recycler);
        this.senderKillSwitch = senderKillSwitch;
        this.headers = cleanHeaders(headers);
        this.payload = payload;
    }

    private Map<String, Object> cleanHeaders(Map<String, Object> headers) {
        if (headers == null) {
            return Map.of();
        }
        return headers.entrySet().stream()
                .filter(entry -> !entry.getKey().equals(MessageHeaders.ID) &&
                        !entry.getKey().equals(MessageHeaders.TIMESTAMP))
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    @Override
    public boolean isAlive() {
        boolean isAlive = this.receiverKillSwitch == null || !this.receiverKillSwitch.isBooped();
        isAlive &= this.senderKillSwitch == null || !this.senderKillSwitch.isBooped();
        return isAlive;
    }

    @Override
    public void kill() {
        if (this.receiverKillSwitch != null) {
            this.receiverKillSwitch.boop();
        }
        if(this.senderKillSwitch != null) {
            this.senderKillSwitch.boop();
        }
    }

    public boolean canSendResponse() {
        return this.returnSink != null;
    }

    public void respond(RSocketFrame frame) {
        if(this.returnSink != null) {
            if(!BackpressureHandler.push(frame, this.returnSink).isSuccess()) {
                throwCancelSignal();
            }
        }
    }

    public void replace(Message<byte[]> message) {
        replace(message.getHeaders(), message.getPayload());
    }

    public void replace(Map<String, Object> headers, byte[] payload) {
        this.headers = cleanHeaders(headers);
        this.payload = payload;
    }

    public Message<byte[]> toSpringMessage() {
        return MessageBuilder.withPayload(this.payload).copyHeaders(this.headers).build();
    }
}
