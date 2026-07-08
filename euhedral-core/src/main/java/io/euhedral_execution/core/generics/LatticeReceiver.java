package io.euhedral_execution.core.generics;

import io.euhedral_execution.core.frames.AbstractFrame;

/// An interface for defining where data flows to
public interface LatticeReceiver extends LatticeTerminal {
    void push(AbstractFrame frame);

    void onComplete();

    void onError(Throwable e);
}
