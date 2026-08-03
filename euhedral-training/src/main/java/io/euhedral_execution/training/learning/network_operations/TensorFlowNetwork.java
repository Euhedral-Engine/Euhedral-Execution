package io.euhedral_execution.training.learning.network_operations;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import org.tensorflow.Graph;
import org.tensorflow.Operand;
import org.tensorflow.Session;
import org.tensorflow.framework.initializers.Glorot;
import org.tensorflow.framework.initializers.Initializer;
import org.tensorflow.framework.initializers.VarianceScaling;
import org.tensorflow.framework.initializers.Zeros;
import org.tensorflow.framework.op.nn.GELU;
import org.tensorflow.framework.optimizers.Adam;
import org.tensorflow.ndarray.Shape;
import org.tensorflow.ndarray.buffer.DataBuffers;
import org.tensorflow.op.Op;
import org.tensorflow.op.Ops;
import org.tensorflow.op.core.Placeholder;
import org.tensorflow.op.core.Variable;
import org.tensorflow.types.TFloat32;

public class TensorFlowNetwork implements AutoCloseable {

    private static final String INPUT_LAYER_NAME = "INPUT";

    private static Placeholder<TFloat32> inputLayer(int featureWidth, Ops tf) {
        return tf.withName(INPUT_LAYER_NAME).placeholder(TFloat32.class, Placeholder.shape(Shape.of(-1, featureWidth)));
    }

    private static Operand<TFloat32> linear(Ops tf, Operand<TFloat32> input, int inUnits, int outUnits, String name) {
        Initializer<TFloat32> weightInit = new Glorot<>(VarianceScaling.Distribution.TRUNCATED_NORMAL, 1L);
        Initializer<TFloat32> biasInit = new Zeros<>();

        Variable<TFloat32> weights = tf.withName(name + "_weights")
                .variable(weightInit.call(tf, tf.array((long) inUnits, (long) outUnits), TFloat32.class));
        Variable<TFloat32> biases =
                tf.withName(name + "_biases").variable(biasInit.call(tf, tf.array((long) outUnits), TFloat32.class));

        return tf.nn.biasAdd(tf.linalg.matMul(input, weights), biases);
    }

    private static Operand<TFloat32> buildBlock(Ops tf, Operand<TFloat32> input, int featureWidth) {
        Operand<TFloat32> layer1 = linear(tf, input, featureWidth, 128, "layer1");
        Operand<TFloat32> act1 = GELU.gelu(tf.scope(), layer1);

        Operand<TFloat32> layer2 = linear(tf, act1, 128, 96, "layer2");
        Operand<TFloat32> act2 = GELU.gelu(tf.scope(), layer2);

        Operand<TFloat32> layer3 = linear(tf, act2, 96, 48, "layer3");
        Operand<TFloat32> act3 = GELU.gelu(tf.scope(), layer3);

        return linear(tf, act3, 48, 9, "layer4");
    }

    private static Operand<TFloat32> buildLoss(
            Ops tf,
            Operand<TFloat32> logits,
            Placeholder<TFloat32> labels,
            Placeholder<TFloat32> rowWeights,
            Placeholder<TFloat32> posWeights,
            Placeholder<TFloat32> negWeights,
            float labelSmoothing) {
        Operand<TFloat32> target = labelSmoothing == 0.0f
                ? labels
                : tf.math.add(
                        tf.math.mul(labels, tf.constant(1.0f - 2.0f * labelSmoothing)), tf.constant(labelSmoothing));
        Operand<TFloat32> classWeight = tf.math.add(
                tf.math.mul(labels, posWeights), tf.math.mul(tf.math.sub(tf.constant(1.0f), labels), negWeights));
        Operand<TFloat32> reluLogit = tf.nn.relu(logits);
        Operand<TFloat32> logitTarget = tf.math.mul(logits, target);
        Operand<TFloat32> absLogit = tf.math.abs(logits);
        Operand<TFloat32> negAbsLogit = tf.math.neg(absLogit);
        Operand<TFloat32> log1pExp = tf.math.log1p(tf.math.exp(negAbsLogit));
        Operand<TFloat32> stableBce = tf.math.add(tf.math.sub(reluLogit, logitTarget), log1pExp);

        Operand<TFloat32> weightedBce = tf.math.mul(tf.math.mul(stableBce, classWeight), rowWeights);
        Operand<TFloat32> totalLoss = tf.reduceSum(weightedBce, tf.array(0, 1));
        Operand<TFloat32> totalWeight = tf.math.mul(tf.reduceSum(rowWeights, tf.array(0, 1)), tf.constant(9.0f));
        return tf.math.div(totalLoss, totalWeight);
    }

    private final int featureWidth;
    private final Graph graph;
    private final Ops tf;
    private final Placeholder<TFloat32> inputPlaceholder;
    private final Operand<TFloat32> outputLayer;

