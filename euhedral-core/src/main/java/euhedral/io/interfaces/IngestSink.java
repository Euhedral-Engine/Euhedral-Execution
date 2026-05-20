package euhedral.io.interfaces;

public interface IngestSink extends AutoCloseable {

    ScaffoldingOrigin getDelegate();

    interface Delegate extends ScaffoldingOrigin, AutoCloseable {}
}
