package io.euhedral_execution.core.generics;

import io.euhedral_execution.core.frames.AbstractFrame;

@FunctionalInterface
public interface FramePusher<T extends AbstractFrame> {
    void push(T frame);
}
