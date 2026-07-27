package io.euhedral_execution.spring.core.frames;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.euhedral_execution.core.impl.FrameManager;
import io.euhedral_execution.spring.core.transport.kafka.OffsetCollector.OffsetMd;
import io.euhedral_execution.spring.core.utils.KillSwitch;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;

class KafkaFrameTest {

    @Test
    void finalizationAcknowledgesOffsetAndRecyclesFrame() {
        long password = 51;
        FrameManager<ConsumerRecord<?, ?>, KafkaFrame> manager =
                new FrameManager<>(4, password);
        OffsetMd acknowledgement = new OffsetMd(7);
        KafkaFrame frame = new KafkaFrame(1, record(7), acknowledgement, manager,
                new KillSwitch());

        frame.doFinally();

        assertTrue(acknowledgement.ready);
        assertSame(frame, manager.get(password));
    }

    @Test
    void replaceUsesNewRecordAcknowledgementAndPartitionKillSwitch() {
        KillSwitch originalSwitch = new KillSwitch();
        OffsetMd originalAcknowledgement = new OffsetMd(1);
        KafkaFrame frame = new KafkaFrame(1, record(1), originalAcknowledgement, null,
                originalSwitch);
        KillSwitch replacementSwitch = new KillSwitch();
        OffsetMd replacementAcknowledgement = new OffsetMd(2);
        ConsumerRecord<byte[], byte[]> replacement = record(2);

        frame.replace(replacement, replacementAcknowledgement, replacementSwitch);
        originalSwitch.boop();

        assertSame(replacement, frame.getCRecord());
        assertSame(replacementAcknowledgement, frame.getAck());
        assertTrue(frame.isAlive());
        assertFalse(frame.isReady());

        replacementSwitch.boop();
        assertFalse(frame.isAlive());
    }

    @Test
    void killStopsAllFramesSharingThePartitionSwitch() {
        KillSwitch killSwitch = new KillSwitch();
        KafkaFrame first = new KafkaFrame(1, record(1), new OffsetMd(1), null, killSwitch);
        KafkaFrame second = new KafkaFrame(2, record(2), new OffsetMd(2), null, killSwitch);

        first.kill();

        assertFalse(first.isAlive());
        assertFalse(second.isAlive());
    }

    private static ConsumerRecord<byte[], byte[]> record(long offset) {
        return new ConsumerRecord<>("topic", 0, offset, new byte[]{1}, new byte[]{2});
    }
}
