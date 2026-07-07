package euhedral.io.metrics;

import static euhedral.io.metrics.MetricsAggregator.CORE_TAG;
import static euhedral.io.metrics.MetricsAggregator.DEFAULT_PREFIX;
import static euhedral.io.metrics.MetricsAggregator.metricName;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import euhedral.io.config.CacheConfig;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;

public final class CacheMetrics implements AutoCloseable {

    private final MeterRegistry registry;

    private final List<Meter> meters;
    public final DistributionSummary capFactor;

    public CacheMetrics(CacheConfig config, Supplier<Long> cacheCount) {
        String prefix = config.metricPrefix();
        if (prefix == null || prefix.isBlank()) {
            prefix = DEFAULT_PREFIX;
        }

        this.registry = config.registry();
        if (registry != null) {
            this.meters = new ArrayList<>();
            String tag = String.valueOf(config.cloneConfig().coreId());

            this.meters.add(
                    Gauge.builder(metricName(prefix, MetricsAggregator.CACHE_BACKLOG_SUFFIX),
                                    cacheCount)
                            .description("Number of frames stored in the fragment cache.")
                            .baseUnit("frames").tag(CORE_TAG, tag).register(registry));

            this.capFactor = DistributionSummary.builder(
                            metricName(prefix, MetricsAggregator.CAP_FACTOR_SUFFIX)).description(
                            "Current buffer capacity multiplier. Higher is better. (0.15 to 1.0)")
                    .tag(CORE_TAG, tag).register(registry);
            this.meters.add(this.capFactor);

        } else {
            this.meters = null;
            this.capFactor = null;
        }
    }

    public void recordCapFactor(double cap) {
        if (this.registry != null) {
            this.capFactor.record(cap);
        }
    }

    @Override
    public void close() {
        if (this.registry != null) {
            meters.forEach(this.registry::remove);
            meters.clear();
        }
    }
}
