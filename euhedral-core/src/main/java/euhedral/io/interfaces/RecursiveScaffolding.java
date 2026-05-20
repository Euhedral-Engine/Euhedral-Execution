package euhedral.io.interfaces;

public interface RecursiveScaffolding extends ScaffoldingOrigin, ScaffoldingTerminal {

    default void addUpstream(RecursiveScaffolding upstream) {

    }

    default void addDownstream(RecursiveScaffolding downstream) {

    }

    default void onComplete() {}

    default void cancel() {}
}
