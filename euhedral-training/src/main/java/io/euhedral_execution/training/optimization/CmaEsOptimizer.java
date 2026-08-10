package io.euhedral_execution.training.optimization;

import io.euhedral_execution.training.data.PolicyId;
import io.euhedral_execution.training.data.PolicyVector;
import io.euhedral_execution.training.merge.PolicyComparator;
import io.euhedral_execution.training.merge.data.MergeRecords.RobustPolicySummary;
import io.euhedral_execution.training.optimization.config.CmaEsConfig;
import io.euhedral_execution.training.optimization.data.PredictedCandidate;
import io.euhedral_execution.training.optimization.data.PredictedPolicySummary;
import io.euhedral_execution.training.optimization.enums.CandidateOrigin;
import io.euhedral_execution.training.utils.CommonFunctions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;

/** Full-covariance CMA-ES over the four normalized seven-weight action chunks. */
public final class CmaEsOptimizer {

    public static final int DIMENSIONS = 28;

    private static void runIsland(
            PolicyVector seed,
            double[][] initialCovariance,
            CmaEsConfig config,
            PolicyCurvePredictor predictor,
            Random random,
            List<PredictedCandidate> output) {
        int n = DIMENSIONS;
        int lambda = config.populationSize();
        int mu = lambda / 2;
        double[] weights = new double[mu];
        double weightSum = 0.0;
        for (int i = 0; i < mu; i++) {
            weights[i] = StrictMath.log(mu + 0.5) - StrictMath.log(i + 1.0);
            weightSum += weights[i];
        }
        double squaredWeightSum = 0.0;
        for (int i = 0; i < mu; i++) {
            weights[i] /= weightSum;
            squaredWeightSum += weights[i] * weights[i];
        }

        double muEffective = 1.0 / squaredWeightSum;
        double cc = (4.0 + muEffective / n) / (n + 4.0 + 2.0 * muEffective / n);
        double cs = (muEffective + 2.0) / (n + muEffective + 5.0);
        double c1 = 2.0 / (StrictMath.pow(n + 1.3, 2.0) + muEffective);
        double cmu = Math.min(
                1.0 - c1, 2.0 * (muEffective - 2.0 + 1.0 / muEffective) / (StrictMath.pow(n + 2.0, 2.0) + muEffective));
        double damping = 1.0 + 2.0 * Math.max(0.0, StrictMath.sqrt((muEffective - 1.0) / (n + 1.0)) - 1.0) + cs;
        double chiN = StrictMath.sqrt(n) * (1.0 - 1.0 / (4.0 * n) + 1.0 / (21.0 * n * n));

        double[] mean = seed.copyWeights();
        CommonFunctions.normalizePolicyVector(mean);
        double[][] covariance = copyMatrix(initialCovariance);
        double[] pathSigma = new double[n];
        double[] pathCovariance = new double[n];
        double sigma = config.initialSigma();
        PredictedPolicySummary best = null;
        int stagnant = 0;

        for (int generation = 0; generation < config.generations(); generation++) {
            EigenSystem eigen = decomposeAndStabilize(covariance);
            PopulationMember[] population = new PopulationMember[lambda];
            ArrayList<PolicyVector> policies = new ArrayList<>(lambda);
            for (int row = 0; row < lambda; row++) {
                double[] z = new double[n];
                for (int i = 0; i < n; i++) {
                    z[i] = random.nextGaussian();
                }
                double[] step = eigen.sqrtMultiply(z);
                double[] vector = new double[n];
                for (int i = 0; i < n; i++) {
                    vector[i] = mean[i] + sigma * step[i];
                }
                CommonFunctions.normalizePolicyVector(vector);
                PolicyVector policy = PolicyVector.of(vector);
                policies.add(policy);
                population[row] = new PopulationMember(policy);
            }

            List<PolicyVector> distinctPolicies = List.copyOf(policies.stream()
                    .collect(java.util.stream.Collectors.toMap(
                            PolicyVector::id,
                            java.util.function.Function.identity(),
                            (first, ignored) -> first,
                            LinkedHashMap::new))
                    .values());
            Map<PolicyId, PredictedPolicySummary> summaries =
                    predictionMap(predictor.predict(distinctPolicies), distinctPolicies);
            for (PopulationMember member : population) {
                member.prediction = summaries.get(member.policy.id());
                output.add(new PredictedCandidate(member.policy, member.prediction, CandidateOrigin.CMA_ES));
            }
            Arrays.sort(
                    population,
                    Comparator.comparing(PopulationMember::prediction, PredictedPolicyComparator.BEST_FIRST));

            if (best == null || PredictedPolicyComparator.BEST_FIRST.compare(population[0].prediction, best) < 0) {
                best = population[0].prediction;
                stagnant = 0;
            } else {
                stagnant++;
            }

            double[] oldMean = mean;
            mean = new double[n];
            for (int parent = 0; parent < mu; parent++) {
                for (int i = 0; i < n; i++) {
                    mean[i] += weights[parent] * population[parent].policy.weight(i);
                }
            }
            CommonFunctions.normalizePolicyVector(mean);

            double[] meanStep = new double[n];
            for (int i = 0; i < n; i++) {
                meanStep[i] = (mean[i] - oldMean[i]) / sigma;
            }
            double[] whitened = eigen.inverseSqrtMultiply(meanStep);
            double pathSigmaScale = StrictMath.sqrt(cs * (2.0 - cs) * muEffective);
            for (int i = 0; i < n; i++) {
                pathSigma[i] = (1.0 - cs) * pathSigma[i] + pathSigmaScale * whitened[i];
            }

            double normalizedPath =
                    norm(pathSigma) / StrictMath.sqrt(1.0 - StrictMath.pow(1.0 - cs, 2.0 * (generation + 1.0)));
            boolean hSigma = normalizedPath / chiN < 1.4 + 2.0 / (n + 1.0);
            double pathCovarianceScale = StrictMath.sqrt(cc * (2.0 - cc) * muEffective);
            for (int i = 0; i < n; i++) {
                pathCovariance[i] = (1.0 - cc) * pathCovariance[i] + (hSigma ? pathCovarianceScale * meanStep[i] : 0.0);
            }

            double oldScale = 1.0 - c1 - cmu + (hSigma ? 0.0 : c1 * cc * (2.0 - cc));
            double[][] nextCovariance = new double[n][n];
            for (int i = 0; i < n; i++) {
                for (int j = 0; j < n; j++) {
                    nextCovariance[i][j] = oldScale * covariance[i][j] + c1 * pathCovariance[i] * pathCovariance[j];
                }
            }
            for (int parent = 0; parent < mu; parent++) {
                double[] parentStep = new double[n];
                for (int i = 0; i < n; i++) {
                    parentStep[i] = (population[parent].policy.weight(i) - oldMean[i]) / sigma;
                }
                for (int i = 0; i < n; i++) {
                    for (int j = 0; j < n; j++) {
                        nextCovariance[i][j] += cmu * weights[parent] * parentStep[i] * parentStep[j];
                    }
                }
            }
            covariance = stabilize(nextCovariance);
            sigma *= StrictMath.exp((cs / damping) * (norm(pathSigma) / chiN - 1.0));
            sigma = Math.max(0.005, Math.min(0.8, sigma));

            if (stagnant >= 4) {
                sigma = Math.min(0.8, sigma * 1.6);
                blendIdentity(covariance, 0.20);
                Arrays.fill(pathSigma, 0.0);
                Arrays.fill(pathCovariance, 0.0);
                stagnant = 0;
            }
        }
    }

