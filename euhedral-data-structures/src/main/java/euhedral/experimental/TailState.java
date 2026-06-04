package euhedral.experimental;

abstract sealed class TailState permits MpTailState, SpTailState {

    abstract long getTail();
}
