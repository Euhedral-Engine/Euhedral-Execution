package io.euhedral_execution.core.control_plane;

import io.euhedral_execution.data_structures.atomics.PaddedDoubleAdder;
import io.euhedral_execution.hardware_utils.SystemInfo;
import io.euhedral_execution.hardware_utils.SystemInfo.SocketInfo;
import java.util.BitSet;

@SuppressWarnings("unused")
public class GlobalState {

    private static final PaddedDoubleAdder[] GLOBAL_THROUGHPUT;

    static {
        int maxSocket = SystemInfo.MAX_SOCKET_ID;

        GLOBAL_THROUGHPUT = new PaddedDoubleAdder[maxSocket + 1];

        for (int i = 0; i < maxSocket + 1; i++) {
            SocketInfo info = SystemInfo.getSocketInfo(i);
            if (info == null) {
                continue;
            }

            BitSet cpus = info.getCpuSet();

            int highest = cpus.previousSetBit(cpus.size());
            GLOBAL_THROUGHPUT[i] = new PaddedDoubleAdder(highest + 1);
            GLOBAL_THROUGHPUT[i].fill(Double.NEGATIVE_INFINITY);
        }
    }

    private GlobalState() {}

    public static void setThroughput(int socket, int cpu, double throughput) {
        GLOBAL_THROUGHPUT[socket].setRelease(cpu, throughput);
    }

    public static void resetThroughput(int socket, int cpu) {
        GLOBAL_THROUGHPUT[socket].setRelease(cpu, Double.NEGATIVE_INFINITY);
    }

    public static double minThroughput(int socket) {
        return GLOBAL_THROUGHPUT[socket].min();
    }

    public static double sumThroughput(int socket) {
        return GLOBAL_THROUGHPUT[socket].sum();
    }

    public static double meanThroughput(int socket) {
        return GLOBAL_THROUGHPUT[socket].mean();
    }

    public static double maxThroughput(int socket) {
        return GLOBAL_THROUGHPUT[socket].max();
    }
}
