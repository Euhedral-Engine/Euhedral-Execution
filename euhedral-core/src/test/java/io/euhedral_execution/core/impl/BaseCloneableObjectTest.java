package io.euhedral_execution.core.impl;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.when;

import io.euhedral_execution.core.config.FragmentConfig;
import io.euhedral_execution.core.control_plane.ControlPlaneFragment;
import io.euhedral_execution.core.generics.AbstractExecutor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

@Isolated
class BaseCloneableObjectTest {
    @Test
    void startedPipelineWaitsForWorkerRegistrationAndExecutorReadiness() {
        var executor = mock(AbstractExecutor.class);
        when(executor.isStarted()).thenReturn(true);
        try (var fragments = mockConstruction(ControlPlaneFragment.class, (fragment, context) -> {
            /// Start can return while the owner-thread registration is still pending.
            when(fragment.isStarted()).thenReturn(true);
        })) {
            var pipeline = new BaseCloneableObject(FragmentConfig.ofDefaults(), executor);
            var fragment = fragments.constructed().getFirst();
            try {
                pipeline.start();
                assertTrue(pipeline.isStarted());
                assertFalse(pipeline.ready(), "unregistered workers must not allow source publication");
                when(fragment.ready()).thenReturn(true);
                assertFalse(pipeline.ready(), "executor readiness is also required");
                when(executor.ready()).thenReturn(true);
                assertTrue(pipeline.ready());
                when(fragment.ready()).thenReturn(false);
                assertFalse(pipeline.ready(), "a stopped worker must withdraw readiness");
            } finally {
                pipeline.close();
            }
        }
    }
}
