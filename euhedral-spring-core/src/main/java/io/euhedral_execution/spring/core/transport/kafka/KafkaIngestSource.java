package io.euhedral_execution.spring.core.transport.kafka;

import io.euhedral_execution.core.frames.AbstractFrame;
import io.euhedral_execution.core.generics.LatticeReceiver;
import io.euhedral_execution.core.generics.LatticeSource;
import io.euhedral_execution.core.impl.FrameFactory;
import io.euhedral_execution.core.impl.FrameFactory.FrameCreate;
import io.euhedral_execution.core.impl.FrameFactory.FrameReplace;
import io.euhedral_execution.core.impl.FrameManager;
import io.euhedral_execution.data_structures.queues.SpscQueue;
import io.euhedral_execution.hashing.HasherApi;
import io.euhedral_execution.spring.core.frames.KafkaFrame;
import io.euhedral_execution.spring.core.transport.kafka.IngestEventHandler.Event;
import io.euhedral_execution.spring.core.transport.kafka.OffsetCollector.CommitPolicy;
import io.euhedral_execution.spring.core.transport.kafka.OffsetCollector.OffsetMd;
import io.euhedral_execution.spring.core.utils.KillSwitch;
import it.unimi.dsi.fastutil.longs.Long2ObjectArrayMap;
import it.unimi.dsi.fastutil.longs.LongArraySet;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@SuppressWarnings("unused")
public class KafkaIngestSource implements LatticeSource {

    public static final String MIN_COMMIT_BATCH = "min.commit.batch";
    public static final String MAX_COMMIT_BATCH = "max.commit.batch";
    public static final String MIN_COMMIT_INTERVAL = "min.commit.interval.micros";
    public static final String MAX_COMMIT_INTERVAL = "max.commit.interval.micros";
    private static final Set<String> HOT_SWAPPABLE = Set.of(MIN_COMMIT_BATCH, MAX_COMMIT_BATCH,
            MIN_COMMIT_INTERVAL, MAX_COMMIT_INTERVAL);
    private static final VarHandle COMPLETE;
    private static final VarHandle DOWNSTREAM;
    private static final VarHandle HEARTBEAT;
    private static final VarHandle LAST_POLL;
    private static final VarHandle LOCK;

