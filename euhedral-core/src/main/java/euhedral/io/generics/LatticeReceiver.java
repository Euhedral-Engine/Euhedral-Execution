package euhedral.io.generics;

import euhedral.io.frames.AbstractFrame;

/// An interface for defining where data flows to
public interface LatticeReceiver extends LatticeTerminal {
    void push(AbstractFrame frame);

    void onComplete();

    void onError(Throwable e);
}
