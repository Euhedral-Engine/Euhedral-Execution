package io.euhedral_execution.training;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.euhedral_execution.training.data.PolicyVector;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RunnerTest {
    @TempDir
    Path temp;

    @Test
    void importCommandRequiresExactFlagsAndDispatchesExplicitly() throws Exception {
        Path source = temp.resolve("source");
        Path vectors = source.resolve("euhedral-training/output/temp_data");
        Files.createDirectories(vectors.getParent());
        StringBuilder row = new StringBuilder();
        for (int i = 0; i < PolicyVector.WIDTH; i++) {
            if (i != 0) {
                row.append(' ');
            }
            row.append(Double.doubleToRawLongBits(i / 10.0));
        }
        Files.writeString(vectors, row.append('\n'), StandardCharsets.US_ASCII);
        Path output = temp.resolve("import");
        Runner.importCurrentWorkspace(new String[]{"import-current-workspace",
                "--source-root", source.toString(), "--output", output.toString(),
                "--bootstrap-count", "1"});
        assertThat(output.resolve("COMPLETE")).isRegularFile();

        assertThatThrownBy(() -> Runner.importCurrentWorkspace(new String[]{
                "import-current-workspace", "--output", output.toString(),
                "--source-root", source.toString(), "--bootstrap-count", "1"}))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Runner.closedLoop(new String[]{"closed-loop"}))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Runner.packageRun(new String[]{"package-run"}))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
