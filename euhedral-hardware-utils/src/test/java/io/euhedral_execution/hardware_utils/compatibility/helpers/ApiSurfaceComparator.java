package io.euhedral_execution.hardware_utils.compatibility.helpers;

import io.euhedral_execution.hardware_utils.compatibility.ApiSurface;
import io.euhedral_execution.hardware_utils.compatibility.ApiSurface.Entry;
import io.euhedral_execution.hardware_utils.compatibility.CompatibilityReport;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

public final class ApiSurfaceComparator {

    private ApiSurfaceComparator() {}

    public static CompatibilityReport compare(ApiSurface baseline, ApiSurface current) {
        List<CompatibilityReport.Difference> removed = new ArrayList<>();
        List<CompatibilityReport.Difference> changed = new ArrayList<>();
        List<CompatibilityReport.Difference> added = new ArrayList<>();

        for (Map.Entry<String, Entry> baselineEntry : baseline.entries().entrySet()) {
            Entry currentEntry = current.entries().get(baselineEntry.getKey());
            if (currentEntry == null) {
                removed.add(new CompatibilityReport.Difference(
                        baselineEntry.getKey(), baselineEntry.getValue().value(), null));
            } else if (!baselineEntry.getValue().value().equals(currentEntry.value())) {
                changed.add(new CompatibilityReport.Difference(
                        baselineEntry.getKey(), baselineEntry.getValue().value(), currentEntry.value()));
            }
        }
        for (Map.Entry<String, Entry> currentEntry : current.entries().entrySet()) {
            if (!baseline.entries().containsKey(currentEntry.getKey())) {
                added.add(new CompatibilityReport.Difference(
                        currentEntry.getKey(), null, currentEntry.getValue().value()));
            }
        }

        Set<String> baselineModule = baseline.moduleEntries().stream()
                .map(Entry::line)
                .collect(Collectors.toCollection(() -> new TreeSet<>(ApiSurface.UTF8_ORDER)));
        Set<String> currentModule = current.moduleEntries().stream()
                .map(Entry::line)
                .collect(Collectors.toCollection(() -> new TreeSet<>(ApiSurface.UTF8_ORDER)));
        return new CompatibilityReport(baselineModule.equals(currentModule), removed, changed, added);
    }
}
