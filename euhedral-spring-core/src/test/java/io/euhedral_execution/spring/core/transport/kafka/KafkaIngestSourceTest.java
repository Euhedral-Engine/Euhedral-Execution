package io.euhedral_execution.spring.core.transport.kafka;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;

class KafkaIngestSourceTest {

    @Test
    void partitionHashIsStableAcrossMetadataRepresentations() {
        TopicPartition partition = new TopicPartition("topic", 3);
        ConsumerRecord<byte[], byte[]> record =
                new ConsumerRecord<>("topic", 3, 7, new byte[]{1}, new byte[]{2});

        assertEquals(KafkaIngestSource.getPartitionHash(partition),
                KafkaIngestSource.getPartitionHash(record));
        assertNotEquals(KafkaIngestSource.getPartitionHash(partition),
                KafkaIngestSource.getPartitionHash(new TopicPartition("topic", 4)));
    }
}
