package io.euhedral_execution.reactor.common;

import io.euhedral_execution.core.frames.AbstractFrame;
import io.euhedral_execution.core.generics.LatticeReceiver;
import io.euhedral_execution.core.generics.LatticeSource;
import io.euhedral_execution.core.utils.CommonVarHandles;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.function.Consumer;
import java.util.function.Function;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

/// ### This class is a Reactor Subscriber compatible with Euhedral
///
/// It does not signal demand by itself and can only be used in a `subscribe()` call once.
///
/// Otherwise, it is used similarly to a normal Subscriber.
/// ```java
/// EuhedralSubscriber subscriber = new EuhedralSubscriber();
/// framedFlux.subscribe(subscriber);
///
/// EuhedralScheduler.ingest(subscriber);
/// // Or
/// controlPlane.ingest(subscriber);
/// ```
@SuppressWarnings("unused")
public final class EuhedralSubscriber implements Subscriber<AbstractFrame>, LatticeSource {

    private static final VarHandle COMPLETE = CommonVarHandles.complete(MethodHandles.lookup(),
            EuhedralSubscriber.class);
    private static final VarHandle DOWNSTREAM = CommonVarHandles.downstream(MethodHandles.lookup(),
            EuhedralSubscriber.class);
    private static final VarHandle SUBSCRIBER = CommonVarHandles.makeHandle(MethodHandles.lookup(),
            EuhedralSubscriber.class, "subscription", Subscription.class);

    private boolean complete;
    private Subscription subscription;
    private LatticeReceiver downstream;

    public boolean hasSubscription() {
        return SUBSCRIBER.getOpaque(this) != null;
    }

    @Override
    public void onSubscribe(Subscription s) {
        if (!SUBSCRIBER.compareAndSet(this, null, s)) {
            s.cancel();
        }
    }

    @Override
    public void onNext(AbstractFrame frame) {
        LatticeReceiver terminal = (LatticeReceiver) DOWNSTREAM.getOpaque(this);
        if (terminal != null) {
            terminal.push(frame);
        }
    }

    @Override
    public void onError(Throwable t) {
        LatticeReceiver terminal = (LatticeReceiver) DOWNSTREAM.getOpaque(this);
        if (terminal != null) {
            terminal.onError(t);
        }
    }

    @Override
    public void onComplete() {
        complete();
    }

    @Override
    public boolean isComplete() {
        return (boolean) COMPLETE.getOpaque(this);
    }

    @Override
    public void addDownstream(LatticeReceiver downstream) {
        if (!DOWNSTREAM.compareAndSet(this, null, downstream)) {
            downstream.onError(new IllegalStateException("Already has a downstream."));
        }
        downstream.addUpstream(this);
    }

    @Override
    public long pull(Consumer<AbstractFrame> consumer,
            Function<AbstractFrame, Boolean> stopCondition, long demand) {
        return 0;
    }

    @Override
    public void request(long demand) {
        if ((boolean) COMPLETE.getOpaque(this)) {
            return;
        }

        Subscription sub = (Subscription) SUBSCRIBER.getOpaque(this);
        if (sub != null) {
            sub.request(demand);
        }
    }

    @Override
    public void complete() {
        if (COMPLETE.compareAndSet(this, false, true)) {
            SUBSCRIBER.set(this, null);
            LatticeReceiver terminal = (LatticeReceiver) DOWNSTREAM.getOpaque(this);
            if (terminal != null) {
                terminal.onComplete();
            }
        }
    }
}
