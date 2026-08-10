package io.euhedral_execution.training.packaging;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TrainingRunPackageValidatorTest {
    @TempDir
    Path temp;

    @Test
    void rejectsUnexpectedPackageInventoryBeforeTrustingMetadata() throws Exception {
        Files.writeString(temp.resolve("unexpected.txt"), "not a package\n");
        assertThatThrownBy(() -> TrainingRunPackageValidator.validate(temp)).isInstanceOf(java.io.IOException.class);
    }
}
