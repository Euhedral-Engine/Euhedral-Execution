package euhedral.io.metrics;

import io.euhedral_execution.data_structures.atomics.AtomicDouble;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

public class CacheMetrics implements AutoCloseable {
    public final DistributionSummary subQBacklogSummary;
    public final DistributionSummary subQWeightSummary;

    private final List<Meter> meters = new ArrayList<>();

    public CacheMetrics(String metricPrefix, String tag, AtomicDouble capFactor,
            Supplier<Long> totalQueuedSizeBytes, MeterRegistry registry) {
        if (metricPrefix == null || metricPrefix.isBlank()) {
            metricPrefix = "euhedral";
        }
        metricPrefix = metricPrefix.split("\\.")[0];

        if (registry != null) {

            subQBacklogSummary =
                    DistributionSummary.builder(metricPrefix + ".cache_sub_queue_backlog_bytes")
                            .description("Amount of bytes stored in a sub queue")
                            .tag("core", tag).publishPercentiles(0.5, 0.95, 0.99)
                            .register(registry);

            subQWeightSummary =
                    DistributionSummary.builder(metricPrefix + ".cache_sub_queue_weight")
                            .tag("core", tag).publishPercentiles(0.0, 1.0).register(registry);

            meters.add(
                    Gauge.builder(metricPrefix + ".cap_factor", capFactor, AtomicDouble::get)
                            .description(
                                    "Current buffer capacity multiplier. Higher is better. (0.15 to 1.0)")
                            .tag("core", tag).register(registry));

            meters.add(Gauge.builder(metricPrefix + ".cache_backlog",
                            () -> totalQueuedSizeBytes.get() / 1024)
                    .description("Total bytes buffered in all sub queues of the ControlPlaneCache")
                    .baseUnit("KB").register(registry));
        } else {
            subQBacklogSummary = null;
            subQWeightSummary = null;
        }
    }

    @Override
    public void close() {
        meters.forEach(Meter::close);
        meters.clear();
        if (subQBacklogSummary != null) {
            subQBacklogSummary.close();
            subQWeightSummary.close();
        }
    }
}
