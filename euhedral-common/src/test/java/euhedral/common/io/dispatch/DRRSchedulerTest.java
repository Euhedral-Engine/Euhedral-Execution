package euhedral.common.io.dispatch;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.Test;

class DRRSchedulerTest {


    private static DRRScheduler.Config getConfig() {
        return new DRRScheduler.Config(null, 10, "Test", null);
    }

    public DRRScheduler createDRRScheduler(DRRScheduler.Config config,
            Callable<Double> downstreamPressure) {
        return new DRRScheduler(config, null, downstreamPressure);
    }
}