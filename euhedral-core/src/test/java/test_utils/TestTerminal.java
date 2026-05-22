package test_utils;

import euhedral.io.frames.AbstractFrame;
import euhedral.io.generics.ScaffoldingSource;
import euhedral.io.generics.ScaffoldingTerminal;
import java.util.ArrayList;
import java.util.List;

public class TestTerminal implements ScaffoldingTerminal {

    public final List<TestFrame> received = new ArrayList<>();

    public Throwable error;
    public boolean completed;
    public ScaffoldingSource upstream;

    @Override
    public void onNext(AbstractFrame frame) {
        received.add((TestFrame) frame);
    }

    @Override
    public void onError(Throwable throwable) {
        this.error = throwable;
    }

    @Override
    public void onComplete() {
        this.completed = true;
    }

    @Override
    public void addUpstream(ScaffoldingSource upstream) {
        this.upstream = upstream;
    }
}