    static {
        try {
            COMPLETE = MethodHandles.lookup()
                    .findVarHandle(KafkaIngestSource.class, "complete", boolean.class);
            DOWNSTREAM = MethodHandles.lookup()
                    .findVarHandle(KafkaIngestSource.class, "downstream", LatticeReceiver.class);
            HEARTBEAT = MethodHandles.lookup().findVarHandle(KafkaIngestSource.class, "heartbeatNs", long.class);
            LAST_POLL = MethodHandles.lookup()
                    .findVarHandle(KafkaIngestSource.class, "lastPollNs", long.class);
            LOCK = MethodHandles.lookup()
                    .findVarHandle(KafkaIngestSource.class, "lock", boolean.class);
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    public static long getPartitionHash(TopicPartition partition) {
        return HasherApi.getHash(partition.topic(), partition.partition());
    }

    public static long getPartitionHash(ConsumerRecord<?, ?> cRecord) {
        return HasherApi.getHash(cRecord.topic(), cRecord.partition());
    }

    private static CommitPolicy getCommitPolicy(Map<String, Object> props) {
        long minTime, maxTime, minBatch, maxBatch;

        minTime = Long.parseLong(props.getOrDefault(MIN_COMMIT_INTERVAL, "0").toString()) * 1_000;
        maxTime =
                Long.parseLong(props.getOrDefault(MAX_COMMIT_INTERVAL, "0").toString()) * 1_000;
        minBatch = Long.parseLong(props.getOrDefault(MIN_COMMIT_BATCH, "0").toString());
        maxBatch = Long.parseLong(props.getOrDefault(MAX_COMMIT_BATCH, "0").toString());

        props.put(MIN_COMMIT_INTERVAL, minTime);
        props.put(MAX_COMMIT_INTERVAL, maxTime);
        props.put(MIN_COMMIT_BATCH, minBatch);
        props.put(MAX_COMMIT_BATCH, maxBatch);
        return new CommitPolicy(minTime, maxTime, minBatch, maxBatch);
    }

    private final Logger logger;

    private final long ingestPassword = HasherApi.mix(ThreadLocalRandom.current().nextLong());
    private final Map<String, Object> consumerProperties = new HashMap<>();

    private final AtomicReference<KafkaConsumer<?, ?>> kafkaConsumer = new AtomicReference<>();
    private final OffsetCollector offsetCollector;
    private final Set<String> topics = new HashSet<>();

    private final Long2ObjectArrayMap<PartitionIngestor> ingestors = new Long2ObjectArrayMap<>();
    private final SpscQueue<ConsumerRecord<?, ?>> queue;
    private final FrameManager<ConsumerRecord<?, ?>, KafkaFrame> manager;
    private final IngestEventHandler eventHandler = new IngestEventHandler(this.topics);
    private final Thread heartbeat;

    private LatticeReceiver downstream;

    private long heartbeatNs = 3_000_000;
    private boolean complete = false;

    private long lastPollNs = 0;
    private boolean lock = false;

    public KafkaIngestSource(String name, Map<String, Object> properties) {
        this.logger = LoggerFactory.getLogger(KafkaIngestSource.class.getSimpleName() + "-" + name);
        this.queue = new SpscQueue<>(4_096);
        this.manager = createFrameManager();
        this.kafkaConsumer.set(new KafkaConsumer<>(properties));
        this.offsetCollector = new OffsetCollector(this.kafkaConsumer,
                getCommitPolicy(properties), ingestPassword);
        updateInternal(properties);
        subscribe();

        this.heartbeat = new Thread(() -> {
            while(!Thread.currentThread().isInterrupted()) {
                long now = System.nanoTime();

                long diff = now - (long) LAST_POLL.getAcquire(this);
                long heartbeat = (long) HEARTBEAT.getAcquire(this);
                if(diff >= heartbeat * 0.75 && LOCK.compareAndSet(this, false, true)) {
                    try {
                        KafkaConsumer<?, ?> consumer = this.kafkaConsumer.getAcquire();
                        consumer.pause(consumer.assignment());
                        consumer.poll(Duration.ZERO);
                        consumer.resume(consumer.assignment());

                        LAST_POLL.setRelease(this, now);
                    } catch (Exception ignored) {
                        // In case of NPE
                    } finally {
                        LOCK.setRelease(this, false);
                    }
                    LockSupport.parkNanos((long) (0.75 * heartbeat));
                    continue;
                }
                LockSupport.parkNanos((long) (0.75 * heartbeat - diff));
            }
        });
        this.heartbeat.start();
    }

    @Override
    public void request(long demand) {
        if ((boolean) COMPLETE.getOpaque(this) || demand <= 0) {
            return;
        }
        if(!LOCK.compareAndSet(this, false, true)) {
            return;
        }

        try {
            boolean refresh = drainEvents();
            if (this.topics.isEmpty()) {
                this.offsetCollector.drain(System.nanoTime(), this.ingestPassword);
                complete();
                return;
            }

            demand -= this.queue.drain(this::push, demand);
            if (refresh) {
                this.queue.drain(this::push);
                refresh();
            } else {
                this.offsetCollector.drain(System.nanoTime(), this.ingestPassword);
            }

            while (demand > 0) {
                LAST_POLL.setRelease(this, System.nanoTime());
                var records = this.kafkaConsumer.getPlain().poll(Duration.ZERO);

                int count = 0;
                for (var cRecord : records) {
                    if (demand-- > 0) {
                        push(cRecord);
                    } else {
                        this.queue.offer(cRecord);
                    }
                    count++;
                }
                this.offsetCollector.drain(System.nanoTime(), this.ingestPassword);
                if (count < 128) {
                    break;
                }
            }

            VarHandle.releaseFence();
        } finally {
            LOCK.setRelease(this, false);
        }
    }

    private void push(ConsumerRecord<?, ?> cRecord) {
        LatticeReceiver receiver = (LatticeReceiver) DOWNSTREAM.getOpaque(this);
        if (receiver == null) {
            return;
        }
        long partHash = getPartitionHash(cRecord);
        PartitionIngestor sender = ingestors.get(partHash);

        KafkaFrame frame = manager.getOrCreate(cRecord, this.ingestPassword);
        this.offsetCollector.registerFrame(sender.partition, frame, this.ingestPassword);

        receiver.push(frame);
    }

    private boolean drainEvents() {
        this.eventHandler.drain();

        if (this.eventHandler.partitionUpdate) {
            buildPartitionMap();
        }
        if (this.eventHandler.newProperties != null && updateInternal(
                this.eventHandler.newProperties)) {
            return true;
        }
        if (this.eventHandler.topicUpdate) {
            subscribe();
            LockSupport.parkNanos(10_000);
        }
        return false;
    }

    private void refresh() {
        this.logger.warn("Updating Kafka consumer.");
        long drainDeadline = System.currentTimeMillis() + Duration.ofMillis(500).toNanos();
        KafkaConsumer<?, ?> kafkaConsumer = this.kafkaConsumer.getPlain();
        kafkaConsumer.unsubscribe();

        while (true) {
            long now = System.nanoTime();
            boolean drained = this.offsetCollector.isEmpty();
            boolean timedOut = now >= drainDeadline;
            if (drained || timedOut) {
                if (!drained) {
                    this.logger.warn(
                            "Forcing Kafka consumer swap due to offset commit drain timeout.");
                }
                this.ingestors.values().forEach(ingestor -> {
                    this.logger.info("Removing partition: {}-{}", ingestor.partition.topic(),
                            ingestor.partition.partition());
                    ingestor.killSwitch.boop();
                });
                this.kafkaConsumer.set(new KafkaConsumer<>(consumerProperties));
                this.ingestors.clear();
                this.offsetCollector.reset();
                subscribe();
                this.logger.info("Update complete.");
                break;
            }
            this.offsetCollector.drain(this.ingestPassword);
            LockSupport.parkNanos(10_000);
        }
    }

    public void update(Map<String, Object> properties) {
        Event event = Event.configUpdate(properties);
        eventHandler.add(event);
    }

    public void addTopic(String topic) {
        logger.info("Topic added: {}", topic);
        Event event = Event.addTopic(topic);
        this.eventHandler.add(event);
    }

    public void removeTopic(String topic) {
        logger.info("Topic removed: {}", topic);
        Event event = Event.removeTopic(topic);
        this.eventHandler.add(event);
    }

    private void subscribe() {
        KafkaConsumer<?, ?> consumer = this.kafkaConsumer.getOpaque();
        consumer.subscribe(this.topics,
                new RebalanceListener(this.kafkaConsumer, consumer, this.eventHandler));
    }

    private boolean updateInternal(Map<String, Object> properties) {
        boolean requiresFullUpdate = false;
        boolean requiresLightUpdate = false;

        for (var entry : properties.entrySet()) {
            Object oldVal = this.consumerProperties.get(entry.getKey());
            Object newVal = entry.getValue();

            boolean equal = Objects.equals(newVal, oldVal)
                    || entry.getKey().equals(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG);

            if (!equal) {
                requiresLightUpdate = true;

                if (!HOT_SWAPPABLE.contains(entry.getKey())) {
                    requiresFullUpdate = true;
                    break;
                }
            }
        }

        if (!requiresFullUpdate) {
            for (var entry : this.consumerProperties.entrySet()) {
                if (!properties.containsKey(entry.getKey())) {
                    requiresLightUpdate = true;
                    requiresFullUpdate = true;
                    break;
                }
            }
        }

        if (!requiresLightUpdate) {
            return false;
        }

        this.consumerProperties.clear();
        this.consumerProperties.putAll(properties);

        if (requiresFullUpdate) {
            setDefaultProperties();
            return true;
        }

        this.offsetCollector.setCommitPolicy(getCommitPolicy(this.consumerProperties));
        return false;
    }

    private void setDefaultProperties() {
        this.consumerProperties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        this.consumerProperties.putIfAbsent(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 1024);

        long heartbeatNs = Long.parseLong(
                this.consumerProperties.getOrDefault(
                        ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG, "3000"
                ).toString()
        ) * 1_000_000;

        long sessionTimeoutNs = Long.parseLong(
                this.consumerProperties.getOrDefault(
                        ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, "45000"
                ).toString()
        ) * 1_000_000;
        HEARTBEAT.setRelease(this, Math.min(heartbeatNs, sessionTimeoutNs));

        this.consumerProperties.put(ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG,
                heartbeatNs / 1_000_000);
        this.consumerProperties.put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG,
                sessionTimeoutNs / 1_000_000);

        this.offsetCollector.setCommitPolicy(getCommitPolicy(this.consumerProperties));
    }

    private void buildPartitionMap() {
        Set<TopicPartition> assignments = this.kafkaConsumer.get().assignment();

        LongArraySet old = new LongArraySet(this.ingestors.keySet());

        for (var part : assignments) {
            long hash = getPartitionHash(part);
            PartitionIngestor ingestor = this.ingestors.get(hash);
            if (ingestor == null) {
                this.logger.info("Adding partition: {}-{}", part.topic(), part.partition());
                ingestor = new PartitionIngestor(part, hash, new KillSwitch());
                ingestor.killSwitch().addGoner(this::complete);
            }
            this.ingestors.put(hash, ingestor);
            old.remove(hash);
        }
        old.forEach(hash -> {
            PartitionIngestor rem = this.ingestors.remove(hash);
            this.logger.info("Removing partition: {}-{}", rem.partition.topic(),
                    rem.partition.partition());
            rem.killSwitch.boop();
        });
    }

    private FrameManager<ConsumerRecord<?, ?>, KafkaFrame> createFrameManager() {
        FrameManager<ConsumerRecord<?, ?>, KafkaFrame> manager = new FrameManager<>(8_192,
                this.ingestPassword);

        FrameCreate<ConsumerRecord<?, ?>, KafkaFrame> create = (idHash, cRecord) -> {
            long partHash = getPartitionHash(cRecord);
            PartitionIngestor sender = ingestors.get(partHash);
            return new KafkaFrame(idHash, cRecord, new OffsetMd(cRecord.offset()), manager,
                    sender.killSwitch);
        };
        FrameReplace<ConsumerRecord<?, ?>, KafkaFrame> replace = (cRecord, frame) -> {
            long partHash = getPartitionHash(cRecord);
            PartitionIngestor sender = ingestors.get(partHash);
            frame.replace(cRecord, new OffsetMd(cRecord.offset()), sender.killSwitch);
        };
        manager.setFactory(new FrameFactory<>(create, replace));
        return manager;
    }

    @Override
    public void addDownstream(LatticeReceiver downstream) {
        if (!DOWNSTREAM.compareAndSet(this, null, downstream)) {
            downstream.onError(
                    new IllegalAccessException("This class can only have one downstream"));
        }
    }

    @Override
    public long pull(Consumer<AbstractFrame> consumer, long demand) {
        return 0;
    }

    @Override
    public void complete() {
        if (COMPLETE.compareAndSet(this, false, true)) {
            LatticeReceiver downstream = (LatticeReceiver) DOWNSTREAM.getAndSetRelease(this, null);
            if (downstream != null) {
                downstream.onComplete();
            }

            try {
                this.heartbeat.interrupt();
                LockSupport.unpark(this.heartbeat);
                this.heartbeat.interrupt();
                this.heartbeat.join(500);
            } catch (Exception ignored) {
                Thread.currentThread().interrupt();
                // Ignore interrupt on complete
            }

            KafkaConsumer<?, ?> consumer = this.kafkaConsumer.getAndSet(null);
            if (consumer != null) {
                consumer.unsubscribe();
            }

            this.queue.clear();
        }
    }

    public boolean isComplete() {
        return (boolean) COMPLETE.getAcquire(this);
    }

    private record PartitionIngestor(TopicPartition partition, long partitionHash,
                                     KillSwitch killSwitch) {

    }
}
