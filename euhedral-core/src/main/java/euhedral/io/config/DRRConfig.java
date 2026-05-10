package euhedral.io.config;

import euhedral.io.interfaces.CloneableObject;
import io.micrometer.core.instrument.MeterRegistry;
import org.jspecify.annotations.Nullable;

public record DRRConfig(@Nullable CloneConfig cloneConfig, String metricPrefix,
                        @Nullable MeterRegistry registry) implements CloneableObject {

    @Override
    public DRRConfig clone(CloneConfig cloneConfig) {
        MeterRegistry meterRegistry = null;
        if (cloneConfig != null) {
            meterRegistry = cloneConfig.meterRegistry();
        }
        return new DRRConfig(cloneConfig, metricPrefix, meterRegistry);
    }

    @Override
    public void close() throws Exception {

    }

}
