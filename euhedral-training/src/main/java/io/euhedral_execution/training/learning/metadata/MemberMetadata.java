package io.euhedral_execution.training.learning.metadata;

import java.util.Objects;

public record MemberMetadata(int index, long seed, int bestEpoch, String relativePath,
                             String sha256) implements Comparable<MemberMetadata> {

    public static String expectedPath(int index) {
        if (index < 0) {
            throw new IllegalArgumentException("Negative member index");
        }
        return "members/member-%03d/euhedral-scenario-ordinal-0000.params".formatted(index);
    }

    public MemberMetadata {
        Objects.requireNonNull(relativePath);
        Objects.requireNonNull(sha256);
        if (index < 0 || bestEpoch < 0
                || !relativePath.equals(expectedPath(index))
                || !sha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Invalid member metadata");
        }
    }

    @Override
    public int compareTo(MemberMetadata other) {
        return Integer.compare(index, other.index);
    }
}
