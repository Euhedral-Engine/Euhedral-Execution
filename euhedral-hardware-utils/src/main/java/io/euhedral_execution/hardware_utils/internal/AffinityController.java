package io.euhedral_execution.hardware_utils.internal;

import io.euhedral_execution.hardware_utils.AffinityCapability;
import io.euhedral_execution.hardware_utils.common.UnmodifiableBitSet;
import java.util.BitSet;
import org.slf4j.Logger;

public final class AffinityController {

    private final AffinityProvider provider;
    private final BitSet supported;
    private final int span;
    private final AffinityCapability capability;
    private final UnmodifiableBitSet baseMask;
    private final Logger logger;
    private final ThreadLocal<Lease> leases = new ThreadLocal<>();
    private final ThreadLocal<Owner> owners = new ThreadLocal<>();

    /// Creates the common affinity policy for one immutable topology snapshot.
    ///
    /// @param provider  platform operations, or `null` when affinity is unsupported
    /// @param supported logical CPU IDs accepted by this controller; copied on entry
    /// @param span      exclusive upper bound for logical CPU IDs, including sparse holes
    /// @param logger    bounded diagnostics sink, or `null` in deterministic tests
    public AffinityController(AffinityProvider provider, BitSet supported, int span, Logger logger) {
        if (span < 1 || span > AffinityMasks.MAX_BITS) {
            throw new IllegalArgumentException("Invalid logical CPU span: " + span);
        }
        BitSet owned = (BitSet) supported.clone();
        if (owned.isEmpty() || owned.length() > span) {
            throw new IllegalArgumentException("Supported CPU mask is empty or outside its span");
        }
        this.provider = provider;
        this.supported = owned;
        this.span = span;
        this.logger = logger;

        AffinityCapability selected = AffinityCapability.UNSUPPORTED;
        BitSet base = (BitSet) owned.clone();
        if (provider != null) {
            try {
                AffinityCapability declared = provider.capability();
                if (declared == AffinityCapability.EXACT) {
                    long[] captured = canonical(provider.captureAffinity());
                    if (captured != null) {
                        base = BitSet.valueOf(captured);
                        selected = AffinityCapability.EXACT;
                    }
                } else if (declared == AffinityCapability.LOCALITY_HINT) {
                    selected = AffinityCapability.LOCALITY_HINT;
                }
            } catch (RuntimeException | LinkageError failure) {
                diagnostic("Affinity capability discovery failed", failure);
            }
        }
        capability = selected;
        baseMask = UnmodifiableBitSet.wrap(base);
    }

    /// Returns the stable operational capability selected during construction.
    public AffinityCapability capability() {
        return capability;
    }

    /// Returns the discovered exact mask or the supported-topology fallback.
    public UnmodifiableBitSet baseMask() {
        return baseMask;
    }

    /// Validates and applies the complete little-endian logical CPU mask.
    public boolean setAffinity(long[] source) {
        long[] request = canonical(source);
        if (request == null || capability == AffinityCapability.UNSUPPORTED) {
            return false;
        }
        return capability == AffinityCapability.EXACT ? applyExact(request) : applyLocality(request);
    }

    /// Applies affinity to the current truthful or managed logical CPU.
    public boolean setAffinity() {
        return setAffinity(currentCpu());
    }

    /// Validates one logical CPU before allocating its minimal request mask.
    public boolean setAffinity(int cpu) {
        if (!isSupported(cpu)) {
            return false;
        }
        long[] masks = new long[(cpu >>> 6) + 1];
        masks[cpu >>> 6] = 1L << (cpu & 63);
        return setAffinity(masks);
    }

    /// Copies and validates every logical CPU before allocating the request mask.
    public boolean setAffinity(int[] source) {
        if (source == null || source.length == 0 || source.length > AffinityMasks.MAX_BITS) {
            return false;
        }
        int[] owned = source.clone();
        int highest = -1;
        for (int cpu : owned) {
            if (!isSupported(cpu)) {
                return false;
            }
            highest = Math.max(highest, cpu);
        }
        long[] masks = new long[(highest >>> 6) + 1];
        for (int cpu : owned) {
            masks[cpu >>> 6] |= 1L << (cpu & 63);
        }
        return setAffinity(masks);
    }

    /// Copies and validates a caller-owned bit set before conversion.
    public boolean setAffinity(BitSet source) {
        if (source == null || source.isEmpty() || source.length() > AffinityMasks.MAX_BITS) {
            return false;
        }
        BitSet owned = (BitSet) source.clone();
        if (owned.length() > AffinityMasks.MAX_BITS) {
            return false;
        }
        return setAffinity(owned.toLongArray());
    }

    /// Returns a truthful provider CPU, then the managed logical owner, otherwise `-1`.
    public int currentCpu() {
        if (provider != null) {
            try {
                int cpu = provider.currentCpu();
                if (isSupported(cpu)) {
                    return cpu;
                }
            } catch (RuntimeException | LinkageError failure) {
                diagnostic("Current CPU query failed", failure);
            }
        }
        Owner owner = owners.get();
        return owner == null ? -1 : owner.cpu;
    }

