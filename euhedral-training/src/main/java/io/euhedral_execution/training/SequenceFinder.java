package io.euhedral_execution.training;

import io.euhedral_execution.training.data.PolicyId;
import io.euhedral_execution.training.data.PolicyRegistry;
import io.euhedral_execution.training.data.PolicyVector;
import io.euhedral_execution.training.optimization.CandidateGenerationRequest;
import io.euhedral_execution.training.optimization.CandidateGenerationResult;
import io.euhedral_execution.training.optimization.CandidateOrigin;
import io.euhedral_execution.training.optimization.CmaEsOptimizer;
import io.euhedral_execution.training.optimization.PredictedCandidate;
import io.euhedral_execution.training.optimization.PredictedPolicyComparator;
import io.euhedral_execution.training.optimization.PredictedPolicySummary;
import io.euhedral_execution.training.optimization.ScoreBandSampler;
import io.euhedral_execution.training.scheduling.HamiltonAllocator;
import io.euhedral_execution.training.utils.CommonFunctions;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import org.apache.commons.math4.legacy.random.SobolSequenceGenerator;

public final class SequenceFinder {
    private static final int[] MIX_TIE_ORDER = {0, 1, 2};

    public static CandidateGenerationResult generate(CandidateGenerationRequest request) {
        validateCursorRange(request.sobolCursor(), request.config().screenRows());
        Set<PolicyId> historical = new HashSet<>(request.corpus().policies().keySet());
        historical.addAll(request.fixedAnchorIds());
        PolicyRegistry registry = new PolicyRegistry();
        request.corpus().policies().values().forEach(registry::register);

        List<PredictedCandidate> cmaProposals = new CmaEsOptimizer().optimize(
                request.corpus().eligiblePolicies(), request.fixedAnchorIds(),
                request.predictor(), request.config().cma(),
                io.euhedral_execution.training.optimization.SchedulerSeeds.hash(
                        "phase3-cma-islands-v1\niteration=" + request.iteration() + "\n",
                        request.schedulerSeed()));
        List<PredictedCandidate> cma = cmaProposals.stream().sorted(
                Comparator.comparing(PredictedCandidate::prediction,
                        PredictedPolicyComparator.BEST_FIRST)).toList();

        int provisionalExploration = Math.addExact(request.baseExplorationCount(),
                request.overflowExplorationCount());
        int bandCapacity = Math.addExact(provisionalExploration,
                request.disagreementAuditCount());
        ScoreBandSampler bands = new ScoreBandSampler(bandCapacity,
                request.config().scoreBandWeights(), request.schedulerSeed(),
                request.iteration(), bandCapacity);
        BoundedAuditSelector audits = new BoundedAuditSelector(
                request.disagreementAuditCount());
        for (PredictedCandidate candidate : cmaProposals) {
            if (!historical.contains(candidate.policy().id())) {
                audits.accept(candidate);
                bands.accept(candidate);
            }
        }

        streamSobolScreen(request, historical, registry, candidate -> {
            audits.accept(candidate);
            bands.accept(candidate);
        });

        List<PredictedCandidate> selectedAudits = audits.finish().stream()
                .filter(candidate -> !historical.contains(candidate.policy().id()))
                .limit(request.disagreementAuditCount()).toList();
        Set<PolicyId> excluded = new HashSet<>(historical);
        selectedAudits.forEach(candidate -> excluded.add(candidate.policy().id()));
        int auditShortfall = request.disagreementAuditCount() - selectedAudits.size();
        int overflowCount = Math.addExact(request.overflowExplorationCount(), auditShortfall);

        List<PredictedCandidate> bandCandidates = bands.finish().stream()
                .map(candidate -> new PredictedCandidate(candidate.policy(),
                        candidate.prediction(), CandidateOrigin.SCORE_BAND))
                .toList();
        DirectCursor directCursor = new DirectCursor(Math.addExact(request.sobolCursor(),
                request.config().screenRows()), registry, excluded, request);
        Tranche base = selectTranche(request.baseExplorationCount(), cma, bandCandidates,
                excluded, directCursor, request);
        Tranche overflow = selectTranche(overflowCount, cma, bandCandidates, excluded,
                directCursor, request);
        return new CandidateGenerationResult(selectedAudits, base.candidates(),
                overflow.candidates(), directCursor.cursor(),
                Math.addExact(base.cmaAssigned(), overflow.cmaAssigned()),
                Math.addExact(base.bandAssigned(), overflow.bandAssigned()),
                Math.addExact(base.directAssigned(), overflow.directAssigned()), auditShortfall);
    }

    private static Tranche selectTranche(int count, List<PredictedCandidate> cma,
            List<PredictedCandidate> bands, Set<PolicyId> excluded, DirectCursor direct,
            CandidateGenerationRequest request) {
        int[] requested = mix(count, request);
        ArrayList<PredictedCandidate> selected = new ArrayList<>(count);
        int cmaAssigned = take(cma, excluded, selected, requested[0]);
        int bandTarget = Math.addExact(requested[1], requested[0] - cmaAssigned);
        int bandAssigned = take(bands, excluded, selected, bandTarget);
        int directTarget = count - selected.size();
        List<PredictedCandidate> directCandidates = direct.next(directTarget);
        selected.addAll(directCandidates);
        if (selected.size() != count) {
            throw new IllegalStateException("Candidate generation did not fill tranche");
        }
        return new Tranche(List.copyOf(selected), cmaAssigned, bandAssigned,
                directCandidates.size());
    }

