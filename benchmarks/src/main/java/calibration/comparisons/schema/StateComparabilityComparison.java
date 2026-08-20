package calibration.comparisons.schema;

/// Explicit state-distribution and transition metrics used to classify one policy comparison.
///
/// The classification is an analysis aid. Its tolerances are not production policy constants.
public record StateComparabilityComparison(
        StateComparability classification,
        double baselineProductiveHandleRatio,
        double candidateProductiveHandleRatio,
        double productiveHandleRatioDelta,
        double baselineContentionCentroid,
        double candidateContentionCentroid,
        double contentionCentroidDelta,
        double baselineBodyCentroid,
        double candidateBodyCentroid,
        double bodyCentroidDelta,
        double occupancyTotalVariationDistance,
        int baselineDominantState,
        int candidateDominantState,
        double baselineDominantProbability,
        double candidateDominantProbability,
        double baselineDominantSelfTransitionRate,
        double candidateDominantSelfTransitionRate,
        double dominantSelfTransitionRateDelta,
        double transitionTotalVariationDistance,
        double baselineMaximumOscillation,
        double candidateMaximumOscillation,
        double maximumOscillationDelta,
        double baselineDominantVectorContention,
        double candidateDominantVectorContention,
        double baselineDominantVectorBody,
        double candidateDominantVectorBody,
        double baselineDominantVectorMagnitude,
        double candidateDominantVectorMagnitude) {}
