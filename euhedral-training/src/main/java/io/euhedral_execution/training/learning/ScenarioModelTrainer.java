package io.euhedral_execution.training.learning;

import ai.djl.Device;
import ai.djl.engine.Engine;
import io.euhedral_execution.training.data.PartitionCounts;
import io.euhedral_execution.training.data.PolicyId;
import io.euhedral_execution.training.data.PolicyVector;
import io.euhedral_execution.training.data.SourceScenario;
import io.euhedral_execution.training.learning.config.EvaluationThresholds;
import io.euhedral_execution.training.learning.config.ScenarioTrainingConfig;
import io.euhedral_execution.training.learning.data.PolicyGroupedSplit;
import io.euhedral_execution.training.learning.data.PolicyGroupedSplitter;
import io.euhedral_execution.training.learning.data.PolicyPredictionCurve;
import io.euhedral_execution.training.learning.data.ScenarioLearningMatrix;
import io.euhedral_execution.training.learning.data.ScenarioPrediction;
import io.euhedral_execution.training.learning.enums.EvaluationStatus;
import io.euhedral_execution.training.learning.enums.FeatureSelectionMode;
import io.euhedral_execution.training.learning.enums.ModelAcceptanceStatus;
import io.euhedral_execution.training.learning.enums.ScenarioFeatureSet;
import io.euhedral_execution.training.learning.inputs.ScenarioLearningReader;
import io.euhedral_execution.training.learning.inputs.ScenarioLearningRow;
import io.euhedral_execution.training.learning.inputs.ScenarioLearningTable;
import io.euhedral_execution.training.learning.inputs.ScenarioTrainingRequest;
import io.euhedral_execution.training.learning.metadata.EvaluationSummaryMetadata;
import io.euhedral_execution.training.learning.metadata.FeatureNormalizer;
import io.euhedral_execution.training.learning.metadata.MemberMetadata;
import io.euhedral_execution.training.learning.metadata.MetadataProbe;
import io.euhedral_execution.training.learning.metadata.ProducerMetadata;
import io.euhedral_execution.training.learning.metadata.ScenarioModelMetadata;
import io.euhedral_execution.training.learning.metadata.ScenarioModelMetadataCodec;
import io.euhedral_execution.training.learning.output.EvaluationSummary;
import io.euhedral_execution.training.learning.output.ScenarioLearningReportWriter;
import io.euhedral_execution.training.learning.output.ScenarioTrainingArtifacts;
import io.euhedral_execution.training.learning.output.TrainingHistoryEntry;
import io.euhedral_execution.training.learning.statistics.LosoEvaluationMetrics;
import io.euhedral_execution.training.learning.statistics.ScenarioEvaluationMetrics;
import io.euhedral_execution.training.learning.utils.ScenarioFeatureEncoder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;

public final class ScenarioModelTrainer {

    public static ScenarioTrainingArtifacts train(ScenarioTrainingRequest request)
            throws Exception {
        Path target = request.modelDirectory();
        if (Files.exists(target)) {
            throw new IllegalArgumentException("Model directory already exists: " + target);
        }
        Path parent = target.getParent();
        if (parent == null) {
            throw new IllegalArgumentException("Model directory has no parent");
        }
        Files.createDirectories(parent);
        Path temporary = parent.resolve(target.getFileName() + ".tmp-" + UUID.randomUUID());
        Path foldWorkspace = parent.resolve(target.getFileName() + ".folds-" + UUID.randomUUID());
        Files.createDirectory(temporary);
        Files.createDirectory(foldWorkspace);
        boolean published = false;
        try {
            ScenarioTrainingArtifacts artifacts =
                    trainInto(request, temporary, foldWorkspace);
            deleteTree(foldWorkspace);
            publish(temporary, target);
            published = true;
            return new ScenarioTrainingArtifacts(target,
                    target.resolve(ScenarioModelMetadataCodec.FILE_NAME),
                    target.resolve("grouped-evaluation.csv"),
                    target.resolve("loso-evaluation.csv"),
                    target.resolve("ablation-evaluation.csv"),
                    target.resolve("training-history.csv"), artifacts.acceptanceStatus(),
                    artifacts.selectedFeatureSet());
        } finally {
            if (!published) {
                deleteTree(temporary);
                deleteTree(foldWorkspace);
            }
        }
    }

