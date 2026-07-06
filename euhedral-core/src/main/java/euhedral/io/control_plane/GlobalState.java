package euhedral.io.control_plane;

import euhedral.hardware_utils.SystemInfo;
import euhedral.hardware_utils.SystemInfo.SocketInfo;
import io.euhedral_execution.data_structures.atomics.PaddedDoubleAdder;
import java.util.BitSet;

@SuppressWarnings("unused")
public class GlobalState {
    private static final PaddedDoubleAdder[] GLOBAL_THROUGHPUT;

    static {
        int sockets = SystemInfo.SOCKET_COUNT;

        GLOBAL_THROUGHPUT = new PaddedDoubleAdder[sockets];

        for(int i = 0; i < sockets; i++) {
            SocketInfo info = SystemInfo.getSocketInfo(i);
            BitSet cpus = info.getCpuSet();

            int highest = cpus.previousSetBit(cpus.size());
            GLOBAL_THROUGHPUT[i] = new PaddedDoubleAdder(highest + 1);
            GLOBAL_THROUGHPUT[i].fill(Double.NEGATIVE_INFINITY);
        }
    }

    public static void setThroughput(int socket, int cpu, double throughput) {
        GLOBAL_THROUGHPUT[socket].setRelease(cpu, throughput);
    }

    public static void resetThroughput(int socket, int cpu) {
        GLOBAL_THROUGHPUT[socket].setRelease(cpu, Double.NEGATIVE_INFINITY);
    }

    public static double minThroughput(int socket) {
        return GLOBAL_THROUGHPUT[socket].min();
    }

    public static double meanThroughput(int socket) {
        return GLOBAL_THROUGHPUT[socket].mean();
    }

    public static double maxThroughput(int socket) {
        return GLOBAL_THROUGHPUT[socket].max();
    }
}
