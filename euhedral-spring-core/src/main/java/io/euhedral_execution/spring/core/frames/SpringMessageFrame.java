package io.euhedral_execution.spring.core.frames;

import io.euhedral_execution.core.frames.AbstractFrame;
import io.euhedral_execution.core.impl.FrameManager;
import io.euhedral_execution.reactor.common.BackpressureHandler;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import lombok.Getter;
import lombok.Setter;
import org.jspecify.annotations.Nullable;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageHeaders;
import reactor.core.publisher.Sinks;

@SuppressWarnings("unused")
public class SpringMessageFrame extends AbstractFrame {

    @Setter
    private Sinks.Many<SpringMessageFrame> returnSink;

    @Getter
    Message<?> message;

    public SpringMessageFrame(long idHash, Message<?> message) {
        this(idHash, message, null, null);
    }

    public SpringMessageFrame(long idHash, Message<?> message, @Nullable FrameManager<Message<?>, SpringMessageFrame> recycler,
            @Nullable AtomicBoolean killSwitch) {
        super(idHash, recycler, killSwitch);
        this.message = message;
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

    public boolean canSendResponse() {
        return this.returnSink != null;
    }

    public void respond(SpringMessageFrame frame) {
        if(this.returnSink != null) {
            if(!BackpressureHandler.push(frame, this.returnSink).isSuccess()) {
                throwCancelSignal();
            }
        }
    }

    public void replace(Message<?> message) {
        this.message = message;
    }
}
