package io.euhedral_execution.hardware_utils.internal.topology;

import io.euhedral_execution.hardware_utils.SystemInfo.CoreInfo;
import io.euhedral_execution.hardware_utils.SystemInfo.CpuCacheLayout;
import io.euhedral_execution.hardware_utils.SystemInfo.CpuInfo;
import io.euhedral_execution.hardware_utils.SystemInfo.SocketInfo;
import io.euhedral_execution.hardware_utils.common.UnmodifiableBitSet;
import java.util.AbstractMap;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.jspecify.annotations.NonNull;

public final class TopologyModel {

    private final int[] activeLogicalIds;
    private final UnmodifiableBitSet cpuSet;
    private final UnmodifiableBitSet pCoreSet;
    private final UnmodifiableBitSet eCoreSet;
    private final UnmodifiableBitSet pCpuSet;
    private final UnmodifiableBitSet eCpuSet;
    private final Map<Integer, CpuCacheLayout> cacheLayout;
    private final Map<Integer, CpuInfo> cpuInfo;
    private final Map<Integer, CoreInfo> coreInfo;
    private final Map<Integer, SocketInfo> socketInfo;
    private final int cpuCount;
    private final int coreCount;
    private final int socketCount;
    private final int cacheLineBytes;

    TopologyModel(int[] activeLogicalIds, UnmodifiableBitSet cpuSet,
            UnmodifiableBitSet pCoreSet, UnmodifiableBitSet eCoreSet,
            UnmodifiableBitSet pCpuSet, UnmodifiableBitSet eCpuSet,
            Map<Integer, CpuCacheLayout> cacheLayout, Map<Integer, CpuInfo> cpuInfo,
            Map<Integer, CoreInfo> coreInfo, Map<Integer, SocketInfo> socketInfo,
            int cpuCount, int coreCount, int socketCount, int cacheLineBytes) {
        this.activeLogicalIds = activeLogicalIds.clone();
        this.cpuSet = cpuSet;
        this.pCoreSet = pCoreSet;
        this.eCoreSet = eCoreSet;
        this.pCpuSet = pCpuSet;
        this.eCpuSet = eCpuSet;
        this.cacheLayout = new ProjectionMap<>(this, cacheLayout);
        this.cpuInfo = new ProjectionMap<>(this, cpuInfo);
        this.coreInfo = new ProjectionMap<>(this, coreInfo);
        this.socketInfo = new ProjectionMap<>(this, socketInfo);
        this.cpuCount = cpuCount;
        this.coreCount = coreCount;
        this.socketCount = socketCount;
        this.cacheLineBytes = cacheLineBytes;
    }

    public int[] activeLogicalIds() {
        return activeLogicalIds.clone();
    }

    public UnmodifiableBitSet cpuSet() {
        return cpuSet;
    }

    public UnmodifiableBitSet pCoreSet() {
        return pCoreSet;
    }

    public UnmodifiableBitSet eCoreSet() {
        return eCoreSet;
    }

    public UnmodifiableBitSet pCpuSet() {
        return pCpuSet;
    }

    public UnmodifiableBitSet eCpuSet() {
        return eCpuSet;
    }

    public Map<Integer, CpuCacheLayout> cacheLayout() {
        return cacheLayout;
    }

    public Map<Integer, CpuInfo> cpuInfo() {
        return cpuInfo;
    }

    public Map<Integer, CoreInfo> coreInfo() {
        return coreInfo;
    }

    public Map<Integer, SocketInfo> socketInfo() {
        return socketInfo;
    }

    public int cpuCount() {
        return cpuCount;
    }

    public int coreCount() {
        return coreCount;
    }

    public int socketCount() {
        return socketCount;
    }

    public int maxCoreId() {
        return coreCount - 1;
    }

    public int maxSocketId() {
        return socketCount - 1;
    }

    public int cacheLineBytes() {
        return cacheLineBytes;
    }

    interface OwnedProjection {

        TopologyModel owner();
    }

    private static final class ProjectionMap<V> extends AbstractMap<Integer, V>
            implements OwnedProjection {

        private final TopologyModel owner;
        private final Map<Integer, V> delegate;

        private ProjectionMap(TopologyModel owner, Map<Integer, V> source) {
            this.owner = owner;
            this.delegate = Collections.unmodifiableMap(new TreeMap<>(source));
        }

        @Override
        public TopologyModel owner() {
            return owner;
        }

        @Override
        public V get(Object key) {
            return delegate.get(key);
        }

        @Override
        public @NonNull Set<Entry<Integer, V>> entrySet() {
            return delegate.entrySet();
        }
    }
}
