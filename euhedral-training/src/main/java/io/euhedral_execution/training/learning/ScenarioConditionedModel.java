package io.euhedral_execution.training.learning;

import ai.djl.Device;
import io.euhedral_execution.training.data.PolicyId;
import io.euhedral_execution.training.data.PolicyVector;
import io.euhedral_execution.training.data.SourceScenario;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.SortedSet;
import java.util.TreeSet;

public final class ScenarioConditionedModel implements AutoCloseable {
    private final ScenarioModelMetadata metadata;
    private final FeatureNormalizer normalizer;
    private final SortedSet<SourceScenario> configuredScenarios;
    private final List<OrdinalMember> members;
    private boolean closed;

    private ScenarioConditionedModel(ScenarioModelMetadata metadata,
            FeatureNormalizer normalizer, SortedSet<SourceScenario> scenarios,
            List<OrdinalMember> members) {
        this.metadata = metadata;
        this.normalizer = Objects.requireNonNull(normalizer);
        configuredScenarios = Collections.unmodifiableSortedSet(new TreeSet<>(scenarios));
        this.members = List.copyOf(members);
        if (configuredScenarios.isEmpty() || members.isEmpty()
                || metadata != null && members.size() != metadata.members().size()
                || members.stream().anyMatch(member ->
                member.featureWidth() != normalizer.featureNames().size())) {
            throw new IllegalArgumentException("Invalid scenario-conditioned model");
        }
    }

    public static ScenarioConditionedModel load(Path modelDirectory) throws IOException {
        return load(modelDirectory, "auto");
    }

    public static ScenarioConditionedModel load(Path modelDirectory, String device)
            throws IOException {
        return loadInternal(modelDirectory, device, false);
    }

    public static ScenarioConditionedModel loadForAudit(Path modelDirectory) throws IOException {
        return loadForAudit(modelDirectory, "auto");
    }

    public static ScenarioConditionedModel loadForAudit(Path modelDirectory, String device)
            throws IOException {
        return loadInternal(modelDirectory, device, true);
    }

    private static ScenarioConditionedModel loadInternal(Path modelDirectory, String device,
            boolean audit) throws IOException {
        Objects.requireNonNull(modelDirectory);
        Objects.requireNonNull(device);
        Path directory = modelDirectory.toAbsolutePath().normalize();
        ScenarioModelMetadata metadata =
                ScenarioModelMetadataCodec.read(directory.resolve(
                        ScenarioModelMetadataCodec.FILE_NAME));
        if (!audit && !metadata.deploymentEligible()) {
            throw new IOException("Scenario model is rejected: " + metadata.acceptanceStatus());
        }
        for (MemberMetadata member : metadata.members()) {
            Path file = directory.resolve(member.relativePath()).normalize();
            if (!file.startsWith(directory) || !Files.isRegularFile(file)
                    || !sha256(file).equals(member.sha256())) {
                throw new IOException("Missing or checksum-mismatched member "
                        + member.index());
            }
        }
        Device resolved;
        try {
            resolved = ScenarioOrdinalNetwork.resolveDevice(device.trim().toLowerCase());
        } catch (RuntimeException error) {
            throw new IOException("Invalid scenario model device " + device, error);
        }
        ArrayList<OrdinalMember> members = new ArrayList<>(metadata.members().size());
        try {
            for (MemberMetadata member : metadata.members()) {
                Path file = directory.resolve(member.relativePath()).normalize();
                members.add(ScenarioOrdinalNetwork.load(file.getParent(),
                        metadata.featureSet(), member, resolved));
            }
            return new ScenarioConditionedModel(metadata, metadata.normalizer(),
                    metadata.requiredScenarios(), members);
        } catch (Throwable error) {
            closeMembers(members);
            if (error instanceof IOException io) throw io;
            throw new IOException("Failed to load scenario-conditioned model", error);
        }
    }

