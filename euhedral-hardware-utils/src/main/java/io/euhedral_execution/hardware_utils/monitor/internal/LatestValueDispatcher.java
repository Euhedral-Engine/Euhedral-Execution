package io.euhedral_execution.hardware_utils.monitor.internal;

import io.euhedral_execution.hardware_utils.monitor.ResourceMonitor.MonitorListener;
import io.euhedral_execution.hardware_utils.monitor.SystemUtilization.HardwareUtilization;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/// Bounded latest-value listener registry and dispatcher.
/// Guarantees one-active callback and one-pending coalesced value.
/// Uses snapshot iteration for reentrant listener additions and provides
/// a strict two-phase close barrier with exactly-once unlocked termination notification.
public final class LatestValueDispatcher {

    private static final VarHandle CLOSING;
    private static final VarHandle CLOSED;

    static {
        try {
            CLOSING = MethodHandles.lookup().findVarHandle(LatestValueDispatcher.class, "closing", boolean.class);
            CLOSED = MethodHandles.lookup().findVarHandle(LatestValueDispatcher.class, "closed", boolean.class);
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private final Thread dispatchThread;
    private final AtomicBoolean lock = new AtomicBoolean();
    // Identity registry
    private MonitorListener[] listeners = new MonitorListener[0];

    private @Nullable HardwareUtilization pendingUtilization = null;
    private boolean closing = false;
    private boolean closed = false;
    private @Nullable Runnable terminationHook = null;

    public LatestValueDispatcher() {
        dispatchThread = new Thread(this::dispatchLoop, "Euhedral-ResourceMonitor-Dispatcher");
        dispatchThread.setDaemon(true);
        dispatchThread.start();
        if (!dispatchThread.isAlive()) {
            throw new IllegalStateException("Failed to start LatestValueDispatcher thread");
        }
    }

    private static void awaitWhile(BooleanSupplier condition) {
        int cycles = 0;
        while (condition.getAsBoolean()) {
            if (cycles++ < 128) {
                Thread.onSpinWait();
            } else if (cycles < 512) {
                Thread.yield();
            } else {
                LockSupport.parkNanos(20_000L);
            }
        }
    }

    /// Blocks the dispatch thread until a value is pending or close has begun.
    ///
    /// Lost-wakeup argument. The only producers of work for this thread are
    /// `offer` (writes `pendingUtilization` under the lock, releases the lock with a
    /// release store, then unparks) and `beginClose` (sets CLOSING under the lock, then
    /// unparks). Listener add/remove only mutate the snapshot that is read at dispatch time and
    /// never create work. Every condition is re-read here under the same lock, so a producer's
    /// state is visible once its lock release precedes our acquire; if it releases after our
    /// check, its subsequent unpark leaves a permit that makes the next `park` return at once.
    /// The condition is re-checked after every `park` return, so spurious wake-ups and
    /// interrupts are harmless.
    ///
    /// Interrupts are treated as wake-ups: the flag is cleared so a stray interrupt cannot make
    /// `park` return immediately forever, and the loop re-checks state. Close is signalled by
    /// CLOSING, not by interruption, so close semantics are unchanged.
    private void awaitWork() {
        while (true) {
            acquireLock();
            boolean ready;
            try {
                ready = (boolean) CLOSING.getAcquire(this) || pendingUtilization != null;
            } finally {
                releaseLock();
            }
            if (ready) {
                return;
            }
            LockSupport.park(this);
            Thread.interrupted();
        }
    }

    /// Adds a listener using object identity deduplication while the dispatcher is open.
    public void addListener(@NonNull MonitorListener listener) {
        Objects.requireNonNull(listener);

        acquireLock();
        try {
            if ((boolean) CLOSING.getAcquire(this)) {
                return;
            }
            for (MonitorListener existing : listeners) {
                if (existing == listener) {
                    return;
                }
            }
            MonitorListener[] newListeners = new MonitorListener[listeners.length + 1];
            System.arraycopy(listeners, 0, newListeners, 0, listeners.length);
            newListeners[listeners.length] = listener;
            listeners = newListeners;
        } finally {
            releaseLock();
        }
    }

    /// Removes a listener based on object identity.
    public void removeListener(@NonNull MonitorListener listener) {
        Objects.requireNonNull(listener);
        acquireLock();
        try {
            int index = -1;
            for (int i = 0; i < listeners.length; i++) {
                if (listeners[i] == listener) {
                    index = i;
                    break;
                }
            }
            if (index >= 0) {
                MonitorListener[] newListeners = new MonitorListener[listeners.length - 1];
                System.arraycopy(listeners, 0, newListeners, 0, index);
                System.arraycopy(listeners, index + 1, newListeners, index, listeners.length - index - 1);
                listeners = newListeners;
            }
        } finally {
            releaseLock();
        }
    }

    /// Offers a new utilization record. Replaces any existing pending record.
    public void offer(@NonNull HardwareUtilization utilization) {
        Objects.requireNonNull(utilization);
        acquireLock();
        try {
            if ((boolean) CLOSING.getAcquire(this)) {
                return;
            }
            this.pendingUtilization = utilization;
        } finally {
            releaseLock();
            LockSupport.unpark(this.dispatchThread);
        }
    }

    /// Waits until the dispatch thread has fully exited.
    public void awaitClosed() {
        awaitWhile(() -> !(boolean) CLOSED.getAcquire(this));
    }

    /// Returns true once the dispatch thread has fully exited. Non-blocking.
    public boolean isClosed() {
        return (boolean) CLOSED.getAcquire(this);
    }

    /// Returns true when the caller is the dispatch thread (i.e. inside a listener callback).
    public boolean isDispatchThread() {
        return Thread.currentThread() == dispatchThread;
    }

    /// Initiates shutdown, publishing the termination hook before rejecting new work.
    public void beginClose(@Nullable Runnable terminationHook) {
        acquireLock();
        try {
            if (!(boolean) CLOSING.getAcquire(this)) {
                this.terminationHook = terminationHook;
                CLOSING.setRelease(this, true);
            }
        } finally {
            releaseLock();
        }
        LockSupport.unpark(this.dispatchThread);
    }

    private void dispatchLoop() {
        Runnable hookToRun;
        try {
            while (true) {
                HardwareUtilization toDispatch;
                MonitorListener[] listenersSnapshot;

                awaitWork();
                try {
                    acquireLock();

                    toDispatch = pendingUtilization;
                    pendingUtilization = null;
                    listenersSnapshot = listeners;

                    if ((boolean) CLOSING.getAcquire(this)) {
                        break;
                    }
                    if (toDispatch == null) {
                        continue;
                    }
                } catch (Exception e) {
                    CLOSING.setRelease(this, true);
                    break;
                } finally {
                    releaseLock();
                }

                if (listenersSnapshot != null) {
                    for (MonitorListener listener : listenersSnapshot) {
                        try {
                            listener.update(toDispatch);
                        } catch (Throwable t) {
                            // Isolate faults; do not crash the dispatch thread
                        }
                    }
                }
            }
        } finally {
            acquireLock();
            try {
                hookToRun = this.terminationHook;
                this.terminationHook = null;
                this.listeners = new MonitorListener[0];
                this.pendingUtilization = null;
            } finally {
                releaseLock();
            }

            if (hookToRun != null) {
                try {
                    hookToRun.run();
                } catch (Throwable t) {
                    // Isolate hook faults
                }
            }
            CLOSED.setRelease(this, true);
        }
    }

    private void acquireLock() {
        awaitWhile(() -> !this.lock.compareAndSet(false, true));
    }

    private void releaseLock() {
        this.lock.setRelease(false);
    }
}
