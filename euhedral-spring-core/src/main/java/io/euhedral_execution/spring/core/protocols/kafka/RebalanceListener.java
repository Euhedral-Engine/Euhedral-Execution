package io.euhedral_execution.spring.core.protocols.kafka;

import io.euhedral_execution.spring.core.protocols.kafka.IngestEventHandler.Event;
import java.util.Collection;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.kafka.clients.consumer.ConsumerRebalanceListener;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;

public class RebalanceListener implements ConsumerRebalanceListener {

    private final AtomicReference<KafkaConsumer<?, ?>> actualConsumer;
    private final KafkaConsumer<?, ?> consumer;
    private final IngestEventHandler eventHandler;

    public RebalanceListener(AtomicReference<KafkaConsumer<?, ?>> actualConsumer, KafkaConsumer<?, ?> consumer, IngestEventHandler eventHandler) {
        this.actualConsumer = actualConsumer;
        this.consumer = consumer;
        this.eventHandler = eventHandler;
    }

    @Override
    public void onPartitionsRevoked(Collection<TopicPartition> collection) {
        if (this.actualConsumer.getAcquire() != this.consumer) {
            return;
        }
        eventHandler.add(Event.PARTITION_UPDATE);
    }

    @Override
    public void onPartitionsAssigned(Collection<TopicPartition> collection) {
        if (this.actualConsumer.getAcquire() != this.consumer) {
            return;
        }
        eventHandler.add(Event.PARTITION_UPDATE);
    }
}
