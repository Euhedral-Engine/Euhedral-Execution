package test_utils;

import euhedral.io.frames.AbstractFrame;
import euhedral.io.generics.LaticeSource;
import euhedral.io.generics.LatticeReceiver;
import java.util.ArrayList;
import java.util.List;

public class TestReceiver implements LatticeReceiver {

    public final List<TestFrame> received = new ArrayList<>();

    public Throwable error;
    public boolean completed;
    public LaticeSource upstream;

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
    public void addUpstream(LaticeSource upstream) {
        this.upstream = upstream;
    }
}
