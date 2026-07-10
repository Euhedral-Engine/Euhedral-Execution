package io.euhedral_execution.spring.core.transport.kafka;

import io.euhedral_execution.data_structures.queues.MpscQueue;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import lombok.Getter;

@SuppressWarnings("unused")
public class IngestEventHandler {

    private final MpscQueue<Event> eventQueue = new MpscQueue<>(256);
    private final Set<String> topics;

    boolean partitionUpdate = false;
    boolean topicUpdate = false;
    @Getter
    Map<String, Object> newProperties = null;

    public IngestEventHandler(Set<String> topics) {
        this.topics = topics;
    }

    public boolean hasPartitionUpdate() {
        return this.partitionUpdate;
    }

    public boolean hasTopicUpdate() {
        return this.topicUpdate;
    }

    public void add(Event event) {
        this.eventQueue.offer(event);
    }

    public void drain() {
        reset();
        this.eventQueue.drain(this::drainInternal);
    }

    private void reset() {
        this.partitionUpdate = false;
        this.topicUpdate = false;
        this.newProperties = null;
    }

    private void drainInternal(Event event) {
        Set<String> topics = this.topics;
        switch (event.type) {
            case PARTITION_UPDATE -> this.partitionUpdate = true;
            case CONFIG_UPDATE -> this.newProperties = event.properties;
            case ADD_TOPIC -> {
                this.topicUpdate = true;
                topics.add(event.value);
            }
            case REMOVE_TOPIC -> {
                this.topicUpdate = true;
                topics.remove(event.value);
            }
        }
    }

    public enum EventType {
        PARTITION_UPDATE,
        CONFIG_UPDATE,
        ADD_TOPIC,
        REMOVE_TOPIC,
    }

    public record Event(EventType type, String value, Collection<String> values,
                        Map<String, Object> properties) {

        public static final Event PARTITION_UPDATE = new Event(EventType.PARTITION_UPDATE,
                null, null, null);

        public static Event addTopic(String value) {
            return new Event(EventType.ADD_TOPIC, value, null, null);
        }

        public static Event removeTopic(String value) {
            return new Event(EventType.REMOVE_TOPIC, value, null, null);
        }

        public static Event configUpdate(Map<String, Object> properties) {
            return new Event(EventType.CONFIG_UPDATE, null, null, properties);
        }
    }
}
