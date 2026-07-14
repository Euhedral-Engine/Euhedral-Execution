package io.euhedral_execution.core.utils;

import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;

public class SpinWait {
    public static void await(Supplier<Boolean> condition) {
        int cycles = 0;
        while(condition.get()) {
            if(cycles++ < 128) {
                Thread.onSpinWait();
            } else if(cycles < 512) {
                Thread.yield();
            } else {
                LockSupport.parkNanos(10_000L);
            }
        }
    }
}
