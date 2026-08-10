package io.euhedral_execution.core.control_plane;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import io.euhedral_execution.core.config.CacheConfig;
import io.euhedral_execution.core.config.CloneConfig;
import io.euhedral_execution.core.flow_control.UpstreamQueue;
import io.euhedral_execution.core.frames.AbstractFrame;
import io.euhedral_execution.core.generics.CloneableObject;
import io.euhedral_execution.core.utils.FlowThread;
import io.euhedral_execution.hardware_utils.SystemInfo;
import java.util.BitSet;
import org.junit.jupiter.api.Test;

class WorkRequesterTest {

    /// Verifies a staged attempt clears stale counters before recording its current pull and request.
    @Test
    void requestAndPullClearsCountersAndRetainsWatermarks() {
        int cpu = SystemInfo.getCpuSet().nextSetBit(0);
        int core = SystemInfo.getCpuInfo(cpu).core();
        BitSet cpus = new BitSet();
        cpus.set(cpu);
        CacheConfig config = CacheConfig.ofDefaults().clone(new CloneConfig("requester-test", core, cpus));
        TestRequester requester = new TestRequester(config);
        UpstreamQueue upstream = mock(UpstreamQueue.class);
        FlowThread.FlowContext context = new FlowThread.FlowContext();
        context.upstream = upstream;
        context.satisfiedRequest = 11L;
        context.originalRequest = 12L;
        context.satisfiedPull = 13L;
        context.originalPull = 14L;

        long multiplier = Math.max(
                (SystemInfo.getSocketInfo(SystemInfo.getCoreInfo(core).socket())
                                        .getCoreSet()
                                        .cardinality()
                                * 3L)
                        >> 3,
                2L);
        long batchSize = 4L;
        long expectedDemand = batchSize * multiplier * SystemInfo.SOCKET_COUNT;
        long expectedPull = Math.min(requester.getMaxLocalCacheCount() >> 1, batchSize * multiplier);

        try {
            requester.stage(context, batchSize);

            assertEquals(0L, context.satisfiedRequest);
            assertEquals(expectedDemand, context.originalRequest);
            assertEquals(0L, context.satisfiedPull);
            assertEquals(expectedPull, context.originalPull);
            verify(upstream).request(expectedDemand);
        } finally {
            requester.close();
        }
    }

    private static final class TestRequester extends WorkRequester {

        /// Creates a requester with a real owner-local cache and a mocked upstream queue.
        private TestRequester(CacheConfig config) {
            super(config);
        }

        /// Exposes one staged request/pull attempt to package-owned tests.
        private void stage(FlowThread.FlowContext context, long batchSize) {
            requestAndPull(context, batchSize);
        }

        @Override
        protected void accept(AbstractFrame frame) {}

        @Override
        public CloneableObject clone(CloneConfig cloneConfig) {
            return null;
        }
    }
}
