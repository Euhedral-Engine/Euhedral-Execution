package euhedral.io.generics;

public interface ScaffoldingSource {
    void addDownstream(ScaffoldingTerminal downstream);

    void request(long demand);

    void cancel();
}
