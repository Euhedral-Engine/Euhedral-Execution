package io.euhedral_execution.spring.core.transport.kafka;

import io.euhedral_execution.spring.core.transport.kafka.IngestEventHandler.Event;
import java.util.Collection;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRebalanceListener;
import org.apache.kafka.common.TopicPartition;

public class RebalanceListener implements ConsumerRebalanceListener {

    private final AtomicReference<? extends Consumer<?, ?>> actualConsumer;
    private final Consumer<?, ?> consumer;
    private final IngestEventHandler eventHandler;

    public RebalanceListener(AtomicReference<? extends Consumer<?, ?>> actualConsumer,
            Consumer<?, ?> consumer, IngestEventHandler eventHandler) {
        this.actualConsumer = actualConsumer;
        this.consumer = consumer;
        this.eventHandler = eventHandler;
    }

    @Override
    public void onPartitionsRevoked(Collection<TopicPartition> collection) {
        sendUpdate();
    }

    @Override
    public void onPartitionsAssigned(Collection<TopicPartition> collection) {
        sendUpdate();
    }

    private void sendUpdate() {
        if (this.actualConsumer.getAcquire() != this.consumer) {
            return;
        }
        this.eventHandler.add(Event.PARTITION_UPDATE);
    }
}
