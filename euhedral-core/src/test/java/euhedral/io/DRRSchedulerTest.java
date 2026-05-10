package euhedral.io;

import euhedral.io.config.DRRConfig;
import java.util.concurrent.Callable;

class DRRSchedulerTest {

    private static DRRConfig getConfig() {
        return new DRRConfig(null, "Test", null);
    }

    public DRRScheduler createDRRScheduler(DRRConfig config,
            Callable<Double> downstreamPressure) {
        return new DRRScheduler(config, null, downstreamPressure);
    }
}