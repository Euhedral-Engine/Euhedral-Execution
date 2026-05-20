package euhedral.io.interfaces;

public interface IngestSink extends AutoCloseable {

    ScaffoldingSource getDelegate();

    interface Delegate extends ScaffoldingSource, AutoCloseable {}
}
