package io.euhedral_execution.training.importer.currentworkspace;

record CurrentWorkspaceImportReportRow(
        String relativePath,
        CurrentWorkspaceSemanticType semanticType,
        CurrentWorkspaceImportStatus status,
        long recordCount,
        long acceptedCount,
        long duplicateCount,
        long rejectedCount,
        String reason) {
}
