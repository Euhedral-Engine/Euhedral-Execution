package euhedral.io.interfaces;

public interface ScaffoldingOrigin {
    void addDownstream(ScaffoldingTerminal downstream);

    void request(long demand);

    void cancel();
}
