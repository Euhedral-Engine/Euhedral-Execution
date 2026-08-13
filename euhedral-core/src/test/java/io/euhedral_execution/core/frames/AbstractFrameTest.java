package io.euhedral_execution.core.frames;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.euhedral_execution.core.flow_control.RoutingPolicy;
import io.euhedral_execution.core.generics.FramePusher;
import io.euhedral_execution.core.impl.FrameManager;
import io.euhedral_execution.hardware_utils.SystemInfo.CpuInfo;
import io.euhedral_execution.hashing.HasherApi;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.Getter;
import org.junit.jupiter.api.Test;

@SuppressWarnings("rawtypes")
class AbstractFrameTest {

    @Test
    void singleArgConstructorInitializesDefaultState() {
        long idHash = 0x12345678L;
        TestFrame frame = new TestFrame(idHash);

        assertThat(frame.getIdHash()).isEqualTo(idHash);
        assertThat(frame.getRoutingHash()).isEqualTo(idHash);
        assertThat(frame.isOrdered()).isTrue();
        assertThat(frame.isAlive()).isTrue();
        assertThat(frame.getRoutingPolicy()).isEqualTo(RoutingPolicy.ANYWHERE);
        assertThat(frame.getOrigin()).isNull();
    }

    @Test
    void threeArgConstructorInitializesRecyclerAndKillSwitch() {
        long idHash = 0xABCDEFL;
        FrameManager<Void, TestFrame> recycler = new FrameManager<>(0x9999L);
        AtomicBoolean killSwitch = new AtomicBoolean(false);

        TestFrame frame = new TestFrame(idHash, recycler, killSwitch);

        assertThat(frame.getIdHash()).isEqualTo(idHash);
        assertThat(frame.getRoutingHash()).isEqualTo(idHash);
        assertThat(frame.isOrdered()).isTrue();
        assertThat(frame.isAlive()).isTrue();

        killSwitch.set(true);
        assertThat(frame.isAlive()).isFalse();
    }

    @Test
    void fourArgConstructorInitializesAllFields() {
        long idHash = 0xDEADBEEFL;
        TestFramePusher<AbstractFrame> pusher = new TestFramePusher<>();
        FrameManager<Void, TestFrame> recycler = new FrameManager<>(0x8888L);
        AtomicBoolean killSwitch = new AtomicBoolean(false);

        TestFrame frame = new TestFrame(idHash, pusher, recycler, killSwitch);
        TestFrame response = new TestFrame(0x111L);

        frame.testGiveToReceiver(response);

        assertThat(pusher.getPushedFrame()).isSameAs(response);
        assertThat(frame.getIdHash()).isEqualTo(idHash);
        assertThat(frame.getRoutingHash()).isEqualTo(idHash);
        assertThat(frame.isAlive()).isTrue();
    }

    @Test
    void randomizeHashMutatesRoutingHashAndChangesOrderedStatus() {
        long idHash = 0x1000L;
        TestFrame frame = new TestFrame(idHash);

        long seed = 0x42L;
        frame.randomizeHash(seed);

        long expectedRoutingHash = idHash ^ HasherApi.mix(seed);
        assertThat(frame.getRoutingHash()).isEqualTo(expectedRoutingHash);
        assertThat(frame.isOrdered()).isFalse();

        // Check determinism with same seed
        TestFrame frame2 = new TestFrame(idHash);
        frame2.randomizeHash(seed);
        assertThat(frame2.getRoutingHash()).isEqualTo(expectedRoutingHash);
    }

    @Test
    void resetHashRestoresRoutingHashToIdHash() {
        long idHash = 0x2000L;
        TestFrame frame = new TestFrame(idHash);

        frame.randomizeHash(99L);
        assertThat(frame.isOrdered()).isFalse();

        frame.resetHash();
        assertThat(frame.getRoutingHash()).isEqualTo(idHash);
        assertThat(frame.isOrdered()).isTrue();
    }

    @Test
    void isOrderedReturnsTrueOnlyWhenIdHashEqualsRoutingHash() {
        TestFrame frame = new TestFrame(0x3000L);
        assertThat(frame.isOrdered()).isTrue();

        frame.randomizeHash(123L);
        assertThat(frame.isOrdered()).isFalse();

        frame.resetHash();
        assertThat(frame.isOrdered()).isTrue();
    }

    @Test
    void routingPolicyCanBeConfiguredAndDefaultsToAnywhere() {
        TestFrame frame = new TestFrame(0x4000L);
        assertThat(frame.getRoutingPolicy()).isEqualTo(RoutingPolicy.ANYWHERE);

        frame.setRoutingPolicy(RoutingPolicy.SOCKET_LOCAL);
        assertThat(frame.getRoutingPolicy()).isEqualTo(RoutingPolicy.SOCKET_LOCAL);

        frame.setRoutingPolicy(RoutingPolicy.CACHE_LOCAL);
        assertThat(frame.getRoutingPolicy()).isEqualTo(RoutingPolicy.CACHE_LOCAL);

        frame.setRoutingPolicy(null);
        assertThat(frame.getRoutingPolicy()).isEqualTo(RoutingPolicy.ANYWHERE);
    }