    private static Map<PolicyId, PredictedPolicySummary> predictionMap(
            List<PredictedPolicySummary> predictions, List<PolicyVector> policies) {
        TreeMap<PolicyId, PredictedPolicySummary> result = new TreeMap<>();
        for (PredictedPolicySummary prediction : predictions) {
            if (result.put(prediction.policy().id(), prediction) != null) {
                throw new IllegalArgumentException("Predictor returned duplicate policy");
            }
        }
        for (PolicyVector policy : policies) {
            PredictedPolicySummary prediction = result.get(policy.id());
            if (prediction == null || !prediction.policy().bitwiseEquals(policy)) {
                throw new IllegalArgumentException("Predictor did not return the requested curve");
            }
        }
        if (result.size() != policies.stream().map(PolicyVector::id).distinct().count()) {
            throw new IllegalArgumentException("Predictor returned an unexpected policy");
        }
        return result;
    }

    private static List<PolicyVector> diverseSeeds(List<RobustPolicySummary> ranked, int requested) {
        int poolSize = Math.min(ranked.size(), Math.max(requested * 32, 64));
        ArrayList<PolicyVector> selected = new ArrayList<>(requested);
        selected.add(ranked.getFirst().policy());
        while (selected.size() < requested && selected.size() < poolSize) {
            double bestDistance = -1.0;
            PolicyVector best = null;
            for (int candidate = 1; candidate < poolSize; candidate++) {
                PolicyVector vector = ranked.get(candidate).policy();
                if (selected.stream().anyMatch(vector::bitwiseEquals)) {
                    continue;
                }
                double minimum = Double.POSITIVE_INFINITY;
                for (PolicyVector chosen : selected) {
                    minimum = Math.min(minimum, squaredDistance(vector, chosen));
                }
                if (minimum > bestDistance) {
                    bestDistance = minimum;
                    best = vector;
                }
            }
            if (best == null) {
                break;
            }
            selected.add(best);
        }
        return List.copyOf(selected);
    }

