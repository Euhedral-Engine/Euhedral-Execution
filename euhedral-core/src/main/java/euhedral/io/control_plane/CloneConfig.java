package euhedral.io.control_plane;

import euhedral.io.utils.ResourceMonitor;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.BitSet;

public record CloneConfig(String shardName, int coreId, double quotaCpus,
                          BitSet effectiveCpus, ResourceMonitor resourceMonitor,
                          MeterRegistry meterRegistry, String metricPrefix) {

};