    private static ScenarioTrainingArtifacts trainInto(ScenarioTrainingRequest request,
            Path directory, Path foldWorkspace) throws Exception {
        ScenarioTrainingConfig requestedConfig = request.config();
        ScenarioLearningTable table = ScenarioLearningReader.read(request.inputs(),
                request.requiredScenarios(), requestedConfig.includeWeakCalibrationRows());
        PolicyGroupedSplit split = PolicyGroupedSplitter.split(
                table, requestedConfig.splitSeed(), requestedConfig);
        Device device = ScenarioOrdinalNetwork.resolveDevice(requestedConfig.device());
        int effectiveBatch = requestedConfig.batchSize() > 0
                ? StrictMath.min(requestedConfig.batchSize(), split.trainingRows().size())
                : StrictMath.min(device.isGpu() ? 4_096 : 512, split.trainingRows().size());
        ScenarioTrainingConfig config = withEffectiveBatch(requestedConfig, effectiveBatch);
        ArrayList<TrainingHistoryEntry> history = new ArrayList<>();

        ArrayList<EvaluationSummary> policyContext = new ArrayList<>();
        ArrayList<EvaluationSummary> ratioContext = new ArrayList<>();
        if (config.requireTargetVariation()) {
            for (SourceScenario heldOut : table.requiredScenarios()) {
                List<ScenarioLearningRow> fitting =
                        PolicyGroupedSplitter.withoutScenario(split.trainingRows(), heldOut);
                List<ScenarioLearningRow> early = PolicyGroupedSplitter.withoutScenario(
                        split.ablationEarlyStopRows(), heldOut);
                List<ScenarioLearningRow> score = PolicyGroupedSplitter.onlyScenario(
                        split.ablationScoreRows(), heldOut);
                ScenarioFoldRunner.validateRowSets(fitting, early, score);
                if (ScenarioFoldRunner.distinctRatios(fitting) < 2) {
                    throw new InsufficientScenarioLearningDataException(
                            "Context fold lacks two fitting ratios: " + heldOut);
                }
            }
            for (SourceScenario heldOut : table.requiredScenarios()) {
                List<ScenarioLearningRow> fitting =
                        PolicyGroupedSplitter.withoutScenario(split.trainingRows(), heldOut);
                List<ScenarioLearningRow> early = PolicyGroupedSplitter.withoutScenario(
                        split.ablationEarlyStopRows(), heldOut);
                List<ScenarioLearningRow> score = PolicyGroupedSplitter.onlyScenario(
                        split.ablationScoreRows(), heldOut);
                ScenarioFoldRunner.FoldResult policyFold = ScenarioFoldRunner.run(
                        "VALIDATION_CONTEXT_LOSO", "VALIDATION_CONTEXT_LOSO",
                        heldOut.canonical(), ScenarioFeatureSet.POLICY_ONLY, fitting, early, score,
                        config.ablationMembers(), config, device,
                        foldWorkspace.resolve("context-policy").resolve(heldOut.canonical()),
                        false);
                policyContext.add(policyFold.evaluation());
                history.addAll(policyFold.history());
                ScenarioFoldRunner.FoldResult ratioFold = ScenarioFoldRunner.run(
                        "VALIDATION_CONTEXT_LOSO", "VALIDATION_CONTEXT_LOSO",
                        heldOut.canonical(), ScenarioFeatureSet.RATIO_ONLY, fitting, early, score,
                        config.ablationMembers(), config, device,
                        foldWorkspace.resolve("context-ratio").resolve(heldOut.canonical()),
                        false);
                ratioContext.add(ratioFold.evaluation());
                history.addAll(ratioFold.history());
            }
        }

        SortedSet<String> environments = new TreeSet<>();
        for (SourceScenario scenario : table.requiredScenarios()) {
            environments.add(scenario.environmentId());
        }
        ArrayList<EvaluationSummary> ratioEnvironments = new ArrayList<>();
        ArrayList<EvaluationSummary> countEnvironments = new ArrayList<>();
        if (config.featureSelectionMode() != FeatureSelectionMode.RATIO_ONLY
                && environments.size() >= 2) {
            try {
                for (String heldEnvironment : environments) {
                    List<ScenarioLearningRow> fitting = PolicyGroupedSplitter.withoutEnvironment(
                            split.trainingRows(), heldEnvironment);
                    List<ScenarioLearningRow> early = PolicyGroupedSplitter.withoutEnvironment(
                            split.ablationEarlyStopRows(), heldEnvironment);
                    List<ScenarioLearningRow> score = split.ablationScoreRows().stream()
                            .filter(row -> row.scenario().environmentId()
                                    .equals(heldEnvironment)).toList();
                    ScenarioFoldRunner.FoldResult ratioFold = ScenarioFoldRunner.run(
                            "VALIDATION_COUNTS_LOEO", "VALIDATION_COUNTS_LOEO",
                            heldEnvironment, ScenarioFeatureSet.RATIO_ONLY, fitting, early, score,
                            config.ablationMembers(), config, device,
                            foldWorkspace.resolve("environment-ratio")
                                    .resolve(heldEnvironment), false);
                    ratioEnvironments.add(ratioFold.evaluation());
                    history.addAll(ratioFold.history());
                    ScenarioFoldRunner.FoldResult countFold = ScenarioFoldRunner.run(
                            "VALIDATION_COUNTS_LOEO", "VALIDATION_COUNTS_LOEO",
                            heldEnvironment, ScenarioFeatureSet.RATIO_AND_COUNTS,
                            fitting, early, score, config.ablationMembers(), config, device,
                            foldWorkspace.resolve("environment-counts")
                                    .resolve(heldEnvironment), false);
                    countEnvironments.add(countFold.evaluation());
                    history.addAll(countFold.history());
                }
            } catch (InsufficientScenarioLearningDataException optionalFailure) {
                ratioEnvironments.clear();
                countEnvironments.clear();
            }
        }
        ScenarioAblationPlanner.Decision ablation = ScenarioAblationPlanner.decide(
                config.featureSelectionMode(), policyContext, ratioContext,
                ratioEnvironments, countEnvironments, environments.size(), config.thresholds());
        ScenarioFeatureSet selectedFeatureSet =
                ablation.selection().selectedFeatureSet();

        FeatureNormalizer normalizer = ScenarioFeatureEncoder.fit(
                split.trainingRows(), selectedFeatureSet);
        ScenarioLearningMatrix training = ScenarioFeatureEncoder.matrix(split.trainingRows(),
                table.requiredScenarios(), normalizer);
        ScenarioLearningMatrix validation = ScenarioFeatureEncoder.matrix(
                split.validationRows(), table.requiredScenarios(), normalizer);
        ArrayList<OrdinalMember> productionMembers = new ArrayList<>();
        ArrayList<ScenarioOrdinalNetwork.TrainingResult> productionResults = new ArrayList<>();
        try {
            for (int memberIndex = 0; memberIndex < config.ensembleMembers(); memberIndex++) {
                Path memberDirectory = directory.resolve("members")
                        .resolve("member-%03d".formatted(memberIndex));
                ScenarioOrdinalNetwork.TrainingResult result = ScenarioOrdinalNetwork.train(
                        training, validation, selectedFeatureSet, config, device, "PRODUCTION",
                        "all", memberIndex, memberDirectory);
                productionResults.add(result);
                productionMembers.add(result.member());
                history.addAll(result.history());
            }

            EvaluationSummary grouped;
            MetadataProbe probe;
            try (ScenarioConditionedModel model = ScenarioConditionedModel.forTest(
                    normalizer, table.requiredScenarios(), productionMembers)) {
                List<PolicyPredictionCurve> testPredictions = model.predictConfiguredCurves(
                        policies(split.testRows()));
                grouped = ScenarioModelEvaluator.evaluate("GROUPED_TEST", selectedFeatureSet,
                        split.testRows(), ScenarioFoldRunner.retainPredictionsForRows(
                                testPredictions, split.testRows()));
                PolicyVector probePolicy = table.policies().get(table.policies().firstKey());
                SourceScenario probeScenario = table.requiredScenarios().first();
                ScenarioPrediction prediction = model.predictCurves(List.of(probePolicy),
                                new TreeSet<>(List.of(probeScenario)), 1).getFirst()
                        .scenarios().getFirst();
                probe = MetadataProbe.from(probePolicy.id(), prediction, deviceName(device));
                productionMembers.clear();
            }

            LosoResult loso = runLoso(table, split, selectedFeatureSet, config, device,
                    foldWorkspace.resolve("test-loso"));
            history.addAll(loso.history());
            Acceptance acceptance = acceptance(ablation, grouped, loso.summary(), config);

            Path groupedPath = directory.resolve("grouped-evaluation.csv");
            Path losoPath = directory.resolve("loso-evaluation.csv");
            Path ablationPath = directory.resolve("ablation-evaluation.csv");
            Path historyPath = directory.resolve("training-history.csv");
            ScenarioLearningReportWriter.writeGrouped(groupedPath, grouped);
            ScenarioLearningReportWriter.writeLoso(losoPath, loso.rows());
            ScenarioLearningReportWriter.writeAblation(
                    ablationPath, ablation.selection().metrics());
            ScenarioLearningReportWriter.writeHistory(historyPath, history);

            ArrayList<MemberMetadata> members = new ArrayList<>();
            for (int index = 0; index < productionResults.size(); index++) {
                ScenarioOrdinalNetwork.TrainingResult result = productionResults.get(index);
                String relative = MemberMetadata.expectedPath(index);
                Path file = directory.resolve(relative);
                members.add(new MemberMetadata(index, result.seed(), result.bestEpoch(),
                        relative, ScenarioConditionedModel.sha256(file)));
            }
            PartitionCounts partitionCounts = partitionCounts(split,
                    table.requiredScenarios());
            EvaluationSummaryMetadata summaryMetadata = new EvaluationSummaryMetadata(
                    "grouped-evaluation.csv", "loso-evaluation.csv", grouped.macroMae(),
                    grouped.macroSpearman(), grouped.macroPrecisionAtTen(),
                    loso.summary().macroMae(), loso.summary().macroSpearman(),
                    loso.summary().worstScenarioMae());
            ProducerMetadata producer = new ProducerMetadata(request.commitSha(),
                    request.dirtyWorkingTree(), ScenarioOrdinalNetwork.ENGINE_NAME,
                    Engine.getEngine(ScenarioOrdinalNetwork.ENGINE_NAME).getVersion(),
                    deviceName(device));
            ScenarioModelMetadata metadata = new ScenarioModelMetadata(
                    ScenarioModelMetadata.SCHEMA_VERSION,
                    ScenarioModelMetadata.OBJECTIVE_VERSION, selectedFeatureSet, normalizer,
                    ScenarioModelMetadata.expectedThresholdBits(),
                    ScenarioModelMetadata.ARCHITECTURE,
                    ScenarioModelMetadata.MEMBER_MODEL_NAME, members,
                    ScenarioModelMetadata.SPLIT_ALGORITHM, config.splitSeed(),
                    config.modelSeed(), table.datasetFingerprintSha256(),
                    config.includeWeakCalibrationRows(), table.requiredScenarios(),
                    ScenarioFoldRunner.scenarios(split.trainingRows()), partitionCounts, config,
                    ablation.selection(), summaryMetadata, acceptance.status(),
                    acceptance.reasons(), producer, probe);
            Path metadataPath = directory.resolve(ScenarioModelMetadataCodec.FILE_NAME);
            ScenarioModelMetadataCodec.write(metadataPath, metadata);
            validateArtifact(directory, metadata, table);
            return new ScenarioTrainingArtifacts(directory, metadataPath, groupedPath, losoPath,
                    ablationPath, historyPath, acceptance.status(), selectedFeatureSet);
        } finally {
            for (OrdinalMember member : productionMembers) {
                member.close();
            }
        }
    }

