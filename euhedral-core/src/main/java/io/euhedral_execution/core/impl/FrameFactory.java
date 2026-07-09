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
/// @param <DATA> Data type to pass to the create and replace functions
/// @param <FRAME> Frame type to create and manage
public final class FrameFactory<DATA, FRAME extends AbstractFrame> {
    private final long idHash = HasherApi.mix(ThreadLocalRandom.current().nextLong());

    private final FrameCreate<DATA, FRAME> frameGenerator;
    private final FrameReplace<DATA, FRAME> frameReplace;
    private final CpuInfo originLocation;

    private long seed = ThreadLocalRandom.current().nextLong();

    public FrameFactory(FrameCreate<DATA, FRAME> frameGenerator, FrameReplace<DATA, FRAME> frameReplace) {
        this.frameGenerator = frameGenerator;
        this.frameReplace = frameReplace;
        this.originLocation = SystemInfo.getCpuInfo(ThreadTools.getCpu());
    }

    /// Creates a frame with the data.
    public FRAME create(DATA data) {
        FRAME frame = frameGenerator.create(idHash, data);
        if(frame.isOrdered()) {
            frame.randomizeHash(idHash);
        } else {
            frame.randomizeHash(seed++);
        }
        frame.setOrigin(originLocation);
        return frame;
    }

    /// Replaces the data in the frame.
    ///
    /// @param data Data to give to the frame
    public void replace(DATA data, FRAME frame) {
        frame.resetHash();

        frameReplace.replace(data, frame);
        if(!frame.isOrdered()) {
            frame.randomizeHash(seed++);
        }
        frame.setOrigin(originLocation);
    }

    @FunctionalInterface
    public interface FrameCreate<DATA, F extends AbstractFrame> {
        F create(long idHash, DATA data);
    }

    @FunctionalInterface
    public interface FrameReplace<DATA, F extends AbstractFrame> {
        void replace(DATA data, F oldFrame);
    }
}
