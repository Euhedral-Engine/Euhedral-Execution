package io.euhedral_execution.core.control_plane;

public abstract class FragmentObserver {

    /// This method will be called concurrently by fragments.
    ///
    /// Doubles are not sanitized before publishing.
    protected abstract void batchProgressState(
            int core,
            int socket,
            long cycleEpoch,
            long batchEpoch,
            long upstreamCount,
            int registeredWorkers,
            long productiveHandleCount,
            int workerRank,
            long contention,
            double avgServiceTime);

    /// This method will be called concurrently by fragments.
    ///
    /// Doubles are not sanitized before publishing.
    protected abstract void batchCompleteState(
            int core,
            int socket,
            long cycleEpoch,
            long batchEpoch,
            long upstreamCount,
            int registeredWorkers,
            long productiveHandleCount,
            int workerRank,
            long contention,
            double avgServiceTime,
            double throughput);
}