    private static LosoResult runLoso(ScenarioLearningTable table, PolicyGroupedSplit split,
            ScenarioFeatureSet featureSet, ScenarioTrainingConfig config, Device device,
            Path directory) throws Exception {
        ArrayList<LosoEvaluationMetrics> rows = new ArrayList<>();
        ArrayList<ScenarioEvaluationMetrics> metrics = new ArrayList<>();
        ArrayList<TrainingHistoryEntry> history = new ArrayList<>();
        for (SourceScenario heldOut : table.requiredScenarios()) {
            List<ScenarioLearningRow> fitting =
                    PolicyGroupedSplitter.withoutScenario(split.trainingRows(), heldOut);
            List<ScenarioLearningRow> early =
                    PolicyGroupedSplitter.withoutScenario(split.validationRows(), heldOut);
            List<ScenarioLearningRow> score =
                    PolicyGroupedSplitter.onlyScenario(split.testRows(), heldOut);
            if (!config.requireTargetVariation()
                    && (fitting.isEmpty() || early.isEmpty() || score.isEmpty())) {
                continue;
            }
            int distinctRatios = ScenarioFoldRunner.distinctRatios(fitting);
            boolean insufficientContext = featureSet != ScenarioFeatureSet.POLICY_ONLY
                    && distinctRatios < 2;
            ScenarioFoldRunner.FoldResult fold = ScenarioFoldRunner.run("TEST_LOSO", "TEST_LOSO",
                    heldOut.canonical(), featureSet, fitting, early, score,
                    config.losoEvaluationMembers(), config, device,
                    directory.resolve(heldOut.canonical()), insufficientContext);
            ScenarioEvaluationMetrics metric = fold.evaluation().scenarios().getFirst();
            metrics.add(metric);
            history.addAll(fold.history());
            boolean ratioSeen = fitting.stream().anyMatch(row ->
                    row.scenario().ratio().equals(heldOut.ratio()));
            rows.add(new LosoEvaluationMetrics(metric, heldOut.ratio().asDouble(), ratioSeen,
                    fold.fittingScenarios().size(), distinctRatios));
        }
        return new LosoResult(List.copyOf(rows),
                ScenarioModelEvaluator.summarize("TEST_LOSO", featureSet, metrics),
                List.copyOf(history));
    }

