package io.euhedral_execution.core.impl;

import io.euhedral_execution.core.frames.AbstractFrame;
import io.euhedral_execution.hardware_utils.SystemInfo;
import io.euhedral_execution.hardware_utils.SystemInfo.CpuInfo;
import io.euhedral_execution.hardware_utils.ThreadTools;
import io.euhedral_execution.hashing.HasherApi;
import java.util.concurrent.ThreadLocalRandom;

/// A class for automatically creating or updating frames using the passed in functions.
///
/// Typically used in tandem with a [FrameManager]
///
/// @param <D> Data type to pass to the create and replace functions
/// @param <F> Frame type to create and manage
public final class FrameFactory<D, F extends AbstractFrame> {
    private final long idHash = HasherApi.mix(ThreadLocalRandom.current().nextLong());

    private final FrameCreate<D, F> frameGenerator;
    private final FrameReplace<D, F> frameReplace;
    private final CpuInfo originLocation;

    private long seed = ThreadLocalRandom.current().nextLong();

    public FrameFactory(FrameCreate<D, F> frameGenerator, FrameReplace<D, F> frameReplace) {
        this.frameGenerator = frameGenerator;
        this.frameReplace = frameReplace;
        this.originLocation = SystemInfo.getCpuInfo(ThreadTools.getCpu());
    }

    /// Creates a frame with the data.
    public F create(D data) {
        F frame = frameGenerator.create(idHash, data);
        if (!frame.isOrdered()) {
            frame.randomizeHash(seed++);
        }
        frame.setOrigin(originLocation);
        return frame;
    }

    /// Replaces the data in the frame.
    ///
    /// @param data Data to give to the frame
    public void replace(D data, F frame) {
        boolean wasParallel = !frame.isOrdered();
        frame.resetHash();

        frameReplace.replace(data, frame);
        if (wasParallel || !frame.isOrdered()) {
            frame.randomizeHash(seed++);
        }
        frame.setOrigin(originLocation);
    }

    @FunctionalInterface
    public interface FrameCreate<D, F extends AbstractFrame> {
        F create(long idHash, D data);
    }

    @FunctionalInterface
    public interface FrameReplace<D, F extends AbstractFrame> {
        void replace(D data, F oldFrame);
    }
}