    private final Placeholder<TFloat32> labelPlaceholder;
    private final Placeholder<TFloat32> rowWeightPlaceholder;
    private final Placeholder<TFloat32> posWeightPlaceholder;
    private final Placeholder<TFloat32> negWeightPlaceholder;
    private final Op trainOp;

    private final Session session;
    private final Properties properties;
    private boolean closed;

    public TensorFlowNetwork(int featureWidth, float learningRate, float labelSmoothing) {
        this.featureWidth = featureWidth;
        this.graph = new Graph();
        this.tf = Ops.create(graph);
        this.inputPlaceholder = inputLayer(featureWidth, tf);
        this.outputLayer = buildBlock(tf, inputPlaceholder, featureWidth);

        this.labelPlaceholder = tf.placeholder(TFloat32.class, Placeholder.shape(Shape.of(-1, 9)));
        this.rowWeightPlaceholder = tf.placeholder(TFloat32.class, Placeholder.shape(Shape.of(-1, 1)));
        this.posWeightPlaceholder = tf.placeholder(TFloat32.class, Placeholder.shape(Shape.of(1, 9)));
        this.negWeightPlaceholder = tf.placeholder(TFloat32.class, Placeholder.shape(Shape.of(1, 9)));

        Operand<TFloat32> loss = buildLoss(
                tf,
                outputLayer,
                labelPlaceholder,
                rowWeightPlaceholder,
                posWeightPlaceholder,
                negWeightPlaceholder,
                labelSmoothing);

        Adam adam = new Adam(graph, learningRate);
        this.trainOp = adam.minimize(loss);

        this.session = new Session(graph);
        this.session.initialize();
        this.properties = new Properties();
    }

    public TensorFlowNetwork(int featureWidth) {
        this(featureWidth, 0.001f, 0.0f);
    }

    public int featureWidth() {
        return featureWidth;
    }

    public Graph graph() {
        return graph;
    }

    public Operand<TFloat32> outputLayer() {
        return outputLayer;
    }

    public void predictLogits(float[] features, int rows, float[] destination) {
        ensureOpen();
        if (rows < 0 || features.length != rows * featureWidth || destination.length != rows * 9) {
            throw new IllegalArgumentException("Invalid inference buffers");
        }
        if (rows == 0) {
            return;
        }
        try (TFloat32 inputTensor = TFloat32.tensorOf(Shape.of(rows, featureWidth), DataBuffers.of(features));
                TFloat32 outputTensor = (TFloat32) session.runner()
                        .feed(inputPlaceholder, inputTensor)
                        .fetch(outputLayer)
                        .run()
                        .get(0)) {
            outputTensor.copyTo(DataBuffers.of(destination));
        }
    }

    public void trainBatch(
            float[] features,
            float[] labels,
            float[] rowWeights,
            float[] posWeights,
            float[] negWeights,
            int batchRows) {
        ensureOpen();
        try (TFloat32 inputTensor = TFloat32.tensorOf(Shape.of(batchRows, featureWidth), DataBuffers.of(features));
                TFloat32 labelTensor = TFloat32.tensorOf(Shape.of(batchRows, 9), DataBuffers.of(labels));
                TFloat32 weightTensor = TFloat32.tensorOf(Shape.of(batchRows, 1), DataBuffers.of(rowWeights));
                TFloat32 posTensor = TFloat32.tensorOf(Shape.of(1, 9), DataBuffers.of(posWeights));
                TFloat32 negTensor = TFloat32.tensorOf(Shape.of(1, 9), DataBuffers.of(negWeights))) {
            session.runner()
                    .feed(inputPlaceholder, inputTensor)
                    .feed(labelPlaceholder, labelTensor)
                    .feed(rowWeightPlaceholder, weightTensor)
                    .feed(posWeightPlaceholder, posTensor)
                    .feed(negWeightPlaceholder, negTensor)
                    .addTarget(trainOp)
                    .run();
        }
    }

    public void setProperty(String key, String value) {
        properties.setProperty(key, value);
    }

    public String getProperty(String key) {
        return properties.getProperty(key);
    }

    public void save(Path memberDirectory, String name) throws IOException {
        ensureOpen();
        Files.createDirectories(memberDirectory);
        Path checkpointFile = memberDirectory.resolve(name);
        session.save(checkpointFile.toString());

        Path propFile = memberDirectory.resolve(name + ".properties");
        try (OutputStream out = Files.newOutputStream(propFile)) {
            properties.store(out, "TensorFlowNetwork properties");
        }
    }

    public void load(Path memberDirectory, String name) throws IOException {
        ensureOpen();
        Path checkpointFile = memberDirectory.resolve(name);
        session.restore(checkpointFile.toString());

        Path propFile = memberDirectory.resolve(name + ".properties");
        if (Files.exists(propFile)) {
            try (InputStream in = Files.newInputStream(propFile)) {
                properties.load(in);
            }
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("TensorFlowNetwork is closed");
        }
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            if (session != null) {
                session.close();
            }
            if (graph != null) {
                graph.close();
            }
        }
    }
}
