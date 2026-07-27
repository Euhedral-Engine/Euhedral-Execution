package io.euhedral_execution.spring.core.transport.kafka;

import io.euhedral_execution.core.utils.CommonVarHandles;
import io.euhedral_execution.spring.core.frames.KafkaFrame;
import io.euhedral_execution.spring.core.internal.Constants;
import it.unimi.dsi.fastutil.longs.Long2ObjectArrayMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongHeapPriorityQueue;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@SuppressWarnings("unused")
public final class OffsetCollector {

    private static final VarHandle COMMIT = CommonVarHandles.makeHandle(MethodHandles.lookup(),
            OffsetCollector.class, "commitPolicy", CommitPolicy.class);

    private final Logger logger = LoggerFactory.getLogger(Constants.getLoggerName(OffsetCollector.class));
    private final long ingestPassword;

    private final AtomicReference<? extends Consumer<?, ?>> kafkaConsumer;
    private final Long2ObjectArrayMap<PartitionCollector> collectors =
            new Long2ObjectArrayMap<>(128);

    private CommitPolicy commitPolicy;

    public OffsetCollector(AtomicReference<? extends Consumer<?, ?>> kafkaConsumer,
            CommitPolicy commitPolicy, long ingestPassword) {
        this.ingestPassword = ingestPassword;
        this.commitPolicy = commitPolicy;
        this.kafkaConsumer = kafkaConsumer;
    }

    public void setCommitPolicy(CommitPolicy policy) {
        COMMIT.setRelease(this, policy);
    }

    public void registerFrame(TopicPartition partition, KafkaFrame frame, long ingestPassword) {
        if (this.ingestPassword == ingestPassword) {
            long key = KafkaIngestSource.getPartitionHash(partition);
            PartitionCollector collector =
                    collectors.computeIfAbsent(key, k -> new PartitionCollector(partition));
            collector.register(frame);
        }
    }

    public void drain(long password) {
        drain(Long.MAX_VALUE, password);
    }

    public void drain(long now, long password) {
        if (password != this.ingestPassword) {
            return;
        }

        Map<TopicPartition, OffsetAndMetadata> offsetMap = new HashMap<>();
        for (var collector : collectors.values()) {
            long nextOffset = collector.collect(now, (CommitPolicy) COMMIT.getAcquire(this));
            if (nextOffset > 0) {
                offsetMap.put(collector.partition, new OffsetAndMetadata(nextOffset));
            }
        }
        if (!offsetMap.isEmpty()) {
            kafkaConsumer.get().commitAsync(offsetMap, this::handleCommitFailure);
        }
    }

    private void handleCommitFailure(Map<TopicPartition, OffsetAndMetadata> offsets, Exception ex) {
        if (ex == null) {
            return;
        }

        logger.error("Failed to commit offsets. Retrying", ex);
        kafkaConsumer.get().commitAsync(offsets,
                (ignored, retryError) -> {
                    if (retryError != null) {
                        logger.error("Failed to commit offsets after retrying.", retryError);
                    }
                });
    }

    public boolean isEmpty() {
        for (var collector : collectors.values()) {
            if (!collector.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    public void reset() {
        collectors.clear();
    }

    public static class PartitionCollector {

        public final TopicPartition partition;

        private final Long2ObjectOpenHashMap<OffsetMd> offsets =
                new Long2ObjectOpenHashMap<>(8_096);
        private final LongHeapPriorityQueue offsetHeap = new LongHeapPriorityQueue(8_096);
        private long lastCollect = System.nanoTime();
        private long minOffset = Long.MAX_VALUE;
        private long readyOffset = Long.MIN_VALUE;

        public PartitionCollector(TopicPartition partition) {
            this.partition = partition;
        }

        public boolean isEmpty() {
            return this.offsetHeap.isEmpty();
        }

        public void register(KafkaFrame frame) {
            OffsetMd ack = frame.getAck();
            if (this.offsetHeap.isEmpty()) {
                this.minOffset = ack.offset;
            }
            this.offsets.put(ack.offset, ack);
            this.offsetHeap.enqueue(ack.offset);
        }

        public long collect(long nowNs, CommitPolicy commitPolicy) {
            drain();
            long elapsed = nowNs - this.lastCollect;
            long diff = getDiff();
            if (commitPolicy != null && commitPolicy.canCommit(elapsed, diff)) {
                this.lastCollect = nowNs;
                this.minOffset = this.readyOffset;
                return this.readyOffset;
            }
            return -1;
        }

        public long getDiff() {
            if (this.readyOffset <= this.minOffset) {
                return 0;
            }
            return this.readyOffset - this.minOffset;
        }

        private void drain() {
            while (!this.offsetHeap.isEmpty()) {
                long minOffset = this.offsetHeap.firstLong();
                OffsetMd ack = this.offsets.get(minOffset);

                if (ack == null) {
                    this.offsetHeap.dequeueLong();
                } else if (ack.ready) {
                    this.readyOffset = Math.max(this.readyOffset, ack.offset + 1);
                    this.offsetHeap.dequeueLong();
                    this.offsets.remove(minOffset);
                } else {
                    break;
                }
            }
        }
    }

    public static class OffsetMd implements Comparable<OffsetMd> {

        public final long offset;
        public volatile boolean ready;

        public OffsetMd(long offset) {
            this.offset = offset;
        }

        @Override
        public int compareTo(OffsetMd o) {
            return Long.compare(this.offset, o.offset);
        }

        @Override
        public boolean equals(Object obj) {
            if (obj instanceof OffsetMd other) {
                return this.offset == other.offset;
            }
            return false;
        }

        @Override
        public int hashCode() {
            return Long.hashCode(this.offset);
        }
    }

    public record CommitPolicy(long minDuration, long maxDuration, long minBatch, long maxBatch) {

        public boolean canCommit(long elapsedTime, long offsetDiff) {
            if (elapsedTime >= maxDuration || offsetDiff >= maxBatch) {
                return true;
            }
            if (elapsedTime < minDuration && offsetDiff < minBatch) {
                return false;
            }

            return elapsedTime >= minDuration && offsetDiff >= minBatch;
        }
    }
}
