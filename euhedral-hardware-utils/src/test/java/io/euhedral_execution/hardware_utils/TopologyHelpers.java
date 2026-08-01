package io.euhedral_execution.hardware_utils;

import io.euhedral_execution.hardware_utils.common.SystemUtilization.HardwareUtilization;
import io.euhedral_execution.hardware_utils.common.SystemUtilization.SystemSnapshot;
import io.euhedral_execution.hardware_utils.common.UnmodifiableBitSet;
import io.euhedral_execution.hardware_utils.internal.topology.CoreKind;
import io.euhedral_execution.hardware_utils.internal.topology.LogicalCpu;
import io.euhedral_execution.hardware_utils.internal.topology.TopologyInput;
import io.euhedral_execution.hardware_utils.internal.topology.TopologyModel;
import io.euhedral_execution.hardware_utils.internal.topology.TopologyNormalizer;
import java.util.BitSet;
import java.util.List;

final class TopologyHelpers {

    static TopologyModel twoSocketModel() {
        return new TopologyNormalizer().normalize(new TopologyInput("linux", List.of(
                linuxCpu(0, 0, 0), linuxCpu(3, 0, 1), linuxCpu(7, 1, 0)), List.of()));
    }

    static TopologyModel coreZeroModel() {
        return new TopologyNormalizer().normalize(new TopologyInput("fallback",
                List.of(fallbackCpu(5)), List.of()));
    }

    static HardwareUtilization utilization(BitSet cpus) {
        int span = Math.max(cpus.length(), 1);
        double[] throttle = new double[span];
        double[] pressure = new double[span];
        SystemSnapshot snapshot = SystemSnapshot.create(17, span, cpus.cardinality(), 100,
                0, 0, new UnmodifiableBitSet(cpus), pressure,
                new long[]{1_000, 250, 0}, 0);
        return HardwareUtilization.create(17, cpus.cardinality(), 0, 100,
                new UnmodifiableBitSet(cpus), 0, throttle, pressure,
                1_000, 100, 0.25, 25, 0, 0, snapshot);
    }

    static BitSet bits(int... ids) {
        BitSet result = new BitSet();
        for (int id : ids) {
            result.set(id);
        }
        return result;
    }

    private static LogicalCpu linuxCpu(int id, int socket, int core) {
        return new LogicalCpu(id, "linux:package:" + socket, "linux:die:0",
                "linux:core:" + core, CoreKind.UNKNOWN);
    }

    private static LogicalCpu fallbackCpu(int id) {
        return new LogicalCpu(id, "fallback:package:0", "fallback:die:0",
                "fallback:core:00000000", CoreKind.UNKNOWN);
    }

    private TopologyHelpers() {
    }
}
