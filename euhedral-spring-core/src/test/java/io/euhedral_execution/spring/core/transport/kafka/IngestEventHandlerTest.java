package io.euhedral_execution.spring.core.transport.kafka;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.euhedral_execution.spring.core.transport.kafka.IngestEventHandler.Event;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class IngestEventHandlerTest {

    @Test
    void drainAppliesAllQueuedEventsAndReportsWhatChanged() {
        Set<String> topics = new HashSet<>(Set.of("old", "keep"));
        IngestEventHandler handler = new IngestEventHandler(topics);
        Map<String, Object> properties = Map.of("max.poll.records", 32);

        handler.add(Event.PARTITION_UPDATE);
        handler.add(Event.removeTopic("old"));
        handler.add(Event.addTopic("new"));
        handler.add(Event.configUpdate(properties));
        handler.drain();

        assertTrue(handler.hasPartitionUpdate());
        assertTrue(handler.hasTopicUpdate());
        assertEquals(Set.of("keep", "new"), topics);
        assertSame(properties, handler.getNewProperties());
    }

    @Test
    void emptyDrainResetsTransientChangeFlags() {
        IngestEventHandler handler = new IngestEventHandler(new HashSet<>());
        handler.add(Event.PARTITION_UPDATE);
        handler.add(Event.addTopic("topic"));
        handler.add(Event.configUpdate(Map.of("key", "value")));
        handler.drain();

        handler.drain();

        assertFalse(handler.hasPartitionUpdate());
        assertFalse(handler.hasTopicUpdate());
        assertNull(handler.getNewProperties());
    }
}