    @Test
    void originCanBeSetAndRetrieved() {
        TestFrame frame = new TestFrame(0x5000L);
        assertThat(frame.getOrigin()).isNull();

        CpuInfo cpuInfo = new CpuInfo(1, 0, 0);
        frame.setOrigin(cpuInfo);

        assertThat(frame.getOrigin()).isSameAs(cpuInfo);
    }

    @Test
    void isAliveWithNullKillSwitchReturnsTrueAndKillIsNoOp() {
        TestFrame frame = new TestFrame(0x6000L);
        assertThat(frame.isAlive()).isTrue();

        frame.kill();
        assertThat(frame.isAlive()).isTrue();
    }

    @Test
    void isAliveAndKillWithAtomicBooleanKillSwitch() {
        AtomicBoolean killSwitch = new AtomicBoolean(false);
        TestFrame frame = new TestFrame(0x7000L, null, killSwitch);

        assertThat(frame.isAlive()).isTrue();

        frame.kill();
        assertThat(killSwitch.get()).isTrue();
        assertThat(frame.isAlive()).isFalse();
    }

    @Test
    void executeRunsDefaultOrOverriddenImplementation() {
        TestFrame frame = new TestFrame(0x8000L);
        assertThat(frame.isExecuted()).isFalse();

        frame.execute();
        assertThat(frame.isExecuted()).isTrue();

        // Test default execute() from AbstractFrame base class directly
        AbstractFrame defaultFrame = new AbstractFrame(0x8001L) {};
        defaultFrame.execute(); // Should not throw
    }

    @Test
    void doFinallyRecyclesFrameWhenRecyclerIsPresent() {
        FrameManager<Void, TestFrame> recycler = new FrameManager<>(0x7777L);
        TestFrame frame = new TestFrame(0x9000L, recycler, null);

        assertThat(recycler.getRecycleQueue().sizeLong()).isZero();

        frame.doFinally();

        assertThat(recycler.getRecycleQueue().sizeLong()).isOne();
    }

    @Test
    void doFinallyDoesNotThrowWhenRecyclerIsNull() {
        TestFrame frame = new TestFrame(0x9001L);
        frame.doFinally();
    }

    @Test
    void doFinallyWithErrorRecyclesFrameWhenRecyclerIsPresent() {
        FrameManager<Void, TestFrame> recycler = new FrameManager<>(0x6666L);
        TestFrame frame = new TestFrame(0xA000L, recycler, null);

        assertThat(recycler.getRecycleQueue().sizeLong()).isZero();

        frame.doFinallyWithError(new RuntimeException("Execution failed"));

        assertThat(recycler.getRecycleQueue().sizeLong()).isOne();
    }

    @Test
    void doFinallyWithErrorAcceptsNullThrowable() {
        FrameManager<Void, TestFrame> recycler = new FrameManager<>(0x5555L);
        TestFrame frame = new TestFrame(0xA001L, recycler, null);

        frame.doFinallyWithError(null);

        assertThat(recycler.getRecycleQueue().sizeLong()).isOne();
    }

    @Test
    void recycleDirectCallEnqueuesToFrameManager() {
        FrameManager<Void, TestFrame> recycler = new FrameManager<>(0x4444L);
        TestFrame frame = new TestFrame(0xB000L, recycler, null);

        frame.recycle();

        assertThat(recycler.getRecycleQueue().sizeLong()).isOne();
    }

    @Test
    void giveToReceiverDoesNothingWhenPusherIsNull() {
        TestFrame frame = new TestFrame(0xC000L);
        TestFrame response = new TestFrame(0xC001L);

        frame.testGiveToReceiver(response); // Should not throw NPE
    }

    @Test
    void throwCancelSignalThrowsStaticCancelSignalInstance() {
        TestFrame frame = new TestFrame(0xD000L);

        assertThatThrownBy(frame::throwCancelSignal).isSameAs(AbstractFrame.CANCEL_SIGNAL);
    }

    @Test
    void cancelSignalProperties() {
        AbstractFrame.CancelSignal signal = AbstractFrame.CANCEL_SIGNAL;

        assertThat(signal).isInstanceOf(RuntimeException.class);
        assertThat(signal.getMessage()).isNull();
        assertThat(signal.getCause()).isNull();
        assertThat(signal.getStackTrace()).isEmpty();
    }

    @Getter
    static class TestFrame extends AbstractFrame {
        private boolean executed = false;

        TestFrame(long idHash) {
            super(idHash);
        }

        TestFrame(long idHash, FrameManager recycler, AtomicBoolean killSwitch) {
            super(idHash, recycler, killSwitch);
        }

        TestFrame(long idHash, FramePusher responseReceiver, FrameManager recycler, AtomicBoolean killSwitch) {
            super(idHash, responseReceiver, recycler, killSwitch);
        }

        @Override
        public void execute() {
            this.executed = true;
        }

        public <T extends AbstractFrame> void testGiveToReceiver(T obj) {
            giveToReceiver(obj);
        }
    }

    @Getter
    static class TestFramePusher<T extends AbstractFrame> implements FramePusher<T> {
        private T pushedFrame;

        @Override
        public void push(T frame) {
            this.pushedFrame = frame;
        }
    }
}
