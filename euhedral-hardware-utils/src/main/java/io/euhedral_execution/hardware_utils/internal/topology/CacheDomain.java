package io.euhedral_execution.hardware_utils.internal.topology;

import java.util.BitSet;
import java.util.Objects;

public record CacheDomain(int level, long sizeBytes, int lineSizeBytes,
                          BitSet logicalCpuSharers) {

    public CacheDomain {
        logicalCpuSharers = (BitSet) Objects.requireNonNull(logicalCpuSharers,
                "logicalCpuSharers").clone();
    }

    @Override
    public BitSet logicalCpuSharers() {
        return (BitSet) logicalCpuSharers.clone();
    }
}
