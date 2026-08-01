package io.euhedral_execution.hardware_utils.compatibility;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.euhedral_execution.hardware_utils.compatibility.helpers.TestPaths;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

class NativeSigningTest {

    @Test
    void packagesTheVerifiedSignedMacosCopy() throws Exception {
        String rcodesign = System.getProperty("p1.rcodesign");
        assertTrue(rcodesign != null && !rcodesign.isBlank(), "p1.rcodesign is required");
        Path generated = TestPaths.buildDirectory().resolve("generated-resources/native");
        Path classes = TestPaths.classesDirectory();
        Map<String, String> products = Map.of(
                "bin/osx/osx_jni_x64.dylib", "io.euhedral.execution.hardware-utils.osx-jni-x64",
                "bin/osx/osx_jni_arm64.dylib", "io.euhedral.execution.hardware-utils.osx-jni-arm64");

        for (Map.Entry<String, String> product : products.entrySet()) {
            Path staged = generated.resolve(product.getKey());
            Path packaged = classes.resolve(product.getKey());
            assertEquals(FilesSupport.sha256(staged), FilesSupport.sha256(packaged));

            Process process = new ProcessBuilder(rcodesign, "print-signature-info", staged.toString())
                    .redirectErrorStream(true)
                    .start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(0, process.waitFor(), output);
            assertTrue(output.contains("identifier: " + product.getValue()), output);
            assertTrue(output.contains("CodeSignatureFlags(ADHOC | RUNTIME)"), output);
            assertTrue(output.contains("slot: CodeDirectory"), output);
            assertTrue(output.contains("sha256:"), output);
        }
    }
}
