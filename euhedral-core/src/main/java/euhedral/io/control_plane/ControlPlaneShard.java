package euhedral.io.control_plane;

import static euhedral.io.utils.MathFunctions.unsignedMultiplyHigh;

import euhedral.io.flow_control.FluxEdge;
import euhedral.io.flow_control.FluxNode;
import euhedral.io.flow_control.NoOpSubscriber;
import euhedral.io.frames.AbstractFrame;
import euhedral.io.interfaces.CloneableObject;
import euhedral.io.resource_monitoring.NumaMapper;
import euhedral.io.resource_monitoring.NumaMapper.OriginLocation;
import euhedral.io.resource_monitoring.NumaMapper.SocketTopology;
import euhedral.io.resource_monitoring.ResourceMonitor;
import euhedral.io.resource_monitoring.SystemUtilization.CoreSnapshot;
import euhedral.io.resource_monitoring.SystemUtilization.SocketSnapshot;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.BitSet;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import lombok.Getter;
import org.jctools.queues.SpscArrayQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ControlPlaneShard implements AutoCloseable {

    protected final Logger logger;
    protected final ResourceMonitor resourceMonitor;

    @Getter
    protected final int shardId;
    @Getter
    protected final String shardName;

    protected final AtomicBoolean started = new AtomicBoolean(false);
    protected final AtomicBoolean rebalancing = new AtomicBoolean(false);
    protected final AtomicInteger coresToDrain = new AtomicInteger(0);

    protected final AtomicReference<FluxNode> coreDistributor = new AtomicReference<>();

    protected final MeterRegistry meterRegistry;
    protected final CloneableObject cloneableObject;

    protected volatile long currentVersion = -1;
    protected volatile ExecutorService shardExecutor;

    protected CloneableObject[] clones;
    protected FluxEdge[] coreHandles;
    protected int[] activeCoreIds = new int[0];
    protected volatile int[] reverseMapping = new int[0];

    public ControlPlaneShard(int shardId, String shardName,
            CloneableObject obj,
            ResourceMonitor resourceMonitor,
            MeterRegistry meterRegistry) {
        this.logger = LoggerFactory.getLogger(shardName);
        this.resourceMonitor = resourceMonitor;
        this.shardId = shardId;
        this.shardName = shardName;
        this.meterRegistry = meterRegistry;
        this.cloneableObject = obj;
        this.clones = new CloneableObject[0];
        this.coreHandles = new FluxEdge[0];
    }

    public void start(SocketSnapshot snapshot, SocketTopology topology, FluxEdge upstream) {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        logger.info("Starting.");
        shardExecutor = Executors.newFixedThreadPool(topology.effectiveCores().get().length(),
                (r) -> new Thread(r, shardName + "+ExecutorService"));
        FluxNode coreDistributor = new FluxNode(shardName + "-CoreDistributor",
                topology.effectiveCores().get().length(), this::route, false);
        this.coreDistributor.set(coreDistributor);
        coreDistributor.onSubscribe(upstream);
        update(snapshot, topology);
    }

    protected int route(AbstractFrame frame, int mapSize) {
        RoutingPolicy policy = frame.getRoutingPolicy();
        if(policy != null && policy.level > RoutingPolicy.SOCKET_LOCAL.level) {
            int[] reverseMapping = this.reverseMapping;
            OriginLocation location = frame.getOrigin();
            int node = location != null ? location.core() : -1;

            if (node >= 0 && node < reverseMapping.length && (node = reverseMapping[node]) < mapSize && node >= 0) {
                return node;
            }
        }

        long rotated = Long.rotateLeft(frame.getCombinedHash(), 31);
        return (int) unsignedMultiplyHigh(rotated, mapSize);
    }

    public void update(SocketSnapshot snapshot, SocketTopology topology) {
        if (!started.get() || rebalancing.get()) {
            logger.error(
                    "Cannot update if not started or rebalancing. Started: {} Rebalancing: {} CoresToDrain: {}",
                    started.get(), rebalancing.get(), coresToDrain.get());
            return;
        }

        int nextVersion = topology.version().get();
        if (this.currentVersion != nextVersion) {
            if (currentVersion >= 0) {
                logger.warn(
                        "Detected change in topology. Initiating shard rebalance for version {}",
                        nextVersion);
            } else {
                logger.info("Initializing clones for topology version {}", nextVersion);
            }
            this.currentVersion = nextVersion;
            handleTopologyChange(snapshot, topology);
        } else {
            for (int coreId : activeCoreIds) {
                updateClone(snapshot.coreSnapshots()[coreId]);
            }
        }
    }

    @SuppressWarnings({"resource"})
    protected void handleTopologyChange(SocketSnapshot snapshot, SocketTopology topology) {
        if (!rebalancing.compareAndSet(false, true)) {
            return;
        }

        FluxNode distributor = coreDistributor.get();
        distributor.setDrain(true);

        BitSet newCores = topology.effectiveCores().get();
        int[] nextCores = new int[newCores.cardinality()];

        CloneableObject[] nextClones = new CloneableObject[topology.effectiveCores()
                .get().length()];
        FluxEdge[] nextHandles = new FluxEdge[topology.effectiveCores().get()
                .length()];

        int idx = 0;
        int[] reverseMapping = new int[NumaMapper.INSTANCE.getCoreCount()];
        for (int i = newCores.nextSetBit(0); i >= 0; i = newCores.nextSetBit(i + 1)) {
            nextHandles[i] = i >= clones.length ? null : coreHandles[i];
            nextHandles[i] =
                    nextHandles[i] == null ? new FluxEdge(distributor.getDrainFlag())
                            : nextHandles[i];
            reverseMapping[i] = idx++;
        }
        this.coreHandles = nextHandles;

        distributor.setDownstreamMapping(newCores, nextHandles);
        this.reverseMapping = reverseMapping;

        idx = 0;
        // Create new clones
        for (int i = newCores.nextSetBit(0); i >= 0; i = newCores.nextSetBit(i + 1)) {
            if (i >= clones.length || clones[i] == null) {
                nextClones[i] = spawnClone(i, snapshot.coreSnapshots()[i], nextClones);
            } else {
                nextClones[i] = clones[i];
                final int id = i;
                CompletableFuture.runAsync(() -> updateClone(snapshot.coreSnapshots()[id]),
                        shardExecutor);
            }
            nextCores[idx++] = i;
        }

        if (currentVersion > 0) {
            drainAndPruneClones(newCores, snapshot, nextClones);
            this.clones = nextClones;
            this.activeCoreIds = nextCores;
        } else {
            this.clones = nextClones;
            this.activeCoreIds = nextCores;
            this.coreDistributor.get().setDownstreamMapping(newCores, nextHandles);
            for(var clone : clones) {
                if(clone != null) {
                    clone.setDrainMode(false);
                }
            }
            this.coreDistributor.get().setDrain(false);
            rebalancing.set(false);
        }
    }

    private void updateClone(CoreSnapshot snapshot) {
        if (snapshot == null) {
            return;
        }

        CloneableObject clone = this.clones[snapshot.coreId()];

        if (!clone.isStarted()) {
            logger.info("Starting clone {}", snapshot.coreId());
            clone.start();
        }
        clone.update(snapshot);
    }

    private CloneableObject spawnClone(int coreId, CoreSnapshot snapshot,
            CloneableObject[] nextClones) {
        CloneConfig config = new CloneConfig(shardName, coreId, snapshot.quotaCpus(),
                snapshot.effectiveCpus(),
                resourceMonitor,
                meterRegistry, shardName);

        CloneableObject clone = cloneableObject.clone(config);
        nextClones[coreId] = clone;

        clone.ingest(coreHandles[coreId]);
        clone.output().subscribe(new NoOpSubscriber());

        clone.setDrainMode(rebalancing.get());
        clone.start();
        clone.update(snapshot);
        return clone;
    }

    private void drainAndPruneClones(BitSet active, SocketSnapshot snapshot,
            CloneableObject[] nextClones) {
        logger.info("Draining and pruning clones.");

        Set<Integer> deadClones = new HashSet<>();
        SpscArrayQueue<CloneableObject> clones = new SpscArrayQueue<>(
                this.clones.length);
        for (int i = 0; i < this.clones.length; i++) {
            CloneableObject clone = this.clones[i];
            if (clone != null) {
                if (!active.get(i)) {
                    this.clones[i] = null;
                    deadClones.add(i);
                }
                clone.setDrainMode(true);
                coresToDrain.incrementAndGet();
                clones.relaxedOffer(clone);
            }
        }

        if (coresToDrain.get() == 0) {
            tryRestartIngest(active);
        }

        clones.drain(clone -> CompletableFuture.runAsync(() -> {
            Thread.currentThread().setName(shardName + "-" + clone.getCore());
            final long deadline =
                    System.nanoTime() + Duration.ofSeconds(1).toNanos();

            while (!clone.isDrained()) {
                LockSupport.parkNanos(5_000);
                if (System.nanoTime() >= deadline) {
                    break;
                }
            }
            if (System.nanoTime() >= deadline) {
                logger.error(
                        "Failed to drain clone on core {}. Forcing shutdown and restarting if core is assigned.",
                        clone.getCore());
                try {
                    clone.close();
                } catch (Exception e) {
                    logger.error("Forced shutdown failed for core {}", clone.getCore(), e);
                } finally {
                    clone.dumpLocks();
                }
                if (!deadClones.contains(clone.getCore())) {
                    int core = clone.getCore();
                    logger.info("Restarting clone on core {}", core);
                    spawnClone(core, snapshot.coreSnapshots()[core], nextClones);
                }
            } else if (deadClones.contains(clone.getCore())) {
                int core = clone.getCore();
                logger.info("Gracefully shutting down clone on core {}", core);
                try {
                    clone.close();
                } catch (Exception e) {
                    logger.error("Failed to shut down clone on core {}", core);
                } finally {
                    clone.dumpLocks();
                }
            }
            coresToDrain.decrementAndGet();
            clone.setDrainMode(false);
            tryRestartIngest(active);
        }, shardExecutor));
    }

    protected void tryRestartIngest(BitSet active) {
        if (coresToDrain.get() == 0) {
            logger.info("Restarting ingest.");
            long deadline =
                    System.nanoTime() + Duration.ofMillis(500).toNanos();
            while (!coreDistributor.get()
                    .setDownstreamMapping(active, coreHandles)) {
                LockSupport.parkNanos(5_000);
                if (System.nanoTime() >= deadline) {
                    logger.error("Failed to restart ingest. Closing.");
                    try {
                        close();
                    } catch (Exception e) {
                        logger.error("CRITICAL. Failed to close.", e);
                    }
                    rebalancing.set(false);
                    return;
                }
            }
            rebalancing.set(false);
        }
    }

    @Override
    public void close() throws Exception {
        started.set(false);
        logger.info("Closing.");
        coreDistributor.getAndUpdate(distributor -> {
            if (distributor != null) {
                try {
                    distributor.close();
                } catch (Exception e) {
                    logger.error("CRITICAL: Failed to close the FluxNode.", e);
                }
            }
            return null;
        });

        logger.info("Closing interceptors.");
        for (int i = 0; i < coreHandles.length; i++) {
            if (coreHandles[i] != null) {
                coreHandles[i].onComplete();
                coreHandles[i] = null;
            }
        }

        logger.info("Closing clones.");
        for (int i = 0; i < clones.length; i++) {
            CloneableObject clone = clones[i];
            if (clone != null) {
                try {
                    clone.close();
                } catch (Exception e) {
                    System.out.printf("Failed to close clone.\n%s", e);
                }
                clone.dumpLocks();
                clones[i] = null;
            }
        }

        if (shardExecutor != null) {
            shardExecutor.shutdownNow();
        }
        logger.info("Closed.");
    }

    public void shutDownShard(AtomicInteger shutDownCounter) {
        if (!started.compareAndSet(true, false)) {
            return;
        }

        logger.info("Shutting down.");

        AtomicInteger drainCounter = new AtomicInteger(activeCoreIds.length);
        for (int i = 0; i < clones.length; i++) {
            if (clones[i] == null) {
                continue;
            }

            CloneableObject clone = clones[i];

            clones[i] = null;

            shutdownCore(i, clone, drainCounter, shutDownCounter);
        }
        this.activeCoreIds = new int[0];
        this.shardExecutor.shutdown();
        this.shardExecutor = null;
    }

    private void shutdownCore(int coreId, CloneableObject oldClone,
            AtomicInteger drainSignal, AtomicInteger shutDownCounter) {
        logger.info("Shutting down clone on core {}", coreId);
        oldClone.setDrainMode(true);

        CompletableFuture.runAsync(() -> {
            Thread.currentThread().setName(shardName + "-" + coreId);
            long deadline = System.nanoTime() + Duration.ofMinutes(1).toNanos();
            try {
                while (!oldClone.isDrained()) {
                    LockSupport.parkNanos(5_000);
                    if (System.nanoTime() >= deadline) {
                        logger.error("Clone on core {} timed out. Forcing shutdown.", coreId);
                        oldClone.close();
                        break;
                    }
                }
            } catch (Exception e) {
                logger.error("Shutdown cleanup failed for Core {}", coreId, e);
            } finally {
                try {
                    oldClone.close();
                } catch (Exception e) {
                    logger.error("CRITICAL: Worker on core {} failed to close.", coreId, e);
                    oldClone.dumpLocks();
                } finally {
                    drainSignal.decrementAndGet();
                    if (drainSignal.get() == 0) {
                        shutDownCounter.decrementAndGet();
                    }
                }
            }
        }, shardExecutor);
    }

    public boolean isStarted() {
        return started.get();
    }

    public boolean isRebalancing() {
        return rebalancing.get();
    }

    public boolean isDrained() {
        if (!started.get()) {
            return true;
        }
        if (rebalancing.get()) {
            return false;
        }

        FluxNode coreDistributor = this.coreDistributor.get();
        if(coreDistributor != null && !coreDistributor.isDrained()) {
            return false;
        }

        boolean drained = true;
        for (int i = 0; i < clones.length && drained; i++) {
            CloneableObject clone = clones[i];
            if (clone != null) {
                drained &= clone.isDrained();
            }
        }
        return drained;
    }

    public ControlPlaneShard clone(int shardId, String shardName) {
        return new ControlPlaneShard(shardId, shardName, this.cloneableObject,
                this.resourceMonitor, this.meterRegistry);
    }
}