    private static int[] mix(int count, CandidateGenerationRequest request) {
        return HamiltonAllocator.allocate(count, new int[]{request.config().cmaWeight(),
                request.config().scoreBandWeight(), request.config().directSobolWeight()},
                MIX_TIE_ORDER);
    }

    private static void streamSobolScreen(CandidateGenerationRequest request,
            Set<PolicyId> historical, PolicyRegistry registry,
            java.util.function.Consumer<PredictedCandidate> consumer) {
        long exclusiveEnd = Math.addExact(request.sobolCursor(),
                request.config().screenRows());
        ArrayList<PolicyVector> batch = new ArrayList<>(
                request.config().maximumPredictionRows());
        for (long cursor = request.sobolCursor(); cursor < exclusiveEnd; cursor++) {
            PolicyVector policy = sobol(Math.toIntExact(cursor));
            if (!historical.contains(policy.id())) {
                batch.add(policy);
            }
            if (batch.size() == request.config().maximumPredictionRows()) {
                predictBatch(request, batch, consumer);
                batch.clear();
            }
        }
        if (!batch.isEmpty()) {
            predictBatch(request, batch, consumer);
        }
    }

    private static void predictBatch(CandidateGenerationRequest request,
            List<PolicyVector> policies,
            java.util.function.Consumer<PredictedCandidate> consumer) {
        for (PredictedPolicySummary summary : request.predictor().predict(
                List.copyOf(policies))) {
            consumer.accept(new PredictedCandidate(summary.policy(), summary,
                    CandidateOrigin.SCORE_BAND));
        }
    }

    private static int take(List<PredictedCandidate> source, Set<PolicyId> excluded,
            List<PredictedCandidate> destination, int count) {
        int start = destination.size();
        for (PredictedCandidate candidate : source) {
            if (destination.size() - start == count) {
                break;
            }
            if (excluded.add(candidate.policy().id())) {
                destination.add(candidate);
            }
        }
        return destination.size() - start;
    }

    private static PolicyVector sobol(int index) {
        SobolSequenceGenerator generator = new SobolSequenceGenerator(PolicyVector.WIDTH);
        generator.skipTo(index);
        double[] vector = generator.get();
        CommonFunctions.normalizeSobolVector(vector);
        return PolicyVector.of(vector);
    }

    private static void validateCursorRange(long start, int count) {
        if (start < 0 || count <= 0 || Math.addExact(start, count) > Integer.MAX_VALUE) {
            throw new IllegalStateException("Sobol cursor exhausted");
        }
    }

    private record Tranche(List<PredictedCandidate> candidates, int cmaAssigned,
            int bandAssigned, int directAssigned) {
    }

    private static final class DirectCursor {
        private long cursor;
        private final PolicyRegistry registry;
        private final Set<PolicyId> excluded;
        private final CandidateGenerationRequest request;

        private DirectCursor(long cursor, PolicyRegistry registry, Set<PolicyId> excluded,
                CandidateGenerationRequest request) {
            this.cursor = cursor;
            this.registry = registry;
            this.excluded = excluded;
            this.request = request;
        }

        private List<PredictedCandidate> next(int count) {
            ArrayList<PolicyVector> policies = new ArrayList<>(count);
            while (policies.size() < count) {
                if (cursor > Integer.MAX_VALUE) {
                    throw new IllegalStateException("Sobol cursor exhausted");
                }
                PolicyVector policy = sobol(Math.toIntExact(cursor++));
                registry.register(policy);
                if (excluded.add(policy.id())) {
                    policies.add(policy);
                }
            }
            if (policies.isEmpty()) {
                return List.of();
            }
            return request.predictor().predict(policies).stream()
                    .map(summary -> new PredictedCandidate(summary.policy(), summary,
                            CandidateOrigin.DIRECT_SOBOL))
                    .toList();
        }

        private long cursor() {
            return cursor;
        }
    }

    private static final class BoundedAuditSelector {
        private final int capacity;
        private final LinkedHashMap<PolicyId, PredictedCandidate> distinct =
                new LinkedHashMap<>();

        private BoundedAuditSelector(int capacity) {
            this.capacity = capacity;
        }

        private void accept(PredictedCandidate candidate) {
            if (capacity == 0 || distinct.putIfAbsent(candidate.policy().id(), candidate)
                    != null) {
                return;
            }
            if (distinct.size() > capacity) {
                PredictedCandidate worst = distinct.values().stream()
                        .max((left, right) -> PredictedPolicyComparator.AUDIT_FIRST.compare(
                                left.prediction(), right.prediction())).orElseThrow();
                distinct.remove(worst.policy().id());
            }
        }

        private List<PredictedCandidate> finish() {
            return distinct.values().stream()
                    .sorted((left, right) -> PredictedPolicyComparator.AUDIT_FIRST.compare(
                            left.prediction(), right.prediction()))
                    .toList();
        }
    }

    private SequenceFinder() {
    }
}
