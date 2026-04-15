package euhedral.io.impl;

import euhedral.io.frames.AbstractFrame;
import java.util.Arrays;
import lombok.Getter;
import lombok.Setter;
import org.jctools.queues.MessagePassingQueue;
import org.jctools.queues.MpscArrayQueue;
import org.jctools.queues.MpscUnboundedXaddArrayQueue;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/// A frame manager backed by an MPSC (Multi-Producer Single-Consumer) queue for efficient frame
/// recirculation. This class is thread-safe for multiple producers and a single consumer.
///
/// This class manages a pool of reusable frames to reduce allocation overhead. Instances of this
/// class are attached to corresponding instances of [`AbstractFrame`][euhedral.io.frames]. At the end
/// of execution, the frames are returned to this manager for reuse. If none are available, they are
/// created. Consumption is password protected by the creator.
///
/// @param <F> The frame type to recycle
@SuppressWarnings("unchecked")
public class FrameManager<T, F extends AbstractFrame> implements AutoCloseable {

    @Getter
    private final MessagePassingQueue<AbstractFrame> recycleQueue;
    private final long password;
    private final AbstractFrame[] buffer;

    @Setter
    private FrameFactory<T, F> factory;
    private int idx = 0;

    public FrameManager(int chunkSize, int pooledChunks,
            long password) {
        int actual = Integer.highestOneBit((chunkSize - 1) << 1);
        actual = actual <= 0 ? 1 : actual;

        this.recycleQueue = new MpscUnboundedXaddArrayQueue<>(actual, pooledChunks);
        this.password = password;
        this.buffer = new AbstractFrame[Math.max(actual, 256)];
    }

    public FrameManager(int capacity, long password) {
        int actual = Integer.highestOneBit((capacity - 1) << 1);
        actual = actual <= 0 ? 1 : actual;

        this.recycleQueue = new MpscArrayQueue<>(actual);
        this.password = password;
        this.buffer = new AbstractFrame[Math.max(actual, 256)];
    }

    public @NonNull F generate(T data, long password) {
        if (password != this.password) {
            return null;
        }
        if(factory == null) {
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
        if(password != this.password) {
            return null;
        }
        return get();
    }

    private @Nullable F get() {
        if (idx == 0) {
            idx = recycleQueue.drain(this::drain, buffer.length);
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
        return recycleQueue.relaxedOffer(frame);
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
            int count = recycleQueue.drain(f -> drain[0]--, drain[0]);

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
