package io.euhedral_execution.core.impl;

import io.euhedral_execution.core.frames.AbstractFrame;
import io.euhedral_execution.data_structures.queues.BoundedMpscQueue;
import io.euhedral_execution.data_structures.queues.MpscQueue;
import io.euhedral_execution.data_structures.queues.common.BatchableQueue;
import java.util.Arrays;
import lombok.Getter;
import lombok.Setter;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/// A frame management class backed by an MPSC (Multi-Producer Single-Consumer) queue for efficient
/// frame recirculation. This class is thread-safe for multiple producers and a single consumer.
///
/// This class manages a pool of reusable frames to reduce allocation overhead. An instance of this
/// class can be attached to corresponding instances of [`AbstractFrame`][io.euhedral_execution.core.frames]. At
/// the end of execution, the frames are returned to this manager for reuse. If none are available,
/// they are created. Consumption is password protected by the creator.
///
/// @param <DATA>  The data type to use to replace fields in the frame
/// @param <FRAME> The frame type to recycle
@SuppressWarnings({"unchecked", "unused"})
public final class FrameManager<DATA, FRAME extends AbstractFrame> implements AutoCloseable {

    @Getter
    private final BatchableQueue<AbstractFrame> recycleQueue;
    private final long password;
    private final AbstractFrame[] buffer;

    @Setter
    private FrameFactory<DATA, FRAME> factory;
    @Getter
    private long totalRecycled = 0;

    private int idx = 0;

    /// Creates a FrameManager with a bounded recycler.
    public FrameManager(long password) {
        this(16_384, password);
    }

    /// Creates a FrameManager with an unbounded recycler.
    public FrameManager(int chunkSize, int pooledChunks, long password) {
        int actual = Integer.highestOneBit((chunkSize - 1) << 1);
        actual = actual <= 0 ? 1 : actual;

        this.recycleQueue = new MpscQueue<>(actual, pooledChunks);
        this.password = password;
        this.buffer = new AbstractFrame[Math.max(actual, 256)];
    }

    /// Creates a FrameManager with a bounded recycler.
    public FrameManager(int capacity, long password) {
        int actual = Integer.highestOneBit((capacity - 1) << 1);
        actual = actual <= 0 ? 1 : actual;

        this.recycleQueue = new BoundedMpscQueue<>(actual);
        this.password = password;
        this.buffer = new AbstractFrame[Math.max(actual, 256)];
    }

    /// Attempts to reuse an old frame if available. Creates one if not using the passed in data.
    public @NonNull FRAME getOrCreate(DATA data, long password) {
        if (password != this.password) {
            throw new RuntimeException("Incorrect password for this FrameFactory.");
        }
        if (factory == null) {
            throw new RuntimeException("Cannot generate frames with a null FrameFactory.");
        }

        FRAME frame = get();
        if (frame == null) {
            return factory.create(data);
        }
        factory.replace(data, frame);
        return frame;
    }

    /// Gets a frame from the buffer or queue if available.
    ///
    /// @param password Password set during instantiation
    /// @return The next frame or `null` if empty
    public @Nullable FRAME get(long password) {
        if (password != this.password) {
            return null;
        }
        return get();
    }

    private @Nullable FRAME get() {
        if (idx == 0) {
            idx = (int) recycleQueue.drain(this::drain, buffer.length);
            totalRecycled += idx;
        }
        if (idx > 0) {
            FRAME frame = (FRAME) buffer[--idx];
            buffer[idx + 1] = null;
            return frame;
        }
        return null;
    }

    private void drain(AbstractFrame frame) {
        buffer[idx++] = frame;
    }

    /// Adds the frame to the recycle queue. This method is thread-safe.
    ///
    /// @param frame Frame to recycle
    /// @return `true` if frame was enqueued, `false` otherwise
    public boolean recycle(FRAME frame) {
        return recycleQueue.offer(frame);
    }

    /// Empties the buffer and queue up to the `max` amount.
    ///
    /// @param max      Maximum amount to drain
    /// @param password Password set during instantiation
    /// @return Number of drained frames
    public long dump(long max, long password) {
        if (password != this.password || max <= 0) {
            return 0;
        }

        long total = 0;
        final int[] drain = new int[]{(int) Math.max(0, Math.min(idx, max))};

        if (drain[0] > 0 && idx < max) {
            Arrays.fill(buffer, null);
        } else if (drain[0] > 0 && idx > 0) {
            idx = (int) max;
            Arrays.fill(buffer, idx, buffer.length, null);
        }
        max -= drain[0];
        total += drain[0];

        while (max > 0) {
            drain[0] = (int) Math.min(max, Integer.MAX_VALUE);
            int count = (int) recycleQueue.drain(f -> drain[0]--, drain[0]);

            if (count == 0) {
                break;
            }

            max -= count;
            total += count;
        }
        return total;
    }

    @Override
    public void close() {
        recycleQueue.clear();
    }
}
