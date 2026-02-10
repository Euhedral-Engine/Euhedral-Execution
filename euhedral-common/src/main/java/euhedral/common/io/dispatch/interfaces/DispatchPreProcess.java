package euhedral.common.io.dispatch.interfaces;

import reactor.core.publisher.Flux;

public interface DispatchPreProcess<T> {
    Flux<T> process(Flux<T> payloadFlux);
}
