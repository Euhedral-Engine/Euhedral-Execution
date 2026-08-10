package io.euhedral_execution.spring.core.transport.kafka;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.euhedral_execution.spring.core.frames.KafkaFrame;
import io.euhedral_execution.spring.core.transport.kafka.OffsetCollector.CommitPolicy;
import io.euhedral_execution.spring.core.transport.kafka.OffsetCollector.OffsetMd;
import io.euhedral_execution.spring.core.transport.kafka.OffsetCollector.PartitionCollector;
import io.euhedral_execution.spring.core.utils.KillSwitch;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.consumer.OffsetCommitCallback;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;

class OffsetCollectorTest {

    private static OffsetCollector collector(TrackingConsumer consumer, long password) {
        AtomicReference<Consumer<?, ?>> current = new AtomicReference<>(consumer);
        return new OffsetCollector(current, new CommitPolicy(0, Long.MAX_VALUE, 1, Long.MAX_VALUE), password);
    }

    private static KafkaFrame frame(TopicPartition partition, long offset) {
        ConsumerRecord<byte[], byte[]> record =
                new ConsumerRecord<>(partition.topic(), partition.partition(), offset, new byte[] {1}, new byte[] {2});
        return new KafkaFrame(1, record, new OffsetMd(offset), null, new KillSwitch());
    }

    @Test
    void commitPolicyHonorsMinimumsAndEitherMaximum() {
        CommitPolicy policy = new CommitPolicy(10, 100, 3, 20);

        assertFalse(policy.canCommit(9, 2));
        assertFalse(policy.canCommit(10, 2));
        assertFalse(policy.canCommit(9, 3));
        assertTrue(policy.canCommit(10, 3));
        assertTrue(policy.canCommit(100, 0));
        assertTrue(policy.canCommit(0, 20));
    }

    @Test
    void partitionCollectorWaitsForTheLowestOutstandingOffset() {
        TopicPartition partition = new TopicPartition("topic", 2);
        PartitionCollector collector = new PartitionCollector(partition);
        KafkaFrame lower = frame(partition, 10);
        KafkaFrame higher = frame(partition, 11);
        collector.register(lower);
        collector.register(higher);
        higher.doFinally();
        CommitPolicy policy = new CommitPolicy(0, Long.MAX_VALUE, 1, Long.MAX_VALUE);

        assertEquals(-1, collector.collect(System.nanoTime(), policy));

        lower.doFinally();
        assertEquals(12, collector.collect(System.nanoTime(), policy));
        assertTrue(collector.isEmpty());
    }

    @Test
    void firstRegisteredFrameCommitsOnceAfterSuccessfulAcknowledgement() {
        TrackingConsumer consumer = new TrackingConsumer(null);
        long password = 37;
        OffsetCollector collector = collector(consumer, password);
        TopicPartition partition = new TopicPartition("topic", 0);
        KafkaFrame frame = frame(partition, 4);

        collector.registerFrame(partition, frame, password);
        frame.doFinally();
        collector.drain(System.nanoTime(), password);

        assertEquals(1, consumer.commitCalls);
        assertEquals(5, consumer.offsets.get(partition).offset());
    }

    @Test
    void failedCommitIsRetriedExactlyOnce() {
        TrackingConsumer consumer = new TrackingConsumer(new RuntimeException("first failure"));
        long password = 41;
        OffsetCollector collector = collector(consumer, password);
        TopicPartition partition = new TopicPartition("topic", 1);
        KafkaFrame frame = frame(partition, 8);

        collector.registerFrame(partition, frame, password);
        frame.doFinally();
        collector.drain(System.nanoTime(), password);

        assertEquals(2, consumer.commitCalls);
        assertEquals(9, consumer.offsets.get(partition).offset());
    }

    @Test
    void wrongPasswordCannotRegisterOrDrainFrames() {
        TrackingConsumer consumer = new TrackingConsumer(null);
        long password = 43;
        OffsetCollector collector = collector(consumer, password);
        TopicPartition partition = new TopicPartition("topic", 1);
        KafkaFrame frame = frame(partition, 8);

        collector.registerFrame(partition, frame, password + 1);
        frame.doFinally();
        collector.drain(System.nanoTime(), password + 1);

        assertEquals(0, consumer.commitCalls);
        assertTrue(collector.isEmpty());
    }

    private static final class TrackingConsumer extends MockConsumer<Object, Object> {

        final Exception firstFailure;
        int commitCalls;
        Map<TopicPartition, OffsetAndMetadata> offsets = Map.of();

        TrackingConsumer(Exception firstFailure) {
            super("earliest");
            this.firstFailure = firstFailure;
        }

        @Override
        public synchronized void commitAsync(
                Map<TopicPartition, OffsetAndMetadata> offsets, OffsetCommitCallback callback) {
            this.commitCalls++;
            this.offsets = Map.copyOf(offsets);
            Exception failure = this.commitCalls == 1 ? this.firstFailure : null;
            callback.onComplete(offsets, failure);
        }
    }
}
