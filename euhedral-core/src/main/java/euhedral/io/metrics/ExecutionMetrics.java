package euhedral.io.metrics;

import euhedral.io.config.FragmentConfig;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

public final class ExecutionMetrics implements AutoCloseable {
    private final List<Meter> meters = new ArrayList<>();

    public ExecutionMetrics(MeterRegistry registry, FragmentConfig config,
            Supplier<Integer> inFlight,
            Supplier<Long> latency, Supplier<Long> currentConcurrency,
            Supplier<Long> currentRate, Supplier<Double> pressure) {
        String prefix = config.metricPrefix();
        if (prefix == null ||  prefix.isBlank()) {
            prefix = "euhedral";
        }
        prefix = prefix.split("\\.")[0];

        if (registry != null && config.cloneConfig() != null) {
            String coreId = String.valueOf(config.cloneConfig().coreId());

            meters.add(Gauge.builder(prefix + ".execution.latency", latency)
                    .description("Average time for execution of work.").tag("core", coreId)
                    .baseUnit("nanoseconds").register(registry));

            meters.add(Gauge.builder(prefix + ".execution.concurrency.current",
                            currentConcurrency).description("Current adaptive concurrency limit")
                    .tag("core", coreId).register(registry));

            meters.add(
                    Gauge.builder(prefix + ".execution.inflight.count", inFlight)
                            .description("Number of frames being executed").tag("core", coreId)
                            .register(registry));

            meters.add(Gauge.builder(prefix + ".execution.throughput", currentRate)
                    .description("Current execution rate (execution/sec)").tag("core", coreId)
                    .register(registry));

            meters.add(Gauge.builder(prefix + ".execution.pressure", pressure)
                    .description(
                            "Combined signal of reported hardware and calculated execution pressure")
                    .tag("core", coreId).register(registry));
        }
    }

    @Override
    public void close() {
        meters.forEach(Meter::close);
        meters.clear();
    }
}
