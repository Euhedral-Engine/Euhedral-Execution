package io.euhedral_execution.hardware_utils.internal.topology;

import java.util.Objects;

public record LogicalCpu(int logicalCpuId, String socketKey, String dieKey, String coreKey, CoreKind coreKind) {

    public LogicalCpu {
        socketKey = Objects.requireNonNull(socketKey, "socketKey");
        dieKey = Objects.requireNonNull(dieKey, "dieKey");
        coreKey = Objects.requireNonNull(coreKey, "coreKey");
        coreKind = Objects.requireNonNull(coreKind, "coreKind");
    }
}
