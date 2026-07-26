package io.euhedral_execution.training.networks;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import org.deeplearning4j.datasets.iterator.utilty.ListDataSetIterator;
import org.deeplearning4j.nn.api.OptimizationAlgorithm;
import org.deeplearning4j.nn.conf.NeuralNetConfiguration;
import org.deeplearning4j.nn.conf.WorkspaceMode;
import org.deeplearning4j.nn.conf.layers.DenseLayer;
import org.deeplearning4j.nn.conf.layers.DropoutLayer;
import org.deeplearning4j.nn.conf.layers.OutputLayer;
import org.deeplearning4j.nn.multilayer.MultiLayerNetwork;
import org.deeplearning4j.nn.weights.WeightInit;
import org.deeplearning4j.util.ModelSerializer;
import org.nd4j.linalg.activations.Activation;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.dataset.AsyncDataSetIterator;
import org.nd4j.linalg.dataset.DataSet;
import org.nd4j.linalg.dataset.api.iterator.DataSetIterator;
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.linalg.learning.config.Adam;
import org.nd4j.linalg.lossfunctions.LossFunctions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Architecture: Input(28) -> Dense(256, ReLU) + BatchNorm + Dropout(0.3) -> Dense(256, ReLU) +
 * BatchNorm + Dropout(0.3) -> Dense(128, ReLU) + BatchNorm + Dropout(0.2) -> Dense(64, ReLU) +
 * BatchNorm + Dropout(0.2) -> Output(5, Linear, MSE) Outputs: [p50 (median), p25 (Q1), p75 (Q3), p0
 * (min), p100 (max)]
 */
public class DL4JVectorScoringNetwork {

    private static final Logger LOGGER = LoggerFactory.getLogger(DL4JVectorScoringNetwork.class);

    private static MultiLayerNetwork buildNetwork(long[] layerSizes) {
        if (layerSizes.length < 2 || layerSizes[0] != 28
                || layerSizes[layerSizes.length - 1] != 5) {
            throw new IllegalArgumentException("Layer sizes must start with 28 and end with 5");
        }

        NeuralNetConfiguration.ListBuilder builder = new NeuralNetConfiguration.Builder()
                .seed(123)
                .trainingWorkspaceMode(WorkspaceMode.ENABLED)
                .inferenceWorkspaceMode(WorkspaceMode.ENABLED)
                .activation(Activation.LEAKYRELU)
                .weightInit(WeightInit.RELU)
                .optimizationAlgo(OptimizationAlgorithm.STOCHASTIC_GRADIENT_DESCENT)
                .updater(new Adam(0.001))  // Adaptive learning rate
                .l2(0.0001)                // L2 regularization
                .list();

        // Build hidden layers with dropout
        for (int i = 0; i < layerSizes.length - 2; i++) {
            long nIn = layerSizes[i];
            long nOut = layerSizes[i + 1];

            // Dense layer
            builder.layer(i * 2, new DenseLayer.Builder()
                    .nIn(nIn)
                    .nOut(nOut)
                    .activation(Activation.LEAKYRELU)
                    .build());

            // Dropout
            builder.layer(i * 2 + 1, new DropoutLayer.Builder(0.1)
                    .build());
        }

        // Output layer
        int outputLayerIdx = (layerSizes.length - 2) * 2;
        builder.layer(outputLayerIdx, new OutputLayer.Builder()
                .nIn(layerSizes[layerSizes.length - 3])
                .nOut((int) layerSizes[layerSizes.length - 1])
                .activation(Activation.IDENTITY)
                .lossFunction(LossFunctions.LossFunction.MSE)
                .build());

        MultiLayerNetwork network = new MultiLayerNetwork(builder.build());
        network.init();
        return network;
    }
    private final MultiLayerNetwork network;
    private double mse = Double.NaN;
    private double mae = 0.0;
    private double sampleCount = 0;

    /**
     * Create new network with default architecture
     */
    public DL4JVectorScoringNetwork() {
        this(new long[]{28, 128, 64, 32, 5});
    }

    /**
     * Create new network with custom layer sizes First element must be 28 (input size), last must
     * be 5 (output size)
     */
    public DL4JVectorScoringNetwork(long[] layerSizes) {
        this.network = buildNetwork(layerSizes);
    }

    /**
     * Load network from file
     */
    public DL4JVectorScoringNetwork(String modelPath) throws Exception {
        this.network = ModelSerializer.restoreMultiLayerNetwork(modelPath);
    }

    /**
     * Get MSE metric
     */
    public double getMSE() {
        return mse;
    }

    /**
     * Get MAE metric
     */
    public double getMAE() {
        return mae;
    }

    /**
     * Predict quantiles for a single control vector
     */
    public double[] predict(double[] input) {
        INDArray features = Nd4j.create(new double[][]{input});
        INDArray output = network.output(features, false);
        return output.getRow(0).toDoubleVector();
    }

    public double[][] predict(double[][] input) {
        INDArray features = Nd4j.create(input);
        INDArray output = network.output(features, false);
        return output.toDoubleMatrix();
    }

