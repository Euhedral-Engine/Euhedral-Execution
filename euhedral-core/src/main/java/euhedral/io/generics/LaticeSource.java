package euhedral.io.generics;

public interface LaticeSource {
    void addDownstream(LatticeReceiver downstream);

    void request(long demand);

    void complete();
}
