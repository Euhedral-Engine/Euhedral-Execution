package io.euhedral_execution.hardware_utils.osx;

import io.euhedral_execution.hardware_utils.SystemInfo.CoreInfo;
import io.euhedral_execution.hardware_utils.SystemInfo.CpuCacheLayout;
import io.euhedral_execution.hardware_utils.SystemInfo.CpuInfo;
import io.euhedral_execution.hardware_utils.SystemInfo.SocketInfo;
import io.euhedral_execution.hardware_utils.common.OSName;
import io.euhedral_execution.hardware_utils.internal.Constants;
import io.euhedral_execution.hardware_utils.internal.topology.CoreKind;
import io.euhedral_execution.hardware_utils.internal.topology.LogicalCpu;
import io.euhedral_execution.hardware_utils.internal.topology.TopologyBootstrap;
import io.euhedral_execution.hardware_utils.internal.topology.TopologyInput;
import io.euhedral_execution.hardware_utils.internal.topology.TopologyModel;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@SuppressWarnings("unused")
public final class OSXSystemLayout {

    public static final OSXSystemLayout INSTANCE;
    private static final Logger LOGGER = LoggerFactory.getLogger(Constants.getLoggerName(
            OSXSystemLayout.class));

    static {
        INSTANCE = OSName.isMacOS() ? new OSXSystemLayout(
                Runtime.getRuntime().availableProcessors()) : null;
    }

    private static native long getSysctlLong(String key);
    private static native int getSysctlInt(String key);
    private static native int getSysctlString(String key);

    private final TopologyModel model;

    private OSXSystemLayout(int processorCount) {
        int count = Math.max(1, processorCount);
        this.model = TopologyBootstrap.normalize(() -> new TopologyInput("macos",
                IntStream.range(0, count).mapToObj(cpu -> new LogicalCpu(cpu,
                        "macos:package:0", "macos:die:0",
                        "macos:core:" + String.format("%08x", cpu), CoreKind.UNKNOWN)).toList(),
                List.of()), count, LOGGER, "macos");
    }

    public Map<Integer, CpuCacheLayout> getCacheLayout() {
        return model.cacheLayout();
    }

    public Map<Integer, CpuInfo> getCpuInfoMap() {
        return model.cpuInfo();
    }

    public Map<Integer, CoreInfo> getCoreInfoMap() {
        return model.coreInfo();
    }

    public Map<Integer, SocketInfo> getSocketInfoMap() {
        return model.socketInfo();
    }
}
