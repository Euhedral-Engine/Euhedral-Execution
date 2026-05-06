package euhedral.io;

import java.util.concurrent.Callable;

class DRRSchedulerTest {

    private static DRRScheduler.Config getConfig() {
        return new DRRScheduler.Config(null, 10, "Test", null);
    }

    public DRRScheduler createDRRScheduler(DRRScheduler.Config config,
            Callable<Double> downstreamPressure) {
        return new DRRScheduler(config, null, downstreamPressure);
    }
}