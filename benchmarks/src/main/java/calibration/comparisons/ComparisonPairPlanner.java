package calibration.comparisons;

import calibration.comparisons.schema.CompletedRun;
import calibration.config.ComparisonConfig;
import calibration.config.ComparisonKeyConfig;
import calibration.config.ComparisonStrategy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import org.jspecify.annotations.NonNull;

/// Plans and constructs deterministic ComparisonPair pairings according to configured ComparisonStrategy.
/// This component strictly handles pair selection and does not perform comparison mathematics or statistics.
public final class ComparisonPairPlanner {

    private ComparisonPairPlanner() {}

    /// Plans comparison pairs from the given configuration, baseline completed runs, and candidate completed runs.
    public static @NonNull ComparisonPairPlan plan(
            @NonNull ComparisonConfig config,
            @NonNull List<CompletedRun> baselineRuns,
            @NonNull List<CompletedRun> candidateRuns) {
        Objects.requireNonNull(config, "config must not be null");
        Objects.requireNonNull(baselineRuns, "baselineRuns must not be null");
        Objects.requireNonNull(candidateRuns, "candidateRuns must not be null");

        ComparisonStrategy strategy = config.strategy();

        return switch (strategy) {
            case BASELINE -> planBaseline(baselineRuns, candidateRuns);
            case KEYED -> planKeyed(config.key(), baselineRuns, candidateRuns);
            case CROSS -> planCross(baselineRuns, candidateRuns);
        };
    }

    private static ComparisonPairPlan planBaseline(List<CompletedRun> baselineRuns, List<CompletedRun> candidateRuns) {
        if (baselineRuns.size() != 1) {
            throw new IllegalArgumentException(
                    "BASELINE comparison strategy requires exactly one baseline run, but got " + baselineRuns.size());
        }
        if (candidateRuns.isEmpty()) {
            throw new IllegalArgumentException("BASELINE comparison strategy requires at least one candidate run");
        }

        CompletedRun baseline = baselineRuns.getFirst();
        List<ComparisonPair> pairs = new ArrayList<>(candidateRuns.size());
        int pairIndex = 0;

        for (CompletedRun candidate : candidateRuns) {
            pairs.add(new ComparisonPair(pairIndex++, baseline, candidate, null));
        }

        return ComparisonPairPlan.of(ComparisonStrategy.BASELINE, pairs);
    }

    private static ComparisonPairPlan planKeyed(
            ComparisonKeyConfig keyConfig, List<CompletedRun> baselineRuns, List<CompletedRun> candidateRuns) {
        if (keyConfig == null || keyConfig.paths().isEmpty()) {
            throw new IllegalArgumentException(
                    "KEYED comparison strategy requires a configured comparison key definition with paths");
        }
        if (baselineRuns.isEmpty()) {
            throw new IllegalArgumentException("KEYED comparison strategy requires at least one baseline run");
        }
        if (candidateRuns.isEmpty()) {
            throw new IllegalArgumentException("KEYED comparison strategy requires at least one candidate run");
        }

        Map<ComparisonKey, CompletedRun> baselineMap = new LinkedHashMap<>();
        for (CompletedRun baseRun : baselineRuns) {
            ComparisonKey key = ComparisonKeyExtractor.extract(baseRun, keyConfig);
            CompletedRun existing = baselineMap.putIfAbsent(key, baseRun);
            if (existing != null) {
                throw new IllegalArgumentException("Duplicate baseline comparison key '" + key.format()
                        + "' found for runs '" + existing.identity().trialId() + "' ("
                        + existing.identity().sourcePath() + ") and '"
                        + baseRun.identity().trialId() + "' ("
                        + baseRun.identity().sourcePath() + ")");
            }
        }

        Map<ComparisonKey, CompletedRun> candidateMap = new LinkedHashMap<>();
        for (CompletedRun candRun : candidateRuns) {
            ComparisonKey key = ComparisonKeyExtractor.extract(candRun, keyConfig);
            CompletedRun existing = candidateMap.putIfAbsent(key, candRun);
            if (existing != null) {
                throw new IllegalArgumentException("Duplicate candidate comparison key '" + key.format()
                        + "' found for runs '" + existing.identity().trialId() + "' ("
                        + existing.identity().sourcePath() + ") and '"
                        + candRun.identity().trialId() + "' ("
                        + candRun.identity().sourcePath() + ")");
            }
        }

        TreeSet<ComparisonKey> unmatchedBaseline = new TreeSet<>();
        for (Map.Entry<ComparisonKey, CompletedRun> entry : baselineMap.entrySet()) {
            if (!candidateMap.containsKey(entry.getKey())) {
                if (keyConfig.requireCompleteMatch()) {
                    throw new IllegalArgumentException("Unmatched baseline comparison key '"
                            + entry.getKey().format()
                            + "' for run '" + entry.getValue().identity().trialId() + "' ("
                            + entry.getValue().identity().sourcePath() + ") (no matching candidate run found)");
                }
                unmatchedBaseline.add(entry.getKey());
            }
        }

        TreeSet<ComparisonKey> unmatchedCandidate = new TreeSet<>();
        for (Map.Entry<ComparisonKey, CompletedRun> entry : candidateMap.entrySet()) {
            if (!baselineMap.containsKey(entry.getKey())) {
                if (keyConfig.requireCompleteMatch()) {
                    throw new IllegalArgumentException("Unmatched candidate comparison key '"
                            + entry.getKey().format()
                            + "' for run '" + entry.getValue().identity().trialId() + "' ("
                            + entry.getValue().identity().sourcePath() + ") (no matching baseline run found)");
                }
                unmatchedCandidate.add(entry.getKey());
            }
        }

        TreeSet<ComparisonKey> matchedKeys = new TreeSet<>();
        for (ComparisonKey key : baselineMap.keySet()) {
            if (candidateMap.containsKey(key)) {
                matchedKeys.add(key);
            }
        }

        List<ComparisonPair> pairs = new ArrayList<>(matchedKeys.size());
        int pairIndex = 0;
        for (ComparisonKey key : matchedKeys) {
            CompletedRun base = baselineMap.get(key);
            CompletedRun cand = candidateMap.get(key);
            pairs.add(new ComparisonPair(pairIndex++, base, cand, key));
        }

        return new ComparisonPairPlan(
                ComparisonStrategy.KEYED,
                pairs,
                keyConfig,
                List.copyOf(unmatchedBaseline),
                List.copyOf(unmatchedCandidate));
    }

    private static ComparisonPairPlan planCross(List<CompletedRun> baselineRuns, List<CompletedRun> candidateRuns) {
        if (baselineRuns.isEmpty()) {
            throw new IllegalArgumentException("CROSS comparison strategy requires at least one baseline run");
        }
        if (candidateRuns.isEmpty()) {
            throw new IllegalArgumentException("CROSS comparison strategy requires at least one candidate run");
        }

        List<ComparisonPair> pairs = new ArrayList<>(baselineRuns.size() * candidateRuns.size());
        int pairIndex = 0;

        for (CompletedRun base : baselineRuns) {
            for (CompletedRun cand : candidateRuns) {
                pairs.add(new ComparisonPair(pairIndex++, base, cand, null));
            }
        }

        return ComparisonPairPlan.of(ComparisonStrategy.CROSS, pairs);
    }
}
