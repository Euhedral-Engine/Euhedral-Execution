package euhedral.io.config;

import euhedral.io.control_plane.ControlPlaneLattice;
import euhedral.io.control_plane.ControlPlaneShard;
import euhedral.io.generics.CloneableObject;
import euhedral.io.impl.BaseCloneableObject;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.BitSet;
import org.jspecify.annotations.Nullable;

/// ### Configuration for the [ControlPlaneLattice][ControlPlaneLattice]
///
/// @param name            Used in logging and thread naming
/// @param allowedCpus     Which CPUs the `ControlPlaneLattice` can bind to. A null value will allow it to
/// use all that are available.
/// @param baseShard       Allows you to use any overridden version of a
/// [ControlPlaneShard][euhedral.io.control_plane.ControlPlaneShard]. A null value will make it
/// use the default implementation.
/// @param cloneableObject Allows you to pass any implementation of [CloneableObject] for cloning
/// onto cores. This is how you can pass a
/// [BaseCloneableObject][BaseCloneableObject] with custom configuration
/// values.
/// @param meterRegistry   Enables collection of metrics from the pipeline. Automatically given to
/// the default cloneableObjects if you don't use a custom one.
@SuppressWarnings("unused")
public record ControlPlaneConfig(String name, @Nullable BitSet allowedCpus,
                                 @Nullable ControlPlaneShard baseShard, @Nullable
                                 CloneableObject cloneableObject, @Nullable String metricPrefix,
                                 @Nullable MeterRegistry meterRegistry) {

    public static ControlPlaneConfig defaultConfig(String name) {
        return defaultConfig(name, null, null);
    }

    public static ControlPlaneConfig defaultConfig(String name, String metricPrefix, MeterRegistry meterRegistry) {
        return new ControlPlaneConfig(name, null, null, null, metricPrefix, meterRegistry);
    }

    public static ControlPlaneConfig defaultConfig(String name, CloneableObject cloneableObject) {
        return new ControlPlaneConfig(name, null, null, cloneableObject, null, null);
    }
}
