package io.euhedral_execution.training.packaging;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PackageReportWriterTest {
    @Test
    void reportWriterRemainsAStaticSingleOwnerUtility() {
        assertThat(java.lang.reflect.Modifier.isFinal(
                PackageReportWriter.class.getModifiers())).isTrue();
    }
}