    static Acceptance acceptance(ScenarioAblationPlanner.Decision ablation,
            EvaluationSummary grouped, EvaluationSummary loso, ScenarioTrainingConfig config) {
        ArrayList<String> reasons = new ArrayList<>();
        ModelAcceptanceStatus status = ModelAcceptanceStatus.ACCEPTED;
        if (!ablation.contextPassed()) {
            status = ModelAcceptanceStatus.SCENARIO_CONTEXT_GATE_FAILED;
            EvaluationThresholds thresholds = config.thresholds();
            reasons.add("SCENARIO_CONTEXT_GATE_FAILED"
                    + "_MAE_IMPROVEMENT_MIN_"
                    + Double.toString(thresholds.minimumContextMaeImprovement())
                    + "_SPEARMAN_IMPROVEMENT_MIN_"
                    + Double.toString(thresholds.minimumContextSpearmanImprovement())
                    + "_MAE_REGRESSION_MAX_"
                    + Double.toString(thresholds.maximumContextMaeRegression())
                    + "_SPEARMAN_REGRESSION_MAX_"
                    + Double.toString(thresholds.maximumContextSpearmanRegression()));
        }
        if (config.featureSelectionMode() == FeatureSelectionMode.REQUIRE_COUNTS
                && !ablation.countsPassed()) {
            if (status == ModelAcceptanceStatus.ACCEPTED) {
                status = ModelAcceptanceStatus.REQUIRED_COUNTS_GATE_FAILED;
            }
            EvaluationThresholds thresholds = config.thresholds();
            reasons.add("REQUIRED_COUNTS_GATE_FAILED"
                    + "_MAE_IMPROVEMENT_MIN_"
                    + Double.toString(
                    thresholds.minimumCountsCrossEnvironmentMaeImprovement())
                    + "_SPEARMAN_REGRESSION_MAX_"
                    + Double.toString(thresholds.maximumCountsSpearmanRegression())
                    + "_WORST_ENVIRONMENT_MAE_REGRESSION_MAX_"
                    + Double.toString(
                    thresholds.maximumCountsWorstEnvironmentMaeRegression()));
        }
        EvaluationThresholds thresholds = config.thresholds();
        boolean groupedOk = allOk(grouped)
                && atMost(grouped.macroMae(), thresholds.maximumGroupedMacroMae())
                && atLeast(grouped.macroSpearman(), thresholds.minimumGroupedMacroSpearman())
                && atLeast(grouped.macroPrecisionAtTen(),
                thresholds.minimumGroupedMacroPrecisionAtTen());
        if (!groupedOk) {
            if (status == ModelAcceptanceStatus.ACCEPTED) {
                status = ModelAcceptanceStatus.GROUPED_QUALITY_GATE_FAILED;
            }
            addQualityReasons(reasons, "GROUPED", grouped,
                    thresholds.maximumGroupedMacroMae(),
                    thresholds.minimumGroupedMacroSpearman(),
                    thresholds.minimumGroupedMacroPrecisionAtTen(), OptionalDouble.empty());
        }
        boolean losoOk = allOk(loso)
                && atMost(loso.macroMae(), thresholds.maximumLosoMacroMae())
                && atLeast(loso.macroSpearman(), thresholds.minimumLosoMacroSpearman())
                && atMost(loso.worstScenarioMae(), thresholds.maximumLosoWorstScenarioMae());
        if (!losoOk) {
            if (status == ModelAcceptanceStatus.ACCEPTED) {
                status = ModelAcceptanceStatus.LOSO_QUALITY_GATE_FAILED;
            }
            addQualityReasons(reasons, "LOSO", loso, thresholds.maximumLosoMacroMae(),
                    thresholds.minimumLosoMacroSpearman(), Double.NaN,
                    OptionalDouble.of(thresholds.maximumLosoWorstScenarioMae()));
        }
        return new Acceptance(status, List.copyOf(reasons));
    }

