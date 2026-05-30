package euhedral.io.generics;

import euhedral.io.frames.AbstractFrame;

public interface LatticeReceiver {
    void addUpstream(LaticeSource upstream);

    void onNext(AbstractFrame frame);

    void onComplete();

    void onError(Throwable e);
}
