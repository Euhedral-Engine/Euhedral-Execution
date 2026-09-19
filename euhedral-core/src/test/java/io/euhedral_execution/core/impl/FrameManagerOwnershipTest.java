package io.euhedral_execution.core.impl;

import static org.assertj.core.api.Assertions.assertThat;

import io.euhedral_execution.core.flow_control.RoutingPolicy;
import io.euhedral_execution.core.frames.AbstractFrame;
import io.euhedral_execution.core.frames.CallbackFrame;
import io.euhedral_execution.core.ingest.SingleUseSource;
import io.euhedral_execution.hardware_utils.SystemInfo.CpuInfo;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class FrameManagerOwnershipTest {

    private static final long PASSWORD = 0xDEADBEEFL;

    @Test
    void singleCheckoutOwnerKeepsLiveIdentitiesExclusiveDuringConcurrentReturns() throws Exception {
        FrameManager<Object, OwnedFrame> manager = new FrameManager<>(16, PASSWORD);
        CountDownLatch bodiesEntered = new CountDownLatch(2);
        CountDownLatch releaseBodies = new CountDownLatch(1);
        CountDownLatch finalizersReturned = new CountDownLatch(2);
        AtomicBoolean returnDuringReplacement = new AtomicBoolean();
        manager.setFactory(new FrameFactory<>(
                (idHash, payload) -> new OwnedFrame(idHash, payload, manager, null), (payload, frame) -> {
                    if (returnDuringReplacement.getAndSet(false)) {
                        // Both finalizers return while this sole checkout owner holds a different frame.
                        releaseBodies.countDown();
                        await(finalizersReturned);
                    }
                    frame.payload = payload;
                }));

        OwnedFrame held = manager.getOrCreate(new Object(), PASSWORD);
        OwnedFrame first = manager.getOrCreate(new Object(), PASSWORD);
        OwnedFrame second = manager.getOrCreate(new Object(), PASSWORD);
        first.body = second.body = () -> {
            bodiesEntered.countDown();
            await(releaseBodies);
        };
        var workers = Executors.newFixedThreadPool(2);
        try {
            var firstRun = workers.submit(() -> {
                try {
                    executeManaged(first);
                } finally {
                    finalizersReturned.countDown();
                }
            });
            var secondRun = workers.submit(() -> {
                try {
                    executeManaged(second);
                } finally {
                    finalizersReturned.countDown();
                }
            });
            await(bodiesEntered);

            Set<OwnedFrame> live = identitySet();
            assertThat(live.add(held)).isTrue();
            assertThat(live.add(first)).isTrue();
            assertThat(live.add(second)).isTrue();
            assertThat(manager.get(PASSWORD)).isNull();
            OwnedFrame fresh = manager.getOrCreate(new Object(), PASSWORD);
            assertThat(live.add(fresh))
                    .as("a miss must not reissue a live identity")
                    .isTrue();

            OwnedFrame buffered = manager.getOrCreate(new Object(), PASSWORD);
            OwnedFrame replacing = manager.getOrCreate(new Object(), PASSWORD);
            assertThat(live.add(buffered)).isTrue();
            assertThat(live.add(replacing)).isTrue();
            executeManaged(buffered);
            executeManaged(replacing);
            live.remove(buffered);
            live.remove(replacing);

            returnDuringReplacement.set(true);
            OwnedFrame reused = manager.getOrCreate(new Object(), PASSWORD);
            assertThat(reused).isSameAs(replacing);
            // Observe task failures, not merely completion-latch counts.
            firstRun.get(5, TimeUnit.SECONDS);
            secondRun.get(5, TimeUnit.SECONDS);
            live.remove(first);
            live.remove(second);
            assertThat(live.add(reused)).isTrue();

            OwnedFrame fromBuffer = manager.getOrCreate(new Object(), PASSWORD);
            assertThat(fromBuffer).isSameAs(buffered);
            assertThat(live.add(fromBuffer)).isTrue();
            Set<OwnedFrame> expectedReturns = identitySet();
            expectedReturns.add(first);
            expectedReturns.add(second);
            for (int i = 0; i < 2; i++) {
                OwnedFrame returned = manager.getOrCreate(new Object(), PASSWORD);
                assertThat(expectedReturns.remove(returned))
                        .as("checkout must consume a specific returned identity exactly once")
                        .isTrue();
                assertThat(live.add(returned)).isTrue();
            }
            assertThat(expectedReturns).isEmpty();
            assertThat(manager.get(PASSWORD)).isNull();
            assertThat(live.add(manager.getOrCreate(new Object(), PASSWORD))).isTrue();
        } finally {
            releaseBodies.countDown();
            workers.shutdownNow();
            assertThat(workers.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void rawCheckoutRetainsStateWhileReplacementOwnsPayloadAndMetadataReset() {
        FrameManager<Object, OwnedFrame> manager = new FrameManager<>(8, PASSWORD);
        AtomicBoolean killSwitch = new AtomicBoolean();
        Object oldPayload = new Object();
        Object oldAttachment = new Object();
        Object newPayload = new Object();
        manager.setFactory(new FrameFactory<>(
                (idHash, payload) -> new OwnedFrame(idHash, payload, manager, killSwitch), (payload, frame) -> {
                    assertThat(frame.payload).isSameAs(oldPayload);
                    assertThat(frame.attachment).isSameAs(oldAttachment);
                    assertThat(frame.getRoutingPolicy()).isEqualTo(RoutingPolicy.CACHE_LOCAL);
                    assertThat(frame.getRoutingHash()).isEqualTo(frame.getIdHash());
                    frame.payload = payload;
                    frame.attachment = null;
                    frame.setRoutingPolicy(RoutingPolicy.ANYWHERE);
                }));

        OwnedFrame frame = manager.getOrCreate(oldPayload, PASSWORD);
        CpuInfo factoryOrigin = frame.getOrigin();
        CpuInfo previousRunOrigin = new CpuInfo(99, 98, 97);
        frame.setOrigin(previousRunOrigin);
        frame.attachment = oldAttachment;
        frame.setRoutingPolicy(RoutingPolicy.CACHE_LOCAL);
        frame.randomizeHash(17L);
        long previousRunHash = frame.getRoutingHash();
        assertThat(previousRunHash).isNotEqualTo(frame.getIdHash());
        frame.kill();
        executeManaged(frame);

        OwnedFrame raw = manager.get(PASSWORD);
        assertThat(raw).isSameAs(frame);
        assertThat(raw.payload).isSameAs(oldPayload);
        assertThat(raw.attachment).isSameAs(oldAttachment);
        assertThat(raw.getOrigin()).isSameAs(previousRunOrigin);
        assertThat(raw.getRoutingHash()).isEqualTo(previousRunHash);
        assertThat(raw.getRoutingPolicy()).isEqualTo(RoutingPolicy.CACHE_LOCAL);
        assertThat(raw.isAlive()).isFalse();
        assertThat(manager.get(PASSWORD)).isNull();
        executeManaged(raw);

        OwnedFrame replaced = manager.getOrCreate(newPayload, PASSWORD);
        assertThat(replaced).isSameAs(frame);
        assertThat(replaced.payload).isSameAs(newPayload);
        assertThat(replaced.attachment).isNull();
        assertThat(replaced.getOrigin()).isSameAs(factoryOrigin);
        assertThat(replaced.getRoutingPolicy()).isEqualTo(RoutingPolicy.ANYWHERE);
        assertThat(replaced.isAlive())
                .as("replacement must not revive a shared killed generation")
                .isFalse();
        assertThat(killSwitch).isTrue();
        assertThat(manager.get(PASSWORD)).isNull();
    }

    @Test
    void callbackSuccessTransfersExactFrameUntilResponseOwnerRecyclesIt() {
        FrameManager<Object, CallbackFrame<Object, Object>> manager = new FrameManager<>(8, PASSWORD);
        AtomicReference<CallbackFrame<Object, Object>> response = new AtomicReference<>();
        manager.setFactory(new FrameFactory<>(
                (idHash, payload) -> new CallbackFrame<>(idHash, payload, value -> value, response::set, manager, null),
                (payload, frame) -> frame.replace(payload)));
        Object firstPayload = new Object();
        CallbackFrame<Object, Object> first = manager.getOrCreate(firstPayload, PASSWORD);
        executeManaged(first);
        assertThat(response.get()).isSameAs(first);
        assertThat(response.get().getRetVal()).isSameAs(firstPayload);
        assertThat(manager.get(PASSWORD)).isNull();
        CallbackFrame<Object, Object> stillLive = manager.getOrCreate(new Object(), PASSWORD);
        assertThat(stillLive).isNotSameAs(first);

        response.getAndSet(null).recycle();
        Object nextPayload = new Object();
        CallbackFrame<Object, Object> reused = manager.getOrCreate(nextPayload, PASSWORD);
        assertThat(reused).isSameAs(first).isNotSameAs(stillLive);
        assertThat(reused.getPayload()).isSameAs(nextPayload);
        assertThat(reused.getRetVal()).isNull();
        executeManaged(reused);
        assertThat(response.get()).isSameAs(reused);
        assertThat(response.get().getRetVal()).isSameAs(nextPayload);
        assertThat(manager.get(PASSWORD)).isNull();
        response.getAndSet(null).recycle();
        assertThat(manager.get(PASSWORD)).isSameAs(reused);
        assertThat(manager.get(PASSWORD)).isNull();
    }

    @Test
    void callbackCanBeReusedBeforeItsPreviousManagedExecutionReturns() {
        FrameManager<Object, CallbackFrame<Object, Object>> manager = new FrameManager<>(8, PASSWORD);
        Object firstPayload = new Object();
        Object nextPayload = new Object();
        AtomicReference<CallbackFrame<Object, Object>> nextOwner = new AtomicReference<>();
        manager.setFactory(new FrameFactory<>(
                (idHash, payload) -> new CallbackFrame<>(
                        idHash,
                        payload,
                        value -> value,
                        response -> {
                            assertThat(response.getRetVal()).isSameAs(firstPayload);
                            response.recycle();
                            // The inline receiver runs on the original, sole checkout owner.
                            nextOwner.set(manager.getOrCreate(nextPayload, PASSWORD));
                        },
                        manager,
                        null),
                (payload, frame) -> frame.replace(payload)));
        CallbackFrame<Object, Object> first = manager.getOrCreate(firstPayload, PASSWORD);
        executeManaged(first);
        CallbackFrame<Object, Object> reused = nextOwner.get();
        assertThat(reused).isSameAs(first);
        assertThat(reused.getPayload()).isSameAs(nextPayload);
        assertThat(reused.getRetVal()).isNull();
        assertThat(manager.get(PASSWORD))
                .as("the previous generation's no-op finalizer must not recycle the new owner")
                .isNull();
    }

    @Test
    void callbackFunctionFailureRecyclesExactFrameOnceWithoutResponseTransfer() {
        FrameManager<Object, CallbackFrame<Object, Object>> manager = new FrameManager<>(8, PASSWORD);
        AtomicReference<CallbackFrame<Object, Object>> response = new AtomicReference<>();
        CallbackFrame<Object, Object> frame = new CallbackFrame<>(
                1L,
                new Object(),
                value -> {
                    throw new IllegalStateException("function failed before response transfer");
                },
                response::set,
                manager,
                null);
        executeManaged(frame);
        assertThat(response.get()).isNull();
        assertThat(manager.get(PASSWORD)).isSameAs(frame);
        assertThat(manager.get(PASSWORD)).isNull();
    }

    @Test
    void callbackReceiverFailureBeforeTakingOwnershipRecyclesExactFrameOnce() {
        FrameManager<Object, CallbackFrame<Object, Object>> manager = new FrameManager<>(8, PASSWORD);
        Object payload = new Object();
        CallbackFrame<Object, Object> frame = new CallbackFrame<>(
                1L,
                payload,
                value -> value,
                response -> {
                    throw new IllegalStateException("receiver rejected before taking ownership");
                },
                manager,
                null);
        executeManaged(frame);
        CallbackFrame<Object, Object> returned = manager.get(PASSWORD);
        assertThat(returned).isSameAs(frame);
        assertThat(returned.getRetVal()).isSameAs(payload);
        assertThat(manager.get(PASSWORD)).isNull();
    }

    @Test
    void cancelledCallbackUsesNoOpNormalFinalizerWithoutResponseHandoff() {
        FrameManager<Object, CallbackFrame<Object, Object>> manager = new FrameManager<>(8, PASSWORD);
        AtomicReference<CallbackFrame<Object, Object>> response = new AtomicReference<>();
        AtomicBoolean bodyRan = new AtomicBoolean();
        CallbackFrame<Object, Object> frame = new CallbackFrame<>(
                1L,
                new Object(),
                value -> {
                    bodyRan.set(true);
                    return value;
                },
                response::set,
                manager,
                new AtomicBoolean(true));
        executeManaged(frame);
        assertThat(bodyRan).isFalse();
        assertThat(response.get()).isNull();
        // Characterization, not an endorsement: cancellation never reaches the response owner.
        assertThat(manager.get(PASSWORD)).isNull();
    }

    private static <T> Set<T> identitySet() {
        return Collections.newSetFromMap(new IdentityHashMap<>());
    }

    private static void executeManaged(AbstractFrame frame) {
        SingleUseSource source = SingleUseSource.wrap(frame);
        new DefaultExecutor().input(source);
        source.request(1);
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while awaiting ownership gate", e);
        }
    }

    private static final class OwnedFrame extends AbstractFrame {
        private Object payload;
        private Object attachment;
        private Runnable body = () -> {};

        private OwnedFrame(
                long idHash, Object payload, FrameManager<Object, OwnedFrame> manager, AtomicBoolean killSwitch) {
            super(idHash, manager, killSwitch);
            this.payload = payload;
        }

        @Override
        public void execute() {
            body.run();
        }
    }
}
