package io.euhedral_execution.training.importer.currentworkspace;

import java.nio.file.Path;

public record CurrentWorkspaceImportResult(
        Path directory,
        Path policyCatalog,
        Path bootstrapPolicies,
        Path importReport,
        int uniquePolicyCount,
        int bootstrapPolicyCount) {
}
