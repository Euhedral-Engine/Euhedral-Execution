package euhedral.io.reactor.common;

import java.util.concurrent.locks.LockSupport;
import reactor.core.publisher.Sinks;
import reactor.core.publisher.Sinks.EmitResult;

public class BackpressureHandler {

    public static <T> EmitResult push(T data, Sinks.Many<T> sink) {
        int cycles = 0;
        EmitResult result;
        while (!(result = sink.tryEmitNext(data)).isSuccess()) {
            if (result == EmitResult.FAIL_CANCELLED
                    || result == EmitResult.FAIL_TERMINATED
                    || result == EmitResult.FAIL_ZERO_SUBSCRIBER) {
                return result;
            }
            if (cycles++ < 128) {
                Thread.onSpinWait();
            } else if (cycles < 512) {
                Thread.yield();
            } else {
                LockSupport.parkNanos(10_000);
            }
        }
        return result;
    }
}
