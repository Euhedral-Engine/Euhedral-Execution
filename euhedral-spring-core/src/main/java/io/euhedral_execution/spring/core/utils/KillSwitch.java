package io.euhedral_execution.spring.core.utils;

import io.euhedral_execution.data_structures.queues.MpscQueue;
import lombok.Getter;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

@SuppressWarnings("unused")
public class KillSwitch {

    private final Sinks.Many<Void> goners = Sinks.many().multicast().onBackpressureBuffer();
    private final MpscQueue<Runnable> moreGoners = new MpscQueue<>(64);

    @Getter
    private volatile boolean booped = false;

    public void boop() {
        booped = true;

        goners.tryEmitComplete();
        Runnable action;
        while ((action = moreGoners.poll()) != null) {
            try {
                action.run();
            } catch (Exception ignored) {
                // Ignore exceptions in user shutdown.
            }
        }
    }

    public Flux<Void> addGoner() {
        return goners.asFlux();
    }

    public void addGoner(Runnable action) {
        if (booped) {
            action.run();
        } else {
            this.moreGoners.offer(action);

            if (booped && moreGoners.remove(action)) {
                action.run();
            }
        }
    }
}
