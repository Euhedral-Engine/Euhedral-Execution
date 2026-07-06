package euhedral.io.metrics;

import euhedral.io.config.FragmentConfig;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.TimeUnit;

@SuppressWarnings("unused")
public final class ExecutionMetrics implements AutoCloseable {

    public static final String CORE_TAG = "core";

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

    public static Collection<DistributionSummary> getLatencySummaries(MeterRegistry registry) {
        return registry.getMeters().stream()
                .filter(m -> m instanceof DistributionSummary && m.getId().getName()
                        .endsWith("execution.latency")).map(DistributionSummary.class::cast)
                .toList();
    }

    public static Collection<DistributionSummary> getLatencySummaries(String metricPrefix,
            MeterRegistry registry) {
        return summaries(metricPrefix, ".execution.latency", registry);
    }

    public static Collection<DistributionSummary> getThroughputSummaries(MeterRegistry registry) {
        return registry.getMeters().stream()
                .filter(m -> m instanceof DistributionSummary && m.getId().getName()
                        .endsWith("execution.throughput")).map(DistributionSummary.class::cast)
                .toList();
    }

    public static Collection<DistributionSummary> getThroughputSummaries(String metricPrefix,
            MeterRegistry registry) {
        return summaries(metricPrefix, ".execution.throughput", registry);
    }

    private static Collection<DistributionSummary> summaries(String prefix, String suffix, MeterRegistry registry) {
        if (prefix == null || prefix.isBlank()) {
            return List.of();
        }
        if (prefix.endsWith(".")) {
            prefix = prefix.substring(0, prefix.length() - 1);
        }

        return registry.find(prefix + suffix).summaries();
    }

    private final MeterRegistry registry;
    private final List<Meter> meters = new ArrayList<>();
    private final DistributionSummary latency;
    private final DistributionSummary throughput;

    private long inProgress = 0;

    public ExecutionMetrics(FragmentConfig config) {
        String prefix = config.metricPrefix();
        if (prefix == null || prefix.isBlank()) {
            prefix = "euhedral";
        }
        if (prefix.endsWith(".")) {
            prefix = prefix.substring(0, prefix.length() - 1);
        }


        this.registry = config.meterRegistry();
        if (this.registry != null && config.cloneConfig() != null) {
            String coreId = String.valueOf(config.cloneConfig().coreId());

            this.latency = DistributionSummary.builder(prefix + ".execution.latency")
                    .description("Average execution latency of work.")
                    .tag(CORE_TAG, coreId)
                    .baseUnit("nanoseconds")
                    .register(this.registry);
            this.meters.add(this.latency);

            this.throughput = DistributionSummary.builder(prefix + ".execution.throughput")
                    .description("Average throughput of work.")
                    .tag(CORE_TAG, coreId)
                    .baseUnit("seconds")
                    .register(this.registry);
            this.meters.add(this.throughput);

            this.meters.add(
                    Gauge.builder(prefix + ".execution.inProgress", this::getInProgress)
                            .description("Number of frames being executed")
                            .tag(CORE_TAG, coreId)
                            .register(this.registry));
        } else {
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
        }
        this.meters.clear();
    }
}
