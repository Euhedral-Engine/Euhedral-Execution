package test_utils;

import io.euhedral_execution.core.frames.AbstractFrame;
import io.euhedral_execution.core.generics.LatticeReceiver;
import io.euhedral_execution.core.generics.LatticeSource;
import java.util.ArrayList;
import java.util.List;

public class TestReceiver implements LatticeReceiver {

    public final List<TestFrame> received = new ArrayList<>();

    public Throwable error;
    public boolean completed;
    public LatticeSource upstream;

    @Override
    public void push(AbstractFrame frame) {
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
    public void addUpstream(LatticeSource upstream) {
        this.upstream = upstream;
    }
}
