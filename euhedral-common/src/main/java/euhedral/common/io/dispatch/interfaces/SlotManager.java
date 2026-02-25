package euhedral.common.io.dispatch.interfaces;

import java.util.function.Supplier;
import org.jspecify.annotations.NonNull;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface SlotManager {

    @NonNull
    default <T> Flux<T> acquireSlot(@NonNull Flux<T> flux) {
        return flux.concatMap(item -> acquireSlot(() -> Mono.just(item)));
    }

    @NonNull
    <T> Mono<T> acquireSlot(@NonNull Supplier<Mono<T>> work);

    double getPressure();

    int getConcurrencyLimit();
}
