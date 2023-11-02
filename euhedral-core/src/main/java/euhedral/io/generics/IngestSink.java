package euhedral.io.generics;

public interface IngestSink extends AutoCloseable {

    ScaffoldingSource getDelegate();

    interface Delegate extends ScaffoldingSource, AutoCloseable {}
}
