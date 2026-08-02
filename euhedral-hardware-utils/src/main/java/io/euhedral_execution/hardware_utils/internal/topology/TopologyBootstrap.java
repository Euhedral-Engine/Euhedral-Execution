package io.euhedral_execution.hardware_utils.internal.topology;

import io.euhedral_execution.hardware_utils.SystemInfo.CoreInfo;
import io.euhedral_execution.hardware_utils.SystemInfo.CpuCacheLayout;
import io.euhedral_execution.hardware_utils.SystemInfo.CpuInfo;
import io.euhedral_execution.hardware_utils.SystemInfo.SocketInfo;
import io.euhedral_execution.hardware_utils.common.OSName;
import io.euhedral_execution.hardware_utils.common.SystemSnapshotProvider;
import io.euhedral_execution.hardware_utils.linux.CgroupV2Resources;
import io.euhedral_execution.hardware_utils.osx.OSXResources;
import io.euhedral_execution.hardware_utils.windows.WindowsResources;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;

public final class TopologyBootstrap {

    public static TopologyModel normalize(TopologyProvider provider, int processorCount,
            Logger logger, String platform) {
        try {
            return new TopologyNormalizer().normalize(provider.collect());
        } catch (Exception | LinkageError failure) {
            logger.error("Failed to initialize {} topology; using common fallback.", platform,
                    failure);
            return fallback(processorCount);
        }
    }

    public static TopologyModel fallback(int processorCount) {
        int count = Math.max(1, processorCount);
        List<LogicalCpu> cpus = new ArrayList<>(count);
        for (int cpu = 0; cpu < count; cpu++) {
            cpus.add(new LogicalCpu(cpu, "fallback:package:0", "fallback:die:0",
                    "fallback:core:" + String.format("%08x", cpu), CoreKind.UNKNOWN));
        }
        return new TopologyNormalizer().normalize(new TopologyInput("fallback", cpus, List.of()));
    }

    public static TopologyModel extract(Map<Integer, CpuCacheLayout> cache,
            Map<Integer, CpuInfo> cpus, Map<Integer, CoreInfo> cores,
            Map<Integer, SocketInfo> sockets) {
        TopologyModel owner = owner(cache);
        if (owner != owner(cpus) || owner != owner(cores) || owner != owner(sockets)) {
            throw new TopologyValidationException("projection", "ownership", "mixed",
                    "projection maps do not share one topology model");
        }
        return owner;
    }

    public static SystemSnapshotProvider resources(Logger logger) {
        try {
            if (OSName.isLinux()) {
                return new CgroupV2Resources();
            }
            if (OSName.isWindows()) {
                return WindowsResources.INSTANCE;
            }
            if (OSName.isMacOS()) {
                return OSXResources.INSTANCE;
            }
            logger.error("Unsupported OS; resource snapshot provider is unavailable.");
        } catch (Exception | LinkageError failure) {
            logger.error("Failed to initialize resource snapshot provider.", failure);
        }
        return null;
    }

    private static TopologyModel owner(Map<?, ?> projection) {
        if (!(projection instanceof TopologyModel.OwnedProjection owned)) {
            throw new TopologyValidationException("projection", "ownership", "unowned",
                    "projection map has no topology model owner");
        }
        return owned.owner();
    }

    private TopologyBootstrap() {
    }
}
