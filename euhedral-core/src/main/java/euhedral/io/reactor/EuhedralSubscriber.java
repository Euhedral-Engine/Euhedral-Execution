package euhedral.io.reactor;

import euhedral.io.frames.AbstractFrame;
import euhedral.io.interfaces.ScaffoldingSource;
import euhedral.io.interfaces.ScaffoldingTerminal;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

public final class EuhedralSubscriber implements Subscriber<AbstractFrame>, ScaffoldingSource {
    private static final VarHandle COMPLETE;
    private static final VarHandle SUBSCRIBER;
    private static final VarHandle TERMINAL;

    static {
        try {
            COMPLETE = MethodHandles.lookup().findVarHandle(EuhedralSubscriber.class, "complete", boolean.class);
            SUBSCRIBER = MethodHandles.lookup().findVarHandle(EuhedralSubscriber.class, "subscription", Subscription.class);
            TERMINAL = MethodHandles.lookup().findVarHandle(EuhedralSubscriber.class, "terminal", ScaffoldingTerminal.class);
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private boolean complete;
    private Subscription subscription;
    private ScaffoldingTerminal terminal;

    @Override
    public void onSubscribe(Subscription s) {
        if(!SUBSCRIBER.compareAndSet(this, null, s)) {
            s.cancel();
        }
    }

    @Override
    public void onNext(AbstractFrame frame) {
        ScaffoldingTerminal terminal = (ScaffoldingTerminal) TERMINAL.getOpaque(this);
        if(terminal != null) {
            terminal.onNext(frame);
        }
    }

    @Override
    public void onError(Throwable t) {
        ScaffoldingTerminal terminal = (ScaffoldingTerminal) TERMINAL.getOpaque(this);
        if(terminal != null) {
            terminal.onError(t);
        }
    }

    @Override
    public void onComplete() {
        ScaffoldingTerminal terminal = (ScaffoldingTerminal) TERMINAL.getOpaque(this);
        if(terminal != null) {
            terminal.onComplete();
        }
    }

    @Override
    public void addDownstream(ScaffoldingTerminal downstream) {
        if(!TERMINAL.compareAndSet(this, null, downstream)) {
            downstream.onError(new IllegalStateException("Already has a downstream."));
        }
        downstream.addUpstream(this);
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
    public void cancel() {
        COMPLETE.setRelease(this, true);
        Subscription sub = (Subscription) SUBSCRIBER.getAndSet(this, null);
        if(sub != null) {
            sub.cancel();
        }
    }
}
