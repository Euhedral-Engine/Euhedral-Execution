package io.euhedral_execution.data_structures.atomics.padding;

@SuppressWarnings("unused")
public abstract class PaddedReference<T> extends ReferenceHolder<T> {

    private long p00, p01, p02, p03, p04, p05, p06, p07;
    private long p08, p09, p10, p11, p12, p13, p14, p15;
}
