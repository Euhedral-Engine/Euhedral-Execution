package io.euhedral_execution.spring.core.frames;

import io.euhedral_execution.core.frames.AbstractFrame;
import io.euhedral_execution.core.impl.FrameManager;
import io.euhedral_execution.spring.core.transport.kafka.OffsetCollector.Offset;
import io.euhedral_execution.spring.core.utils.KillSwitch;
import lombok.Getter;
import org.apache.kafka.clients.consumer.ConsumerRecord;

public class KafkaFrame extends AbstractFrame {
    private KillSwitch killSwitch;

    @Getter
    private ConsumerRecord<?, ?> record;

    @Getter
    private boolean ready = false;

    @Getter
    private Offset ack;

    public KafkaFrame(long idHash,
            ConsumerRecord<?, ?> record,
            Offset ack,
            FrameManager<ConsumerRecord<?, ?>, KafkaFrame> recycler,
            KillSwitch ks) {
        super(idHash, recycler, null);
        this.record = record;
        this.ack = ack;
        this.killSwitch = ks;
    }

    @Override
    public boolean isAlive() {
        return !killSwitch.isBooped();
    }

    @Override
    public void kill() {
        killSwitch.boop();
    }

    @Override
    public void doFinally() {
        ack.ready = true;
        recycle();
    }

    public void replace(ConsumerRecord<?, ?> record, Offset ack, KillSwitch killSwitch) {
        this.record = record;
        this.ack = ack;
        this.ready = false;
        this.killSwitch = killSwitch;
    }
}
