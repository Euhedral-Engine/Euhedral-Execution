package io.euhedral_execution.training.learning.network_operations;

import org.tensorflow.Graph;
import org.tensorflow.Operand;
import org.tensorflow.framework.op.NnOps;
import org.tensorflow.framework.op.nn.GELU;
import org.tensorflow.ndarray.Shape;
import org.tensorflow.op.Ops;
import org.tensorflow.op.core.Placeholder;
import org.tensorflow.types.TFloat32;

public class TensorFlowNetwork implements AutoCloseable {

    private static final String INPUT_LAYER_NAME = "INPUT";

    private static Placeholder<TFloat32> inputLayer(int featureWidth, Ops tf) {
        return tf.withName(INPUT_LAYER_NAME)
                .placeholder(TFloat32.class, Placeholder.shape(Shape.of(featureWidth)));
    }

    private final Graph graph;

    public TensorFlowNetwork(int featureWidth) {
        this.graph = new Graph();

        Ops tf = Ops.create(graph);

        Placeholder<TFloat32> inputLayer = inputLayer(featureWidth, tf);

    }

    @Override
    public void close() throws Exception {

    }
}