    static ScenarioConditionedModel forTest(ScenarioModelMetadata metadata,
            List<OrdinalMember> members) {
        Objects.requireNonNull(metadata);
        return new ScenarioConditionedModel(metadata, metadata.normalizer(),
                metadata.requiredScenarios(), members);
    }

    // Kept package-private for focused feature propagation tests that do not construct artifacts.
    static ScenarioConditionedModel forTest(FeatureNormalizer normalizer,
            SortedSet<SourceScenario> scenarios, List<OrdinalMember> members) {
        return new ScenarioConditionedModel(null, normalizer, scenarios, members);
    }

    public ScenarioModelMetadata metadata() {
        ensureOpen();
        if (metadata == null) {
            throw new IllegalStateException("Test model has no artifact metadata");
        }
        return metadata;
    }

    public List<PolicyPredictionCurve> predictConfiguredCurves(List<PolicyVector> policies) {
        return predictCurves(policies, configuredScenarios, recommendedInferenceBatchRows());
    }

    public List<PolicyPredictionCurve> predictCurves(List<PolicyVector> policies,
            SortedSet<SourceScenario> scenarios, int maximumBatchRows) {
        ensureOpen();
        Objects.requireNonNull(policies);
        Objects.requireNonNull(scenarios);
        if (policies.isEmpty() || scenarios.isEmpty() || maximumBatchRows <= 0) {
            throw new IllegalArgumentException("Inference inputs must be non-empty");
        }
        HashSet<PolicyId> ids = new HashSet<>();
        for (PolicyVector policy : policies) {
            Objects.requireNonNull(policy);
            if (!ids.add(policy.id())) {
                throw new IllegalArgumentException("Duplicate policy ID " + policy.id());
            }
        }
        List<SourceScenario> orderedScenarios = List.copyOf(new TreeSet<>(scenarios));
        int scenarioCount = orderedScenarios.size();
        int policiesPerBatch = StrictMath.max(1, maximumBatchRows / scenarioCount);
        ArrayList<PolicyPredictionCurve> result = new ArrayList<>(policies.size());
        try {
            for (int start = 0; start < policies.size(); start += policiesPerBatch) {
                int policyCount = StrictMath.min(policiesPerBatch, policies.size() - start);
                predictBatch(policies, start, policyCount, orderedScenarios, result);
            }
        } catch (RuntimeException | Error failure) {
            try {
                close();
            } catch (RuntimeException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
        return List.copyOf(result);
    }

    private void predictBatch(List<PolicyVector> policies, int policyStart, int policyCount,
            List<SourceScenario> scenarios, ArrayList<PolicyPredictionCurve> destination) {
        int scenarioCount = scenarios.size();
        int rows = policyCount * scenarioCount;
        int featureWidth = normalizer.featureNames().size();
        float[] features = new float[rows * featureWidth];
        for (int policyIndex = 0; policyIndex < policyCount; policyIndex++) {
            PolicyVector policy = policies.get(policyStart + policyIndex);
            for (int scenarioIndex = 0; scenarioIndex < scenarioCount; scenarioIndex++) {
                int row = policyIndex * scenarioCount + scenarioIndex;
                normalizer.encode(policy, scenarios.get(scenarioIndex), features,
                        row * featureWidth);
            }
        }
        double[] massSums = new double[rows * 10];
        double[] massCorrections = new double[rows * 10];
        double[] topSums = new double[rows];
        double[] topCorrections = new double[rows];
        double[] runningMeans = new double[rows];
        double[] runningM2 = new double[rows];
        double[] minimumMeans = new double[rows];
        double[] maximumMeans = new double[rows];
        java.util.Arrays.fill(minimumMeans, Double.POSITIVE_INFINITY);
        java.util.Arrays.fill(maximumMeans, Double.NEGATIVE_INFINITY);
        float[] logits = new float[rows * 9];
        for (int memberIndex = 0; memberIndex < members.size(); memberIndex++) {
            java.util.Arrays.fill(logits, 0);
            members.get(memberIndex).predictLogits(features, rows, logits);
            int sampleCount = memberIndex + 1;
            for (int row = 0; row < rows; row++) {
                double[] rowLogits = new double[9];
                for (int output = 0; output < 9; output++) {
                    rowLogits[output] = logits[row * 9 + output];
                }
                OrdinalDistribution distribution = ScenarioOrdinalTargets.decode(rowLogits);
                double[] masses = distribution.binMasses();
                for (int bin = 0; bin < 10; bin++) {
                    neumaierAdd(massSums, massCorrections, row * 10 + bin, masses[bin]);
                }
                neumaierAdd(topSums, topCorrections, row,
                        distribution.topDecileProbability());
                double memberMean = distribution.meanQuality();
                double delta = memberMean - runningMeans[row];
                runningMeans[row] += delta / sampleCount;
                runningM2[row] += delta * (memberMean - runningMeans[row]);
                minimumMeans[row] = StrictMath.min(minimumMeans[row], memberMean);
                maximumMeans[row] = StrictMath.max(maximumMeans[row], memberMean);
            }
        }
        for (int policyIndex = 0; policyIndex < policyCount; policyIndex++) {
            ArrayList<ScenarioPrediction> predictions = new ArrayList<>(scenarioCount);
            for (int scenarioIndex = 0; scenarioIndex < scenarioCount; scenarioIndex++) {
                int row = policyIndex * scenarioCount + scenarioIndex;
                double[] meanMasses = new double[10];
                for (int bin = 0; bin < 10; bin++) {
                    int index = row * 10 + bin;
                    meanMasses[bin] =
                            (massSums[index] + massCorrections[index]) / members.size();
                }
                double epistemic = members.size() == 1 ? 0
                        : StrictMath.sqrt(StrictMath.max(0,
                        runningM2[row] / (members.size() - 1)));
                EnsembleOrdinalDistribution distribution =
                        ScenarioOrdinalTargets.combineAggregatedUncertainty(meanMasses,
                                (topSums[row] + topCorrections[row]) / members.size(),
                                epistemic, maximumMeans[row] - minimumMeans[row]);
                predictions.add(new ScenarioPrediction(scenarios.get(scenarioIndex),
                        distribution.predictedQuality(), distribution.ordinalStdDev(),
                        distribution.qualityIntervalLow(), distribution.qualityIntervalHigh(),
                        distribution.ordinalEntropy(), distribution.topDecileProbability(),
                        distribution.epistemicStdDev(), distribution.disagreementRange()));
            }
            destination.add(new PolicyPredictionCurve(
                    policies.get(policyStart + policyIndex), predictions));
        }
    }

    private int recommendedInferenceBatchRows() {
        if (metadata == null) return 16_384;
        return metadata.producer().trainingDevice().startsWith("gpu") ? 65_536 : 16_384;
    }

    private static void neumaierAdd(double[] sums, double[] corrections, int index,
            double value) {
        double current = sums[index];
        double next = current + value;
        corrections[index] += StrictMath.abs(current) >= StrictMath.abs(value)
                ? (current - next) + value : (value - next) + current;
        sums[index] = next;
    }

    static String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(path)) {
                byte[] buffer = new byte[16_384];
                int count;
                while ((count = input.read(buffer)) >= 0) {
                    if (count > 0) digest.update(buffer, 0, count);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private void ensureOpen() {
        if (closed) throw new IllegalStateException("Model is closed");
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            closeMembers(members);
        }
    }

    private static void closeMembers(List<? extends OrdinalMember> members) {
        RuntimeException failure = null;
        for (OrdinalMember member : members) {
            try {
                member.close();
            } catch (RuntimeException error) {
                if (failure == null) failure = error;
                else failure.addSuppressed(error);
            }
        }
        if (failure != null) throw failure;
    }
}