    private static void addQualityReasons(List<String> reasons, String prefix,
            EvaluationSummary summary, double maximumMae, double minimumSpearman,
            double minimumPrecision, OptionalDouble maximumWorst) {
        if (!allOk(summary)) {
            reasons.add(prefix + "_NON_OK_SCENARIO");
        }
        if (!atMost(summary.macroMae(), maximumMae)) {
            reasons.add(prefix + "_MACRO_MAE_MAX_" + Double.toString(maximumMae));
        }
        if (!atLeast(summary.macroSpearman(), minimumSpearman)) {
            reasons.add(prefix + "_MACRO_SPEARMAN_MIN_" + Double.toString(minimumSpearman));
        }
        if (Double.isFinite(minimumPrecision)
                && !atLeast(summary.macroPrecisionAtTen(), minimumPrecision)) {
            reasons.add(prefix + "_MACRO_PRECISION_AT_10_MIN_"
                    + Double.toString(minimumPrecision));
        }
        if (maximumWorst.isPresent()
                && !atMost(summary.worstScenarioMae(), maximumWorst.getAsDouble())) {
            reasons.add(prefix + "_WORST_SCENARIO_MAE_MAX_"
                    + Double.toString(maximumWorst.getAsDouble()));
        }
    }

    private static boolean allOk(EvaluationSummary summary) {
        return !summary.scenarios().isEmpty() && summary.scenarios().stream()
                .allMatch(metric -> metric.status() == EvaluationStatus.OK);
    }

