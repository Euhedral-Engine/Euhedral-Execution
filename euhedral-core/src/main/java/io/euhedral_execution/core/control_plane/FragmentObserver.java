package io.euhedral_execution.core.control_plane;

public abstract class FragmentObserver {

    /// This method will be called concurrently by fragments.
    ///
    /// Doubles are not sanitized before publishing.
    protected abstract void cycleStartState(
            int core,
            int socket,
            long cycleEpoch,
            long batchEpoch,
            long completed,
            long batchSize,
            long upstreamCount,
            int registeredWorkers,
            int workerRank,
            long contention,
            double throughput);

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
            int workerRank,
            long contention,
            double avgServiceTime,
            double throughput);

    /// This method will be called concurrently by fragments.
    ///
    /// The instant raw body cost reported by fragments
    protected abstract void rawBodyCost(int core, int socket, long cycleEpoch, long batchEpoch, long rawBodyCost);

    /// This method will be called concurrently by fragments.
    ///
    /// The idle branch of the decision tree chosen. `contententionPolicy` and `bodyPolicy` are the indexes of their
    /// corresponding idle policy lists. Doubles are not sanitized before publishing
    protected abstract void idleBranchDecision(
            int core,
            int socket,
            long cycleEpoch,
            long batchEpoch,
            int contentionPolicy,
            int bodyPolicy,
            long contention,
            double smoothedBodyCost);

    /// This method will be called concurrently by fragments.
    ///
    /// The execution branch of the decision tree chosen. `contententionPolicy` and `bodyPolicy` are the indexes of
    /// their
    /// corresponding execution policy lists. Doubles are not sanitized before publishing
    protected abstract void execBranchDecision(
            int core,
            int socket,
            long cycleEpoch,
            long batchEpoch,
            int contentionPolicy,
            int bodyPolicy,
            long contention,
            double smoothedBodyCost);
}
