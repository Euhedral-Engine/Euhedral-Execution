package io.euhedral_execution.core.generics;

/// An interface for defining classes that data can flow through between a source and receiver
public interface LatticeInterceptor extends LatticeSource, LatticeReceiver {

    default void addUpstream(LatticeInterceptor upstream) {}

    default void addDownstream(LatticeInterceptor downstream) {}

    default void onComplete() {}

    default void complete() {}
}
