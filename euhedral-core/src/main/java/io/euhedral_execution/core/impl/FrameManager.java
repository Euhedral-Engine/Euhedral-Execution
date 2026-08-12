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
/// @param <D>  The data type to use to replace fields in the frame
/// @param <F> The frame type to recycle
@SuppressWarnings({"unchecked", "unused"})
public final class FrameManager<D, F extends AbstractFrame> {

    @Getter
    private final BatchableQueue<AbstractFrame> recycleQueue;

    private final long password;
    private final AbstractFrame[] buffer;
    private final int chunkSize;

    @Setter
    private FrameFactory<D, F> factory;

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
        this.chunkSize = actual;
    }

    /// Creates a FrameManager with a bounded recycler.
    public FrameManager(int capacity, long password) {
        int actual = Integer.highestOneBit((capacity - 1) << 1);
        actual = actual <= 0 ? 1 : actual;

        this.recycleQueue = new BoundedMpscQueue<>(actual);
        this.password = password;
        this.buffer = new AbstractFrame[Math.max(actual, 256)];
        this.chunkSize = actual;
    }

    /// Attempts to reuse an old frame if available. Creates one if not using the passed in data.
    public @NonNull F getOrCreate(D data, long password) {
        if (password != this.password) {
            throw new RuntimeException("Incorrect password for this FrameFactory.");
        }
        if (factory == null) {
            throw new RuntimeException("Cannot generate frames with a null FrameFactory.");
        }

        F frame = get();
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
    public @Nullable F get(long password) {
        if (password != this.password) {
            return null;
        }
        return get();
    }

    private @Nullable F get() {
        if (idx == 0) {
            idx = (int) recycleQueue.drain(this::drain, buffer.length);
            totalRecycled += idx;
        }
        if (idx > 0) {
            F frame = (F) buffer[--idx];
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
    public boolean recycle(F frame) {
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
        final int[] drain = new int[] {(int) Math.max(0, Math.min(idx, max))};

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

    public void close() {
        recycleQueue.clear();
    }

    /// Performs a shallow copy of this instance.
    ///
    /// ***Copies***:
    /// - Internal queue type
    /// - Queue capacity
    /// - Password
    ///
    /// ***Does not copy***:
    /// - Stored frames
    /// - The create function
    /// - The replace function
    public FrameManager<D, F> copy() {
        if (this.recycleQueue instanceof BoundedMpscQueue<AbstractFrame>) {
            return new FrameManager<>(this.chunkSize, this.password);
        } else if (this.recycleQueue instanceof MpscQueue<AbstractFrame> mpsc) {
            return new FrameManager<>(this.chunkSize, mpsc.getMaxPooledChunks(), this.password);
        }
        throw new IllegalStateException("This class does not have a bounded or unbounded MPSC queue.");
    }
}
