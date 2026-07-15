package io.euhedral_execution.core.metrics;

import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Collection;
import java.util.List;

@SuppressWarnings("unused")
public class MetricsAggregator {

    public static final String DEFAULT_PREFIX = "euhedral";

    public static final String CORE_TAG = "core";

    public static final String CACHE_BACKLOG_SUFFIX = ".cache.backlog";
    public static final String CAP_FACTOR_SUFFIX = ".cache.capFactor";

    public static final String IN_PROGRESS_SUFFIX = ".execution.inProgress";
    public static final String LATENCY_SUMMARY_SUFFIX = ".execution.latency";
    public static final String THROUGHPUT_SUMMARY_SUFFIX = ".execution.throughput";

    public static String metricName(String prefix, String suffix) {
        if (prefix.endsWith(".")) {
            prefix = prefix.substring(0, prefix.length() - 1);
        }
        return prefix + suffix;
    }

    // CACHE

    public static Collection<Gauge> getCacheBacklogGauges(MeterRegistry registry) {
        return registry.getMeters().stream().filter(m -> m instanceof Gauge && m.getId().getName()
                .endsWith(CACHE_BACKLOG_SUFFIX)).map(Gauge.class::cast).toList();
    }

    public static Collection<Gauge> getCacheBacklogGauges(String metricPrefix,
            MeterRegistry registry) {
        return gauges(metricPrefix, CACHE_BACKLOG_SUFFIX, registry);
    }

    public static Collection<DistributionSummary> getCapFactorSummaries(MeterRegistry registry) {
        return registry.getMeters().stream()
                .filter(m -> m instanceof DistributionSummary && m.getId().getName()
                        .endsWith(CAP_FACTOR_SUFFIX)).map(DistributionSummary.class::cast).toList();
    }

    public static Collection<DistributionSummary> getCapFactorSummaries(String metricPrefix,
            MeterRegistry registry) {
        return summaries(metricPrefix, CAP_FACTOR_SUFFIX, registry);
    }

    // EXECUTION

    public static Collection<Gauge> getInProgressGauges(MeterRegistry registry) {
        return registry.getMeters().stream().filter(m -> m instanceof Gauge && m.getId().getName()
                .endsWith(LATENCY_SUMMARY_SUFFIX)).map(Gauge.class::cast).toList();
    }

    public static Collection<Gauge> getInProgressGauges(String metricPrefix,
            MeterRegistry registry) {
        return gauges(metricPrefix, IN_PROGRESS_SUFFIX, registry);
    }

    public static Collection<DistributionSummary> getLatencySummaries(MeterRegistry registry) {
        return registry.getMeters().stream()
                .filter(m -> m instanceof DistributionSummary && m.getId().getName()
                        .endsWith(LATENCY_SUMMARY_SUFFIX)).map(DistributionSummary.class::cast)
                .toList();
    }

    public static Collection<DistributionSummary> getLatencySummaries(String metricPrefix,
            MeterRegistry registry) {
        return summaries(metricPrefix, LATENCY_SUMMARY_SUFFIX, registry);
    }

    public static Collection<DistributionSummary> getThroughputSummaries(MeterRegistry registry) {
        return registry.getMeters().stream()
                .filter(m -> m instanceof DistributionSummary && m.getId().getName()
                        .endsWith(THROUGHPUT_SUMMARY_SUFFIX)).map(DistributionSummary.class::cast)
                .toList();
    }

    public static Collection<DistributionSummary> getThroughputSummaries(String metricPrefix,
            MeterRegistry registry) {
        return summaries(metricPrefix, THROUGHPUT_SUMMARY_SUFFIX, registry);
    }

    private static Collection<Gauge> gauges(String prefix, String suffix, MeterRegistry registry) {
        if (prefix == null || prefix.isBlank()) {
            return List.of();
        }

        return registry.find(metricName(prefix, suffix)).gauges();
    }

    private static Collection<DistributionSummary> summaries(String prefix, String suffix,
            MeterRegistry registry) {
        if (prefix == null || prefix.isBlank()) {
            return List.of();
        }

        return registry.find(metricName(prefix, suffix)).summaries();
    }

    private MetricsAggregator() {

    }
}
