package euhedral.io.generics;

public interface RecursiveScaffolding extends ScaffoldingSource, ScaffoldingTerminal {

    default void addUpstream(RecursiveScaffolding upstream) {

    }

    default void addDownstream(RecursiveScaffolding downstream) {

    }

    default void onComplete() {}

    default void cancel() {}
}
