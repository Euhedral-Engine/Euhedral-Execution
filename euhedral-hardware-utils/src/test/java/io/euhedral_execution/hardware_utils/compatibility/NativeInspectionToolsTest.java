package io.euhedral_execution.hardware_utils.compatibility;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.euhedral_execution.hardware_utils.compatibility.helpers.NativeInspectionTools;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NativeInspectionToolsTest {
    @TempDir
    Path directory;

    private Path executable(String name) throws Exception {
        Path path = Files.writeString(directory.resolve(name), "test fixture");
        assertTrue(path.toFile().setExecutable(true));
        return path;
    }

    @Test
    void findsVersionedToolsWithoutUnversionedSystemSymlinks() throws Exception {
        Path readobj = executable("llvm-readobj-18");
        Path objdump = executable("llvm-objdump-18");
        assertEquals(readobj, NativeInspectionTools.resolve("", "llvm-readobj", directory.toString(), "LLVM_READOBJ"));
        assertEquals(objdump, NativeInspectionTools.resolve("", "llvm-objdump", directory.toString(), "LLVM_OBJDUMP"));
    }

    @Test
    void findsToolsInsideVersionedLlvmInstallationRoots() throws Exception {
        Path bin = Files.createDirectories(directory.resolve("llvm-18/bin"));
        Path readobj = Files.writeString(bin.resolve("llvm-readobj"), "test fixture");
        assertTrue(readobj.toFile().setExecutable(true));

        assertEquals(readobj, NativeInspectionTools.resolve("", "llvm-readobj", directory.toString(), "LLVM_READOBJ"));
    }

    @Test
    void prefersUnversionedPathEntryAndHonorsExplicitOverride() throws Exception {
        Path versioned = executable("llvm-readobj-18");
        Path unversioned = executable("llvm-readobj");
        assertEquals(
                unversioned, NativeInspectionTools.resolve("", "llvm-readobj", directory.toString(), "LLVM_READOBJ"));
        assertEquals(
                versioned,
                NativeInspectionTools.resolve(
                        versioned.toString(), "llvm-readobj", directory.toString(), "LLVM_READOBJ"));
    }

    @Test
    void invalidOverrideDoesNotSilentlyFallBack() throws Exception {
        executable("llvm-readobj");
        assertThrows(
                IllegalStateException.class,
                () -> NativeInspectionTools.resolve(
                        directory.resolve("missing").toString(), "llvm-readobj", directory.toString(), "LLVM_READOBJ"));
        assertThrows(
                IllegalStateException.class,
                () -> NativeInspectionTools.resolve(
                        "llvm-readobj", "llvm-readobj", directory.toString(), "LLVM_READOBJ"));
    }

    @Test
    void absentToolsRemainAHardFailureWithInstallationGuidance() {
        var error = assertThrows(
                IllegalStateException.class,
                () -> NativeInspectionTools.resolve("", "llvm-readobj", directory.toString(), "LLVM_READOBJ"));
        assertTrue(error.getMessage().contains("install LLVM tools on PATH"));
        assertTrue(error.getMessage().contains("LLVM_READOBJ"));
    }
}
