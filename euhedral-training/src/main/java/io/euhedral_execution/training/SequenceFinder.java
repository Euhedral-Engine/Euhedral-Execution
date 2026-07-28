package io.euhedral_execution.training;

import io.euhedral_execution.training.data.PolicyId;
import io.euhedral_execution.training.data.PolicyRegistry;
import io.euhedral_execution.training.data.PolicyVector;
import io.euhedral_execution.training.optimization.*;
import io.euhedral_execution.training.scheduling.HamiltonAllocator;
import io.euhedral_execution.training.utils.CommonFunctions;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.apache.commons.math4.legacy.random.SobolSequenceGenerator;

public final class SequenceFinder {
    private static final int[] MIX_TIE_ORDER = {0, 1, 2};

    public static CandidateGenerationResult generate(CandidateGenerationRequest request) {
        int totalExploration = Math.addExact(request.baseExplorationCount(),
                request.overflowExplorationCount());
        int[] mix = HamiltonAllocator.allocate(totalExploration, new int[]{
                request.config().cmaWeight(), request.config().scoreBandWeight(),
                request.config().directSobolWeight()
        }, MIX_TIE_ORDER);
        Set<PolicyId> excluded = new HashSet<>(request.corpus().policies().keySet());
        excluded.addAll(request.fixedAnchorIds());
        PolicyRegistry registry = new PolicyRegistry();
        request.corpus().policies().values().forEach(registry::register);

        List<PredictedCandidate> cma = new CmaEsOptimizer().optimize(
                request.corpus().eligiblePolicies(), request.fixedAnchorIds(),
                request.predictor(), request.config().cma(), request.schedulerSeed());
        List<PredictedCandidate> screened = screenSobol(request, excluded, registry);
        List<PredictedCandidate> audits = screened.stream()
                .filter(candidate -> !excluded.contains(candidate.policy().id()))
                .sorted((l, r) -> PredictedPolicyComparator.AUDIT_FIRST.compare(
                        l.prediction(), r.prediction()))
                .limit(request.disagreementAuditCount())
                .toList();
        audits.forEach(candidate -> excluded.add(candidate.policy().id()));
        int auditShortfall = request.disagreementAuditCount() - audits.size();

        ArrayList<PredictedCandidate> selected = new ArrayList<>(totalExploration);
        take(cma, excluded, selected, mix[0]);
        int cmaAssigned = selected.size();
        ScoreBandSampler sampler = new ScoreBandSampler(mix[1], request.config().scoreBandWeights(),
                request.schedulerSeed(), request.iteration(), auditShortfall);
        screened.stream().filter(candidate -> !excluded.contains(candidate.policy().id()))
                .map(PredictedCandidate::prediction).forEach(sampler::accept);
        take(sampler.finishPredicted().stream()
                .map(summary -> new PredictedCandidate(summary.policy(), summary,
                        CandidateOrigin.SCORE_BAND)).toList(), excluded, selected,
                totalExploration - selected.size() - mix[2]);
        int scoreBandAssigned = selected.size() - cmaAssigned;

        long cursor = Math.addExact(request.sobolCursor(), request.config().screenRows());
        ArrayList<PolicyVector> directPolicies = new ArrayList<>();
        while (selected.size() + directPolicies.size() < totalExploration) {
            if (cursor > Integer.MAX_VALUE) {
                throw new IllegalStateException("Sobol cursor exhausted");
            }
            PolicyVector policy = sobol((int) cursor++);
            if (registry.register(policy).id().equals(policy.id())
                    && !excluded.contains(policy.id())) {
                excluded.add(policy.id());
                directPolicies.add(policy);
            }
        }
        List<PredictedCandidate> direct = request.predictor().predict(directPolicies).stream()
                .map(summary -> new PredictedCandidate(summary.policy(), summary,
                        CandidateOrigin.DIRECT_SOBOL))
                .toList();
        selected.addAll(direct);
        List<PredictedCandidate> base = selected.subList(0, request.baseExplorationCount());
        List<PredictedCandidate> overflow = selected.subList(request.baseExplorationCount(),
                selected.size());
        return new CandidateGenerationResult(audits, base, overflow, cursor, cmaAssigned,
                scoreBandAssigned, direct.size(), auditShortfall);
    }

    private static List<PredictedCandidate> screenSobol(CandidateGenerationRequest request,
            Set<PolicyId> excluded, PolicyRegistry registry) {
        ArrayList<PolicyVector> policies = new ArrayList<>();
        long end = Math.addExact(request.sobolCursor(), request.config().screenRows());
        for (long cursor = request.sobolCursor(); cursor < end && cursor <= Integer.MAX_VALUE;
                cursor++) {
            PolicyVector policy = sobol((int) cursor);
            registry.register(policy);
            if (!excluded.contains(policy.id())) {
                policies.add(policy);
            }
            if (policies.size() >= request.config().maximumPredictionRows()) {
                break;
            }
        }
        return request.predictor().predict(policies).stream()
                .map(summary -> new PredictedCandidate(summary.policy(), summary,
                        CandidateOrigin.SCORE_BAND))
                .toList();
    }

    private static PolicyVector sobol(int index) {
        SobolSequenceGenerator generator = new SobolSequenceGenerator(PolicyVector.WIDTH);
        generator.skipTo(index);
        double[] vector = generator.get();
        CommonFunctions.normalizeSobolVector(vector);
        return PolicyVector.of(vector);
    }

    private static void take(List<PredictedCandidate> source, Set<PolicyId> excluded,
            List<PredictedCandidate> selected, int limit) {
        for (PredictedCandidate candidate : source) {
            if (selected.size() >= limit && limit >= 0) {
                return;
            }
            if (excluded.add(candidate.policy().id())) {
                selected.add(candidate);
            }
        }
    }

    private SequenceFinder() {
    }
}
