package io.euhedral_execution.hardware_utils.internal.topology;

public final class TopologyValidationException extends IllegalArgumentException {

    private final String providerName;
    private final String category;
    private final String offendingValue;

    public TopologyValidationException(String providerName, String category, String offendingValue,
            String reason) {
        super("topology provider=" + providerName + " category=" + category + " value="
                + offendingValue + ": " + reason);
        this.providerName = providerName;
        this.category = category;
        this.offendingValue = offendingValue;
    }

    public String providerName() {
        return providerName;
    }

    public String category() {
        return category;
    }

    public String offendingValue() {
        return offendingValue;
    }
}