    /**
     * Train on a single sample (online learning)
     */
    public void train(double[] input, double[] target) {
        INDArray features = Nd4j.create(new double[][]{input});
        INDArray labels = Nd4j.create(new double[][]{target});
        DataSet dataset = new DataSet(features, labels);

        network.fit(dataset);

        // Update running metrics
        double[] prediction = predict(input);
        double sampleMse = 0.0;
        double sampleMae = 0.0;
        for (int i = 0; i < prediction.length; i++) {
            double error = prediction[i] - target[i];
            sampleMse += error * error;
            sampleMae += Math.abs(error);
        }
        sampleMse /= prediction.length;
        sampleMae /= prediction.length;

        // Exponential weighted moving average
        double alpha = 0.01;
        if (!Double.isFinite(this.mse)) {
            this.mse = sampleMse;
        } else {
            this.mse = this.mse * (1 - alpha) + sampleMse * alpha;
        }

        if (this.sampleCount == 0) {
            this.mae = sampleMae;
            this.sampleCount = 1;
        } else {
            this.sampleCount++;
            this.mae += (sampleMae - this.mae) / this.sampleCount;
        }
    }

    /**
     * Save model to file
     */
    public void save(String modelPath) throws Exception {
        File f = new File(modelPath);
        if (f.getParent() != null && !f.getParentFile().mkdirs() && !f.getParentFile().exists()) {
            throw new IOException("Failed to create parent directory: " + f.getParent());
        }
        ModelSerializer.writeModel(network, modelPath, true);
    }

    /**
     * Clone network for inference (returns a fresh copy)
     */
    public DL4JVectorScoringNetwork asInference() {
        return new DL4JVectorScoringNetwork();
    }

    /**
     * Reset metrics counters
     */
    public void resetMetrics() {
        this.mse = Double.NaN;
        this.mae = 0.0;
        this.sampleCount = 0;
    }

    /**
     * Evaluate the network on a validation dataset and return MSE
     */
    public double evaluateOnDataSet(double[][] inputs, double[][] targets) {
        double totalMse = 0.0;
        for (int i = 0; i < inputs.length; i++) {
            double[] prediction = predict(inputs[i]);
            for (int j = 0; j < prediction.length; j++) {
                double error = prediction[j] - targets[i][j];
                totalMse += error * error;
            }
        }
        return totalMse / (inputs.length * targets[0].length);
    }

    public void updateTrainingMetrics(double[][] inputs, double[][] targets) {
        double totalMae = 0.0;
        double totalMse = 0.0;
        for (int i = 0; i < inputs.length; i++) {
            double[] prediction = predict(inputs[i]);
            for (int j = 0; j < prediction.length; j++) {
                double error = prediction[j] - targets[i][j];
                totalMse += error * error;
                totalMae += Math.abs(error);
            }
        }

        this.mae = totalMae / (inputs.length * targets[0].length);
        this.mse = totalMse / (inputs.length * targets[0].length);
    }

    /**
     * Train with early stopping using validation dataset. Returns the best model achieved during
     * training (loaded from disk).
     */
    public DL4JVectorScoringNetwork trainWithEarlyStopping(
            double[][] trainInputs, double[][] trainTargets,
            double[][] valInputs, double[][] valTargets,
            String modelCheckpointPath,
            int maxEpochs, int patience, int batchSize) throws Exception {

        // Create checkpoint directory
        File checkpointDir = new File(modelCheckpointPath);
        if (checkpointDir.getParent() != null) {
            if (!checkpointDir.getParentFile().mkdirs() && !checkpointDir.getParentFile()
                    .exists()) {
                throw new IOException(
                        "Failed to create checkpoint directory: " + checkpointDir.getParent());
            }
        }

        double bestValMse = Double.POSITIVE_INFINITY;
        int epochsWithoutImprovement = 0;

        LOGGER.info("Starting training with early stopping...");

        INDArray features = Nd4j.create(trainInputs);
        INDArray labels = Nd4j.create(trainTargets);

        DataSet allData = new DataSet(features, labels);
        DataSetIterator iterator =
                new ListDataSetIterator<>(allData.asList(), batchSize);

        DataSetIterator asyncIterator =
                new AsyncDataSetIterator(iterator, 4);
        for (int epoch = 0; epoch < maxEpochs; epoch++) {
            long now = System.nanoTime();
            allData.shuffle();
            asyncIterator.reset();

            while (asyncIterator.hasNext()) {
                DataSet batch = asyncIterator.next();
                network.fit(batch);
            }

            updateTrainingMetrics(trainInputs, trainTargets);
            double valMse = evaluateOnDataSet(valInputs, valTargets);

            LOGGER.info(
                    "Epoch: {} | Train MAE: {} | Train MSE(Running): {} | Validation MSE: {} | Elapsed: {} s",
                    epoch, this.getMAE(), this.getMSE(), valMse, TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - now));

            // Early stopping check
            if (valMse < bestValMse) {
                bestValMse = valMse;
                epochsWithoutImprovement = 0;
                // Save best model
                this.save(modelCheckpointPath);
            } else {
                epochsWithoutImprovement++;
                if (epochsWithoutImprovement >= patience) {
                    LOGGER.info(
                            "Early stopping triggered at Epoch {}. Best Validation MSE: {}",
                            epoch, bestValMse);
                    break;
                }
            }
        }

        // Load and return the best model
        try {
            return new DL4JVectorScoringNetwork(modelCheckpointPath);
        } catch (Exception e) {
            LOGGER.warn("Warning: failed to reload best model; using last trained model");
            return this;
        }
    }
}

