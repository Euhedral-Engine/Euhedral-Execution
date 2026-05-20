package euhedral.io.interfaces;

import euhedral.io.frames.AbstractFrame;

public interface ScaffoldingTerminal {
    void addUpstream(ScaffoldingOrigin upstream);

    void onNext(AbstractFrame frame);

    void onComplete();

    void onError(Throwable e);
}
