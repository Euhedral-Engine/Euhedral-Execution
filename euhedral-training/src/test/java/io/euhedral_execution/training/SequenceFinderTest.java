package io.euhedral_execution.training;

import static org.assertj.core.api.Assertions.assertThat;

import io.euhedral_execution.training.optimization.config.CandidateGenerationConfig;
import io.euhedral_execution.training.optimization.config.CmaEsConfig;
import io.euhedral_execution.training.optimization.data.CandidateGenerationRequest;
import io.euhedral_execution.training.scheduling.fixtures.SchedulingFixtures;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SequenceFinderTest {
    @Test
    void streamsFullScreenTransfersCmaShortfallAndAdvancesCursorDeterministically() {
        CandidateGenerationConfig config = new CandidateGenerationConfig(32, 7,
                new int[]{1, 1, 1, 1, 2, 2, 3, 5, 8, 16},
                8, 7, 1, new CmaEsConfig(false, 1, 1, 8, 0.2, 2));
        var corpus = SchedulingFixtures.corpus(List.of());
        CandidateGenerationRequest request = new CandidateGenerationRequest(1, 16, 4, 3,
                100, 77L, corpus, Set.of(), SchedulingFixtures.predictor(), config);
        var first = SequenceFinder.generate(request);
        var second = SequenceFinder.generate(request);

        assertThat(first).isEqualTo(second);
        assertThat(first.disagreementAudits()).hasSize(3);
        assertThat(first.baseExploration()).hasSize(16);
        assertThat(first.overflowExploration()).hasSize(4);
        assertThat(first.cmaAssigned()).isZero();
        assertThat(first.scoreBandAssigned()).isGreaterThan(0);
        assertThat(first.directSobolAssigned()).isGreaterThan(0);
        assertThat(first.nextSobolCursor()).isGreaterThanOrEqualTo(132);
        assertThat(java.util.stream.Stream.of(first.disagreementAudits(),
                        first.baseExploration(), first.overflowExploration())
                .flatMap(List::stream).map(candidate -> candidate.policy().id()).distinct())
                .hasSize(23);
    }
}
