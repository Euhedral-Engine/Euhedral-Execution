package io.euhedral_execution.core.control_plane;

public abstract class FragmentObserver {

    /// This method will be called concurrently by fragments.
    /// Doubles are not sanitized before publishing.
    protected abstract void cycleStartState(
            int core,
            int socket,
            long completed,
            long batchSize,
            long upstreamCount,
            int registeredWorkers,
            int workerRank,
            long contention,
            double throughput);

    /// This method will be called concurrently by fragments.
    /// Doubles are not sanitized before publishing.
    protected abstract void batchProgressState(
            int core,
            int socket,
            long upstreamCount,
            int registeredWorkers,
            int workerRank,
            long contention,
            double avgServiceTime);

    /// This method will be called concurrently by fragments.
    /// Doubles are not sanitized before publishing.
    protected abstract void batchCompleteState(
            int core,
            int socket,
            long upstreamCount,
            int registeredWorkers,
            int workerRank,
            long contention,
            double avgServiceTime,
            double throughput);

    /// The instant raw body cost reported by fragments
    /// This method will be called concurrently by fragments.
    protected abstract void rawBodyCost(int core, int socket, long rawBodyCost);
}