    private static double[][] initialCovariance(List<RobustPolicySummary> ranked) {
        int count = Math.min(ranked.size(), Math.max(32, Math.min(512, ranked.size() / 5)));
        double[][] vectors = new double[count][];
        double[] mean = new double[DIMENSIONS];
        for (int row = 0; row < count; row++) {
            vectors[row] = ranked.get(row).policy().copyWeights();
            for (int i = 0; i < DIMENSIONS; i++) {
                mean[i] += vectors[row][i] / count;
            }
        }

        double[][] covariance = new double[DIMENSIONS][DIMENSIONS];
        for (double[] vector : vectors) {
            for (int i = 0; i < DIMENSIONS; i++) {
                double left = vector[i] - mean[i];
                for (int j = 0; j < DIMENSIONS; j++) {
                    covariance[i][j] += left * (vector[j] - mean[j]) / Math.max(1, count - 1);
                }
            }
        }

        double trace = 0.0;
        for (int i = 0; i < DIMENSIONS; i++) {
            trace += covariance[i][i];
        }
        double scale = DIMENSIONS / Math.max(trace, 1.0e-12);
        for (int i = 0; i < DIMENSIONS; i++) {
            for (int j = 0; j < DIMENSIONS; j++) {
                covariance[i][j] *= 0.75 * scale;
            }
            covariance[i][i] += 0.25;
        }
        return stabilize(covariance);
    }

    private static EigenSystem decomposeAndStabilize(double[][] covariance) {
        return decompose(stabilize(covariance));
    }

