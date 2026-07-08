package test_utils;

import io.euhedral_execution.core.frames.AbstractFrame;

public class TestFrame extends AbstractFrame {
    public final String value;

    public TestFrame(String value) {
        super(0, null);
        this.value = value;
    }

    @Override
    public void execute() {

    }

    @Override
    public boolean isAlive() {
        return false;
    }

    @Override
    public void kill() {

    }
}
