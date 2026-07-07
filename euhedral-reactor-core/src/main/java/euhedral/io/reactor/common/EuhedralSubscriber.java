package euhedral.io.reactor.common;

import euhedral.io.frames.AbstractFrame;
import euhedral.io.generics.LatticeReceiver;
import euhedral.io.generics.LatticeSource;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.function.Consumer;
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
public final class EuhedralSubscriber implements Subscriber<AbstractFrame>, LatticeSource {
    private static final VarHandle COMPLETE;
    private static final VarHandle SUBSCRIBER;
    private static final VarHandle TERMINAL;

    static {
        try {
            COMPLETE = MethodHandles.lookup().findVarHandle(EuhedralSubscriber.class, "complete", boolean.class);
            SUBSCRIBER = MethodHandles.lookup().findVarHandle(EuhedralSubscriber.class, "subscription", Subscription.class);
            TERMINAL = MethodHandles.lookup().findVarHandle(EuhedralSubscriber.class, "terminal", LatticeReceiver.class);
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private boolean complete;
    private Subscription subscription;
    private LatticeReceiver terminal;

    public boolean hasSubscription() {
        return SUBSCRIBER.getOpaque(this) != null;
    }

    @Override
    public void onSubscribe(Subscription s) {
        if(!SUBSCRIBER.compareAndSet(this, null, s)) {
            s.cancel();
        }
    }

    @Override
    public void onNext(AbstractFrame frame) {
        LatticeReceiver terminal = (LatticeReceiver) TERMINAL.getOpaque(this);
        if(terminal != null) {
            terminal.push(frame);
        }
    }

    @Override
    public void onError(Throwable t) {
        LatticeReceiver terminal = (LatticeReceiver) TERMINAL.getOpaque(this);
        if(terminal != null) {
            terminal.onError(t);
        }
    }

    @Override
    public void onComplete() {
        if(COMPLETE.compareAndSet(this, false, true)) {
            SUBSCRIBER.set(this, null);
            LatticeReceiver terminal = (LatticeReceiver) TERMINAL.getOpaque(this);
            if(terminal != null) {
                terminal.onComplete();
            }
        }
    }

    @Override
    public void addDownstream(LatticeReceiver downstream) {
        if(!TERMINAL.compareAndSet(this, null, downstream)) {
            downstream.onError(new IllegalStateException("Already has a downstream."));
        }
        downstream.addUpstream(this);
    }

    @Override
    public long pull(Consumer<AbstractFrame> consumer, long demand) {
        return 0;
    }

    @Override
    public void request(long demand) {
        if((boolean)  COMPLETE.getOpaque(this)) {
            return;
        }

        Subscription sub = (Subscription) SUBSCRIBER.getOpaque(this);
        if(sub != null) {
            sub.request(demand);
        }
    }

    @Override
    public void complete() {
        COMPLETE.setRelease(this, true);
        Subscription sub = (Subscription) SUBSCRIBER.getAndSet(this, null);
        if(sub != null) {
            sub.cancel();
        }
    }
}