    private static boolean atMost(OptionalDouble value, double threshold) {
        return value.isPresent() && value.getAsDouble() <= threshold;
    }

    private static boolean atLeast(OptionalDouble value, double threshold) {
        return value.isPresent() && value.getAsDouble() >= threshold;
    }

    private static void validateArtifact(Path directory, ScenarioModelMetadata metadata,
            ScenarioLearningTable table) throws Exception {
        PolicyVector probePolicy = table.policies().get(metadata.metadataProbe().policyId());
        if (probePolicy == null) {
            throw new IOException("Metadata probe policy is absent");
        }
        try (ScenarioConditionedModel loaded = ScenarioConditionedModel.loadForAudit(
                directory, metadata.producer().trainingDevice())) {
            ScenarioPrediction prediction = loaded.predictCurves(List.of(probePolicy),
                            new TreeSet<>(List.of(metadata.metadataProbe().scenario())), 1)
                    .getFirst().scenarios().getFirst();
            MetadataProbe reproduced = MetadataProbe.from(probePolicy.id(), prediction,
                    metadata.producer().trainingDevice());
            if (!reproduced.predictionRawBits().equals(
                    metadata.metadataProbe().predictionRawBits())) {
                throw new IOException("Metadata probe did not reproduce bit-for-bit");
            }
        }
    }