    /// Restores the first exact mask or clears the locality hint, then removes the lease.
    public void releaseAffinity() {
        Lease lease = leases.get();
        if (lease == null) {
            return;
        }
        try {
            boolean restored = lease.exact ? provider.restoreExact(lease.snapshot.clone()) : provider.releaseLocality();
            if (!restored) {
                diagnostic("Affinity release failed", null);
            }
        } catch (RuntimeException | LinkageError failure) {
            diagnostic("Affinity release failed", failure);
        } finally {
            leases.remove();
        }
    }

    /// Pushes a scoped logical owner without claiming physical CPU placement.
    ///
    /// @param cpu supported logical CPU ID associated with the managed task
    public ManagedOwner bindManagedCpu(int cpu) {
        if (!isSupported(cpu)) {
            throw new IllegalArgumentException("Unsupported logical CPU: " + cpu);
        }
        Owner owner = new Owner(Thread.currentThread(), owners.get(), cpu);
        owners.set(owner);
        return owner;
    }

    /// Returns whether this thread still owes exact restoration or locality release.
    public boolean hasAffinityLease() {
        return leases.get() != null;
    }

    /// Returns whether this thread has a scoped managed logical owner.
    public boolean hasManagedOwner() {
        return owners.get() != null;
    }

    public boolean setTimerResolution(long nanos) {
        if (provider == null) {
            return false;
        }
        return provider.setTimerResolution(nanos);
    }

    /// Applies an exact mask only after preserving the thread's first original binding.
    private boolean applyExact(long[] request) {
        Lease existing = leases.get();
        Lease pending = null;
        if (existing == null) {
            try {
                long[] snapshot = canonical(provider.captureAffinity());
                if (snapshot == null) {
                    return false;
                }
                pending = new Lease(true, snapshot);
                leases.set(pending);
            } catch (RuntimeException | LinkageError failure) {
                diagnostic("Affinity capture failed", failure);
                return false;
            }
        }
        boolean applied = false;
        try {
            applied = provider.applyExact(request.clone());
            return applied;
        } catch (RuntimeException | LinkageError failure) {
            diagnostic("Affinity apply failed", failure);
            return false;
        } finally {
            if (!applied && pending != null) {
                leases.remove();
            }
        }
    }

    /// Resolves every requested CPU to one shared locality before applying one hint.
    ///
    /// A locality hint expresses scheduler preference; it does not claim exact placement.
    private boolean applyLocality(long[] request) {
        int locality = -1;
        try {
            for (int wordIndex = 0; wordIndex < request.length; wordIndex++) {
                long word = request[wordIndex];
                while (word != 0) {
                    int bit = Long.numberOfTrailingZeros(word);
                    int mapped = provider.localityForCpu((wordIndex << 6) + bit);
                    if (mapped <= 0 || locality >= 0 && locality != mapped) {
                        return false;
                    }
                    locality = mapped;
                    word &= word - 1;
                }
            }
        } catch (RuntimeException | LinkageError failure) {
            diagnostic("Affinity locality mapping failed", failure);
            return false;
        }

        Lease pending = null;
        if (leases.get() == null) {
            pending = new Lease(false, null);
            leases.set(pending);
        }
        boolean applied = false;
        try {
            applied = provider.applyLocality(locality);
            return applied;
        } catch (RuntimeException | LinkageError failure) {
            diagnostic("Affinity locality apply failed", failure);
            return false;
        } finally {
            if (!applied && pending != null) {
                leases.remove();
            }
        }
    }

    private long[] canonical(long[] source) {
        return AffinityMasks.canonical(source, span, supported);
    }

    private boolean isSupported(int cpu) {
        return cpu >= 0 && cpu < span && supported.get(cpu);
    }

    private void diagnostic(String message, Throwable failure) {
        if (logger == null) {
            return;
        }
        if (failure == null) {
            logger.debug(message);
        } else {
            logger.debug(message, failure);
        }
    }

    /// Scoped logical-owner token that closes on its creator thread in LIFO order.
    public interface ManagedOwner extends AutoCloseable {

        @Override
        void close();
    }

    private static final class Lease {

        private final boolean exact;
        private final long[] snapshot;

        private Lease(boolean exact, long[] snapshot) {
            this.exact = exact;
            this.snapshot = snapshot == null ? null : snapshot.clone();
        }
    }

    private final class Owner implements ManagedOwner {

        private final Thread thread;
        private final Owner predecessor;
        private final int cpu;
        private boolean closed;

        private Owner(Thread thread, Owner predecessor, int cpu) {
            this.thread = thread;
            this.predecessor = predecessor;
            this.cpu = cpu;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            if (Thread.currentThread() != thread || owners.get() != this) {
                throw new IllegalStateException("Managed CPU bindings must close in owner-thread LIFO order");
            }
            if (predecessor == null) {
                owners.remove();
            } else {
                owners.set(predecessor);
            }
            closed = true;
        }
    }
}
