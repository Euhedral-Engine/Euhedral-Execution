package io.euhedral_execution.core.utils;

import io.euhedral_execution.core.flow_control.UpstreamQueue;
import java.util.function.Function;

@SuppressWarnings("unused")
public class FlowThread extends Thread {

    public static Function<Runnable, FlowThread> getFactory() {
        return FlowThread::new;
    }

    public static FlowContext getContext() {
        if (Thread.currentThread() instanceof FlowThread ft) {
            if (ft.context == null) {
                ft.context = new FlowContext();
            }
            return ft.context;
        }
        return null;
    }

    private FlowContext context;

    public FlowThread() {
        super();
    }

    public FlowThread(Runnable task) {
        super(task);
    }

    public FlowThread(ThreadGroup group, Runnable task) {
        super(group, task);
    }

    public FlowThread(String name) {
        super(name);
    }

    public FlowThread(ThreadGroup group, String name) {
        super(group, name);
    }

    public FlowThread(Runnable task, String name) {
        super(task, name);
    }

    public FlowThread(ThreadGroup group, Runnable task, String name) {
        super(group, task, name);
    }

    public FlowThread(ThreadGroup group, Runnable task, String name, long stackSize) {
        super(group, task, name, stackSize);
    }

    public FlowThread(ThreadGroup group, Runnable task, String name,
            long stackSize, boolean inheritInheritableThreadLocals) {
        super(group, task, name, stackSize, inheritInheritableThreadLocals);
    }

    public static final class FlowContext {

        public UpstreamQueue upstream;

        public long satisfiedRequest = 0;
        public long originalRequest = 0;

        public long satisfiedPull = 0;
        public long originalPull = 0;

        public void clearCounters() {
            this.satisfiedRequest = 0;
            this.originalRequest = 0;
            this.satisfiedPull = 0;
            this.originalPull = 0;
        }
    }
}
