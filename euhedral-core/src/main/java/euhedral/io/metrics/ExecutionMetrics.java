package euhedral.io.metrics;

import static euhedral.io.metrics.MetricsAggregator.CORE_TAG;
import static euhedral.io.metrics.MetricsAggregator.DEFAULT_PREFIX;
import static euhedral.io.metrics.MetricsAggregator.metricName;

import euhedral.io.config.FragmentConfig;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

@SuppressWarnings("unused")
public final class ExecutionMetrics implements AutoCloseable {

    private static final long OP_NS_TO_OP_SEC = TimeUnit.SECONDS.toNanos(1);

    private static final VarHandle IN_PROGRESS;

    static {
        try {
            IN_PROGRESS = MethodHandles.lookup()
                    .findVarHandle(ExecutionMetrics.class, "inProgress", long.class);
        } catch (Throwable t) {
            throw new ExceptionInInitializerError(t);
        }
    }

    private final MeterRegistry registry;

    private final List<Meter> meters;
    private final DistributionSummary latency;
    private final DistributionSummary throughput;

    private long inProgress = 0;

    public ExecutionMetrics(FragmentConfig config) {
        String prefix = config.metricPrefix();
        if (prefix == null || prefix.isBlank()) {
            prefix = DEFAULT_PREFIX;
        }

        this.registry = config.registry();
        if (this.registry != null && config.cloneConfig() != null) {
            this.meters = new ArrayList<>();
            String coreId = String.valueOf(config.cloneConfig().coreId());

            this.latency = DistributionSummary.builder(metricName(prefix, MetricsAggregator.LATENCY_SUMMARY_SUFFIX))
                    .description("Average execution latency of work.")
                    .tag(CORE_TAG, coreId)
                    .baseUnit("nanoseconds")
                    .register(this.registry);
            this.meters.add(this.latency);

            this.throughput = DistributionSummary.builder(metricName(prefix, MetricsAggregator.THROUGHPUT_SUMMARY_SUFFIX))
                    .description("Average throughput of work.")
                    .tag(CORE_TAG, coreId)
                    .baseUnit("seconds")
                    .register(this.registry);
            this.meters.add(this.throughput);

            this.meters.add(
                    Gauge.builder(metricName(prefix, MetricsAggregator.IN_PROGRESS_SUFFIX), this::getInProgress)
                            .description("Number of frames being executed")
                            .tag(CORE_TAG, coreId)
                            .register(this.registry));
        } else {
            this.meters = null;
            this.latency = null;
            this.throughput = null;
        }
    }

    public void addInProgress(long inProgress) {
        IN_PROGRESS.getAndAddRelease(this, inProgress);
    }

    public void reportLatency(long latency) {
        if (this.latency != null) {
            this.latency.record(latency);
        }
    }

    public void reportThroughput(double throughput) {
        if (this.throughput != null) {
            this.throughput.record(throughput * OP_NS_TO_OP_SEC);
        }
    }

    public long getInProgress() {
        return (long) IN_PROGRESS.getOpaque(this);
    }

    @Override
    public void close() {
        if(this.registry != null) {
            this.meters.forEach(this.registry::remove);
            this.meters.clear();
        }
    }
}
