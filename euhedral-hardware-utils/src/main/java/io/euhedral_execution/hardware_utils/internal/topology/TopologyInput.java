package io.euhedral_execution.hardware_utils.internal.topology;

import java.util.List;
import java.util.Objects;

public record TopologyInput(String providerName, List<LogicalCpu> logicalCpus,
                            List<CacheDomain> cacheDomains) {

    public TopologyInput {
        providerName = Objects.requireNonNull(providerName, "providerName");
        logicalCpus = List.copyOf(Objects.requireNonNull(logicalCpus, "logicalCpus"));
        cacheDomains = Objects.requireNonNull(cacheDomains, "cacheDomains").stream()
                .map(domain -> new CacheDomain(domain.level(), domain.sizeBytes(),
                        domain.lineSizeBytes(), domain.logicalCpuSharers()))
                .toList();
    }
}
