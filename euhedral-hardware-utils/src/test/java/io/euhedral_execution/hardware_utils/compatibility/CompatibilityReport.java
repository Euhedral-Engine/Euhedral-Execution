package io.euhedral_execution.hardware_utils.compatibility;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

record CompatibilityReport(boolean moduleSame, List<Difference> removed, List<Difference> changed,
                           List<Difference> added) {

    CompatibilityReport(boolean moduleSame, List<Difference> removed, List<Difference> changed,
            List<Difference> added) {
        Comparator<Difference> order = Comparator.comparing(Difference::key, ApiSurface.UTF8_ORDER);
        this.moduleSame = moduleSame;
        this.removed = removed.stream().sorted(order).toList();
        this.changed = changed.stream().sorted(order).toList();
        this.added = added.stream().sorted(order).toList();
    }

    boolean passes() {
        return this.moduleSame && this.removed.isEmpty() && this.changed.isEmpty();
    }

    String render() {
        StringBuilder output = new StringBuilder();
        output.append("format\t1\n");
        output.append("baseline\t").append(ApiSurface.BASELINE).append('\n');
        output.append("status\t").append(passes() ? "PASS" : "FAIL").append('\n');
        output.append("module\t").append(this.moduleSame ? "SAME" : "CHANGED").append('\n');
        output.append("removed\t").append(this.removed.size()).append('\n');
        output.append("changed\t").append(this.changed.size()).append('\n');
        output.append("added\t").append(this.added.size()).append('\n');
        this.removed.forEach(difference -> output.append("REMOVED\t")
                .append(ApiSurface.escape(difference.key())).append('\t')
                .append(ApiSurface.escape(difference.baselineValue())).append('\n'));
        this.changed.forEach(difference -> output.append("CHANGED\t")
                .append(ApiSurface.escape(difference.key())).append('\t')
                .append(ApiSurface.escape(difference.baselineValue())).append('\t')
                .append(ApiSurface.escape(difference.currentValue())).append('\n'));
        this.added.forEach(difference -> output.append("ADDED\t")
                .append(ApiSurface.escape(difference.key())).append('\t')
                .append(ApiSurface.escape(difference.currentValue())).append('\n'));
        return output.toString();
    }

    void write(Path path) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, render(), StandardCharsets.UTF_8);
    }

    record Difference(String key, String baselineValue, String currentValue) {

    }
}
