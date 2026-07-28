package io.euhedral_execution.training.scheduling;

import io.euhedral_execution.training.data.PolicyRegistry;
import io.euhedral_execution.training.data.PolicyVector;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class BootstrapPolicyCsv {
    public static List<PolicyVector> read(Path path, int expectedPolicyCount) throws IOException {
        List<List<String>> rows = Phase3Csv.read(path);
        if (rows.size() != expectedPolicyCount + 1) {
            throw new IllegalArgumentException("Unexpected bootstrap policy count");
        }
        ArrayList<String> header = new ArrayList<>(List.of("schema_version",
                "bootstrap_position", "policy_id"));
        for (int i = 0; i < PolicyVector.WIDTH; i++) {
            header.add(String.format("weight_%02d_bits", i));
        }
        if (!rows.getFirst().equals(header)) {
            throw new IllegalArgumentException("Invalid bootstrap header");
        }
        PolicyRegistry registry = new PolicyRegistry();
        ArrayList<PolicyVector> policies = new ArrayList<>(expectedPolicyCount);
        for (int row = 1; row < rows.size(); row++) {
            List<String> fields = rows.get(row);
            if (fields.size() != 31 || !fields.get(0).equals("1")
                    || Integer.parseInt(fields.get(1)) != row) {
                throw new IllegalArgumentException("Invalid bootstrap row");
            }
            double[] weights = new double[PolicyVector.WIDTH];
            for (int i = 0; i < weights.length; i++) {
                weights[i] = Double.longBitsToDouble(Long.parseUnsignedLong(fields.get(i + 3), 16));
            }
            PolicyVector policy = registry.register(PolicyVector.of(weights));
            if (!policy.id().canonical().equals(fields.get(2))) {
                throw new IllegalArgumentException("Bootstrap policy ID mismatch");
            }
            policies.add(policy);
        }
        return List.copyOf(policies);
    }

    private BootstrapPolicyCsv() {
    }
}
