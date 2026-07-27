package io.euhedral_execution.spring.core.transport.kafka;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;

class RebalanceListenerTest {

    @Test
    void activeConsumerQueuesPartitionUpdatesForAssignmentsAndRevocations() {
        MockConsumer<Object, Object> consumer = new MockConsumer<>("earliest");
        AtomicReference<Consumer<?, ?>> current = new AtomicReference<>(consumer);
        IngestEventHandler events = new IngestEventHandler(new HashSet<>());
        RebalanceListener listener = new RebalanceListener(current, consumer, events);
        List<TopicPartition> partitions = List.of(new TopicPartition("topic", 0));

        listener.onPartitionsAssigned(partitions);
        events.drain();
        assertTrue(events.hasPartitionUpdate());

        listener.onPartitionsRevoked(partitions);
        events.drain();
        assertTrue(events.hasPartitionUpdate());
    }

    @Test
    void staleConsumerCannotQueuePartitionUpdates() {
        MockConsumer<Object, Object> stale = new MockConsumer<>("earliest");
        MockConsumer<Object, Object> currentConsumer = new MockConsumer<>("earliest");
        AtomicReference<Consumer<?, ?>> current = new AtomicReference<>(currentConsumer);
        IngestEventHandler events = new IngestEventHandler(new HashSet<>());
        RebalanceListener listener = new RebalanceListener(current, stale, events);

        listener.onPartitionsAssigned(List.of(new TopicPartition("topic", 0)));
        events.drain();

        assertFalse(events.hasPartitionUpdate());
    }
}
