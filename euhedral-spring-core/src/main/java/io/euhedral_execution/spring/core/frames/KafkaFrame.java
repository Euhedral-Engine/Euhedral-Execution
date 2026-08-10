package io.euhedral_execution.spring.core.frames;

import io.euhedral_execution.core.frames.AbstractFrame;
import io.euhedral_execution.core.impl.FrameManager;
import io.euhedral_execution.spring.core.transport.kafka.OffsetCollector;
import io.euhedral_execution.spring.core.transport.kafka.OffsetCollector.OffsetMd;
import io.euhedral_execution.spring.core.utils.KillSwitch;
import lombok.Getter;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

public class KafkaFrame extends AbstractFrame {

    private KillSwitch partitionKillSwitch;

    @Getter
    private ConsumerRecord<?, ?> cRecord;

    @Getter
    private boolean ready = false;

    @Getter
    private OffsetMd ack;

    public KafkaFrame(
            long idHash,
            ConsumerRecord<?, ?> cRecord,
            OffsetCollector.OffsetMd ack,
            @Nullable FrameManager<ConsumerRecord<?, ?>, KafkaFrame> recycler,
            @NonNull KillSwitch partitionKillSwitch) {
        super(idHash, recycler, null);
        this.cRecord = cRecord;
        this.ack = ack;
        this.partitionKillSwitch = partitionKillSwitch;
    }

    @Override
    public boolean isAlive() {
        return !partitionKillSwitch.isBooped();
    }

    @Override
    public void kill() {
        partitionKillSwitch.boop();
    }

    @Override
    public void doFinally() {
        ack.ready = true;
        recycle();
    }

    public void replace(
            ConsumerRecord<?, ?> cRecord, OffsetCollector.OffsetMd ack, @NonNull KillSwitch partitionKillSwitch) {
        this.cRecord = cRecord;
        this.ack = ack;
        this.ready = false;
        this.partitionKillSwitch = partitionKillSwitch;
    }
}
