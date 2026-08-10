package io.euhedral_execution.training.scheduling.io;

import io.euhedral_execution.training.data.PolicyRegistry;
import io.euhedral_execution.training.data.PolicyVector;
import io.euhedral_execution.training.data.io.CanonicalCsv;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class BootstrapPolicyCsv {
    private BootstrapPolicyCsv() {}

    public static Path write(Path path, List<PolicyVector> policies) throws IOException {
        if (policies.isEmpty()) {
            throw new IllegalArgumentException("Bootstrap policies are empty");
        }
        Path target = path.toAbsolutePath().normalize();
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("Bootstrap policy target already exists");
        }
        ArrayList<String> header = header();
        StringBuilder output = new StringBuilder(CanonicalCsv.row(header));
        PolicyRegistry registry = new PolicyRegistry();
        for (int index = 0; index < policies.size(); index++) {
            PolicyVector policy = registry.register(policies.get(index));
            ArrayList<String> row = new ArrayList<>(
                    List.of("1", Integer.toString(index + 1), policy.id().canonical()));
            for (double weight : policy.copyWeights()) {
                row.add("%016x".formatted(Double.doubleToRawLongBits(weight)));
            }
            output.append(CanonicalCsv.row(row));
        }
        if (registry.policiesInIdOrder().size() != policies.size()) {
            throw new IllegalArgumentException("Duplicate bootstrap policy");
        }
        Files.createDirectories(target.getParent());
        Path temporary = target.getParent().resolve("." + target.getFileName() + ".tmp-" + UUID.randomUUID());
        try {
            Files.writeString(temporary, output, StandardCharsets.UTF_8);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException error) {
                throw new IOException("Atomic bootstrap policy publication is required", error);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
        return target;
    }

    public static List<PolicyVector> read(Path path, int expectedPolicyCount) throws IOException {
        List<List<String>> rows = CanonicalCsv.read(path);
        if (rows.size() != expectedPolicyCount + 1) {
            throw new IllegalArgumentException("Unexpected bootstrap policy count");
        }
        if (!rows.getFirst().equals(header())) {
            throw new IllegalArgumentException("Invalid bootstrap header");
        }
        PolicyRegistry registry = new PolicyRegistry();
        for (int row = 1; row < rows.size(); row++) {
            List<String> fields = rows.get(row);
            if (fields.size() != 31 || !fields.get(0).equals("1") || Integer.parseInt(fields.get(1)) != row) {
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
        }
        return List.copyOf(registry.policiesInIdOrder());
    }

    private static ArrayList<String> header() {
        ArrayList<String> header = new ArrayList<>(List.of("schema_version", "bootstrap_position", "policy_id"));
        for (int i = 0; i < PolicyVector.WIDTH; i++) {
            header.add("weight_%02d_bits".formatted(i));
        }
        return header;
    }
}
