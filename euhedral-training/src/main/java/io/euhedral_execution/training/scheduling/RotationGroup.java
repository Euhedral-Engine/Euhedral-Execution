package io.euhedral_execution.training.scheduling;

public record RotationGroup(String environmentId, int availablePhysicalCoreCount)
        implements Comparable<RotationGroup> {
    public RotationGroup {
        if (environmentId == null || environmentId.isBlank() || availablePhysicalCoreCount <= 0) {
            throw new IllegalArgumentException("Invalid rotation group");
        }
    }

    public String canonical() {
        return environmentId + "/" + availablePhysicalCoreCount;
    }

    @Override
    public int compareTo(RotationGroup other) {
        int result = environmentId.compareTo(other.environmentId);
        return result != 0 ? result
                : Integer.compare(availablePhysicalCoreCount, other.availablePhysicalCoreCount);
    }
}
