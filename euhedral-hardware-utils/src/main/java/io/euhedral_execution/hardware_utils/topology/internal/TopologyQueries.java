package io.euhedral_execution.hardware_utils.topology.internal;

import io.euhedral_execution.hardware_utils.topology.SystemInfo.CoreInfo;
import io.euhedral_execution.hardware_utils.topology.SystemInfo.CpuCacheLayout;
import io.euhedral_execution.hardware_utils.topology.SystemInfo.CpuInfo;
import io.euhedral_execution.hardware_utils.topology.SystemInfo.SocketInfo;
import java.util.Map;

/// Presence and strict lookups over a {@link TopologyModel}. `SystemInfo` delegates here so the
/// logic is testable against fixture models. Presence is defined as "the nullable lookup returns
/// non-null", so these can never disagree with the `get*Info` methods.
public final class TopologyQueries {

    private TopologyQueries() {}

    public static boolean isActiveCpu(TopologyModel model, int cpu) {
        return model.cpuInfo().get(cpu) != null;
    }

    public static boolean isActiveCore(TopologyModel model, int core) {
        return model.coreInfo().get(core) != null;
    }

    public static boolean isActiveSocket(TopologyModel model, int socket) {
        return model.socketInfo().get(socket) != null;
    }

    public static CpuInfo requireCpuInfo(TopologyModel model, int cpu) {
        CpuInfo info = model.cpuInfo().get(cpu);
        if (info == null) {
            throw inactive("cpu ", cpu, "logical CPU", model.cpuInfo());
        }
        return info;
    }

    public static CoreInfo requireCoreInfo(TopologyModel model, int core) {
        CoreInfo info = model.coreInfo().get(core);
        if (info == null) {
            throw inactive("core ", core, "core", model.coreInfo());
        }
        return info;
    }

    public static SocketInfo requireSocketInfo(TopologyModel model, int socket) {
        SocketInfo info = model.socketInfo().get(socket);
        if (info == null) {
            throw inactive("socket ", socket, "socket", model.socketInfo());
        }
        return info;
    }

    public static CpuCacheLayout requireCacheLayout(TopologyModel model, int cpu) {
        CpuCacheLayout layout = model.cacheLayout().get(cpu);
        if (layout == null) {
            throw inactive("cpu ", cpu, "logical CPU", model.cpuInfo());
        }
        return layout;
    }

    // Failure path only: the range text is never built on a successful lookup.
    private static IllegalArgumentException inactive(String prefix, int id, String noun, Map<Integer, ?> active) {
        return new IllegalArgumentException(
                prefix + id + " is not an active " + noun + " (active: " + ranges(active) + ")");
    }

    /// Linux-style range list such as {@code 0-3,6-7}; keys arrive in ascending order.
    static String ranges(Map<Integer, ?> active) {
        StringBuilder sb = new StringBuilder();
        int start = 0;
        int prev = 0;
        boolean open = false;
        for (int id : active.keySet()) {
            if (open && id == prev + 1) {
                prev = id;
                continue;
            }
            if (open) {
                append(sb, start, prev);
            }
            start = id;
            prev = id;
            open = true;
        }
        if (open) {
            append(sb, start, prev);
        }
        return sb.toString();
    }

    private static void append(StringBuilder sb, int start, int end) {
        if (!sb.isEmpty()) {
            sb.append(',');
        }
        sb.append(start);
        if (end != start) {
            sb.append('-').append(end);
        }
    }
}
