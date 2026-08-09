package io.euhedral_execution.training.learning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class ParallelTrainerTest {

    @Test
    void startsAllMembersConcurrentlyAndRetainsIndexOrder() throws Exception {
        int memberCount = 3;
        CountDownLatch started = new CountDownLatch(memberCount);

        var results = ParallelTrainer.run(
                memberCount,
                index -> {
                    started.countDown();
                    assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
                    return index;
                },
                ignored -> {});

        assertThat(results).containsExactly(0, 1, 2);
    }

    @Test
    void closesSuccessfulMembersWhenAnotherMemberFails() {
        Set<Integer> cleaned = new ConcurrentSkipListSet<>();

        assertThatThrownBy(() -> ParallelTrainer.run(
                        3,
                        index -> {
                            if (index == 1) {
                                throw new IOException("member failed");
                            }
                            return index;
                        },
                        cleaned::add))
                .isInstanceOf(IOException.class)
                .hasMessage("member failed");

        assertThat(cleaned).containsExactly(0, 2);
    }
}
