package euhedral.io.interfaces;

public interface ScaffoldingSource {
    void addDownstream(ScaffoldingTerminal downstream);

    void request(long demand);

    void cancel();
}
