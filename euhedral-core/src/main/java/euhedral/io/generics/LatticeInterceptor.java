package euhedral.io.generics;

public interface LatticeInterceptor extends LatticeSource, LatticeReceiver {

    default void addUpstream(LatticeInterceptor upstream) {

    }

    default void addDownstream(LatticeInterceptor downstream) {

    }

    default void onComplete() {}

    default void complete() {}
}