    private static PartitionCounts partitionCounts(PolicyGroupedSplit split,
            SortedSet<SourceScenario> scenarios) {
        TreeMap<String, List<ScenarioLearningRow>> partitions = new TreeMap<>();
        partitions.put("TRAIN", split.trainingRows());
        partitions.put("VALIDATION", split.validationRows());
        partitions.put("TEST", split.testRows());
        partitions.put("ABLATION_EARLY_STOP", split.ablationEarlyStopRows());
        partitions.put("ABLATION_SCORE", split.ablationScoreRows());
        TreeMap<String, Integer> policies = new TreeMap<>();
        TreeMap<String, Integer> rows = new TreeMap<>();
        TreeMap<String, SortedMap<SourceScenario, Integer>> scenarioRows = new TreeMap<>();
        for (Map.Entry<String, List<ScenarioLearningRow>> entry : partitions.entrySet()) {
            policies.put(entry.getKey(), (int) entry.getValue().stream()
                    .map(row -> row.policy().id()).distinct().count());
            rows.put(entry.getKey(), entry.getValue().size());
            TreeMap<SourceScenario, Integer> counts = new TreeMap<>();
            for (SourceScenario scenario : scenarios) {
                counts.put(scenario, 0);
            }
            for (ScenarioLearningRow row : entry.getValue()) {
                counts.merge(row.scenario(), 1, Integer::sum);
            }
            scenarioRows.put(entry.getKey(), counts);
        }
        return new PartitionCounts(policies, rows, scenarioRows);
    }

    private static List<PolicyVector> policies(List<ScenarioLearningRow> rows) {
        TreeMap<PolicyId, PolicyVector> policies = new TreeMap<>();
        for (ScenarioLearningRow row : rows) {
            policies.put(row.policy().id(), row.policy());
        }
        return List.copyOf(policies.values());
    }

    private static ScenarioTrainingConfig withEffectiveBatch(ScenarioTrainingConfig source,
            int effectiveBatch) {
        return new ScenarioTrainingConfig(source.splitSeed(), source.modelSeed(),
                source.device(), source.ensembleMembers(), source.losoEvaluationMembers(),
                source.ablationMembers(), source.maxEpochs(), source.patience(), effectiveBatch,
                source.learningRate(), source.weightDecay(), source.labelSmoothing(),
                source.minimumTrainPolicyGroups(), source.minimumValidationPolicyGroups(),
                source.minimumTestPolicyGroups(), source.minimumTrainRowsPerScenario(),
                source.minimumValidationRowsPerScenario(), source.minimumTestRowsPerScenario(),
                source.includeWeakCalibrationRows(), source.requireTargetVariation(),
                source.featureSelectionMode(), source.thresholds());
    }

    private static String deviceName(Device device) {
        return device.isGpu() ? "gpu" + device.getDeviceId() : "cpu";
    }

    private static void publish(Path temporary, Path target) throws IOException {
        Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
    }

    private static void deleteTree(Path directory) throws IOException {
        if (directory == null || !Files.exists(directory)) {
            return;
        }
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private ScenarioModelTrainer() {
    }

    private record LosoResult(List<LosoEvaluationMetrics> rows, EvaluationSummary summary,
                              List<TrainingHistoryEntry> history) {

    }

    record Acceptance(ModelAcceptanceStatus status, List<String> reasons) {

    }
}
