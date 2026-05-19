package euhedral.io.interfaces;

import euhedral.io.frames.AbstractFrame;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscription;

public interface IngestSink extends AutoCloseable {

    Publisher<AbstractFrame> getDelegate();

    interface Delegate extends Publisher<AbstractFrame>, Subscription,
            AutoCloseable {}
}
