package euhedral.io.generics;

import euhedral.io.frames.AbstractFrame;

public interface ScaffoldingTerminal {
    void addUpstream(ScaffoldingSource upstream);

    void onNext(AbstractFrame frame);

    void onComplete();

    void onError(Throwable e);
}