    private static double[][] stabilize(double[][] matrix) {
        int n = matrix.length;
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                double symmetric = 0.5 * (matrix[i][j] + matrix[j][i]);
                matrix[i][j] = symmetric;
                matrix[j][i] = symmetric;
            }
            matrix[i][i] = Math.max(matrix[i][i], 1.0e-10);
        }
        EigenSystem eigen = decompose(matrix);
        double minimum = Math.max(1.0e-8, eigen.minimumPositive());
        double maximum = minimum * 1.0e6;
        for (int i = 0; i < eigen.values.length; i++) {
            eigen.values[i] = Math.max(minimum, Math.min(eigen.values[i], maximum));
        }
        return eigen.reconstruct();
    }

    private static EigenSystem decompose(double[][] matrix) {
        int n = matrix.length;
        double[][] a = copyMatrix(matrix);
        double[][] vectors = identity(n);
        int iterations = n * n * 20;
        for (int iteration = 0; iteration < iterations; iteration++) {
            int p = 0;
            int q = 1;
            double largest = 0.0;
            for (int i = 0; i < n; i++) {
                for (int j = i + 1; j < n; j++) {
                    double value = StrictMath.abs(a[i][j]);
                    if (value > largest) {
                        largest = value;
                        p = i;
                        q = j;
                    }
                }
            }
            if (largest < 1.0e-12) {
                break;
            }

            double angle = 0.5 * StrictMath.atan2(2.0 * a[p][q], a[q][q] - a[p][p]);
            double cosine = StrictMath.cos(angle);
            double sine = StrictMath.sin(angle);
            for (int i = 0; i < n; i++) {
                if (i != p && i != q) {
                    double aip = a[i][p];
                    double aiq = a[i][q];
                    a[i][p] = a[p][i] = cosine * aip - sine * aiq;
                    a[i][q] = a[q][i] = sine * aip + cosine * aiq;
                }
                double vip = vectors[i][p];
                double viq = vectors[i][q];
                vectors[i][p] = cosine * vip - sine * viq;
                vectors[i][q] = sine * vip + cosine * viq;
            }
            double app = a[p][p];
            double aqq = a[q][q];
            double apq = a[p][q];
            a[p][p] = cosine * cosine * app - 2.0 * sine * cosine * apq + sine * sine * aqq;
            a[q][q] = sine * sine * app + 2.0 * sine * cosine * apq + cosine * cosine * aqq;
            a[p][q] = a[q][p] = 0.0;
        }

        double[] values = new double[n];
        for (int i = 0; i < n; i++) {
            values[i] = a[i][i];
        }
        return new EigenSystem(vectors, values);
    }

    private static void blendIdentity(double[][] covariance, double identityWeight) {
        for (int i = 0; i < covariance.length; i++) {
            for (int j = 0; j < covariance.length; j++) {
                covariance[i][j] *= 1.0 - identityWeight;
            }
            covariance[i][i] += identityWeight;
        }
    }

    private static double norm(double[] vector) {
        double total = 0.0;
        for (double value : vector) {
            total += value * value;
        }
        return StrictMath.sqrt(total);
    }

    private static double squaredDistance(PolicyVector first, PolicyVector second) {
        double total = 0.0;
        for (int i = 0; i < DIMENSIONS; i++) {
            double difference = first.weight(i) - second.weight(i);
            total += difference * difference;
        }
        return total;
    }

    private static double[][] identity(int size) {
        double[][] result = new double[size][size];
        for (int i = 0; i < size; i++) {
            result[i][i] = 1.0;
        }
        return result;
    }

    private static double[][] copyMatrix(double[][] source) {
        double[][] copy = new double[source.length][];
        for (int i = 0; i < source.length; i++) {
            copy[i] = Arrays.copyOf(source[i], source[i].length);
        }
        return copy;
    }

    public List<PredictedCandidate> optimize(
            List<RobustPolicySummary> measuredEligiblePolicies,
            Set<PolicyId> fixedAnchorIds,
            PolicyCurvePredictor predictor,
            CmaEsConfig config,
            long islandSeed) {
        if (!config.enabled()) {
            return List.of();
        }
        List<RobustPolicySummary> ranked = measuredEligiblePolicies.stream()
                .filter(RobustPolicySummary::eligible)
                .filter(summary -> !fixedAnchorIds.contains(summary.policy().id()))
                .sorted(PolicyComparator.BEST_FIRST)
                .toList();
        if (ranked.size() < config.minimumSeedPolicies()) {
            return List.of();
        }

        int islandCount = Math.min(config.islands(), ranked.size());
        List<PolicyVector> seeds = diverseSeeds(ranked, islandCount);
        double[][] initialCovariance = initialCovariance(ranked);
        ArrayList<PredictedCandidate> generated =
                new ArrayList<>(seeds.size() * config.generations() * config.populationSize());
        for (int island = 0; island < seeds.size(); island++) {
            Random random = new Random(SchedulerSeeds.cmaIslandSeed(islandSeed, island));
            runIsland(seeds.get(island), initialCovariance, config, predictor, random, generated);
        }
        return List.copyOf(generated);
    }

    private static final class PopulationMember {
        private final PolicyVector policy;
        private PredictedPolicySummary prediction;

        private PopulationMember(PolicyVector policy) {
            this.policy = policy;
        }

        private PredictedPolicySummary prediction() {
            return prediction;
        }
    }

    private static final class EigenSystem {
        private final double[][] vectors;
        private final double[] values;

        private EigenSystem(double[][] vectors, double[] values) {
            this.vectors = vectors;
            this.values = values;
        }

        private double[] sqrtMultiply(double[] input) {
            double[] output = new double[input.length];
            for (int eigen = 0; eigen < values.length; eigen++) {
                double scaled = StrictMath.sqrt(Math.max(values[eigen], 1.0e-12)) * input[eigen];
                for (int row = 0; row < output.length; row++) {
                    output[row] += vectors[row][eigen] * scaled;
                }
            }
            return output;
        }

        private double[] inverseSqrtMultiply(double[] input) {
            double[] projected = new double[input.length];
            for (int eigen = 0; eigen < values.length; eigen++) {
                double dot = 0.0;
                for (int row = 0; row < input.length; row++) {
                    dot += vectors[row][eigen] * input[row];
                }
                projected[eigen] = dot / StrictMath.sqrt(Math.max(values[eigen], 1.0e-12));
            }
            double[] output = new double[input.length];
            for (int eigen = 0; eigen < values.length; eigen++) {
                for (int row = 0; row < input.length; row++) {
                    output[row] += vectors[row][eigen] * projected[eigen];
                }
            }
            return output;
        }

        private double minimumPositive() {
            double minimum = Double.POSITIVE_INFINITY;
            for (double value : values) {
                if (value > 0.0) {
                    minimum = Math.min(minimum, value);
                }
            }
            return Double.isFinite(minimum) ? minimum : 1.0e-8;
        }

        private double[][] reconstruct() {
            int n = values.length;
            double[][] result = new double[n][n];
            for (int eigen = 0; eigen < n; eigen++) {
                for (int i = 0; i < n; i++) {
                    for (int j = 0; j < n; j++) {
                        result[i][j] += vectors[i][eigen] * values[eigen] * vectors[j][eigen];
                    }
                }
            }
            return result;
        }
    }
}
