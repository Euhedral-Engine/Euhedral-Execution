package io.euhedral_execution.hardware_utils.compatibility;

import io.euhedral_execution.hardware_utils.compatibility.ApiSurface.Entry;
import io.euhedral_execution.hardware_utils.compatibility.helpers.ApiSurfaceReader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class NativeSurface {

    private static final List<String> PRODUCTS = List.of(
            "/bin/linux/glibc/linux_jni_x64.so",
            "/bin/linux/glibc/linux_jni_arm64.so",
            "/bin/linux/musl/linux_jni_x64.so",
            "/bin/linux/musl/linux_jni_arm64.so",
            "/bin/osx/osx_jni_x64.dylib",
            "/bin/osx/osx_jni_arm64.dylib",
            "/bin/windows/windows_jni_x64.dll",
            "/bin/windows/windows_jni_arm64.dll");

    private NativeSurface() {}

    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 2) {
            throw new IllegalArgumentException("usage: NativeSurface <classes-directory> <output>");
        }
        List<Entry> entries = new ArrayList<>();
        PRODUCTS.forEach(product -> entries.add(new Entry("product", product, "aggregate")));
        entries.addAll(ApiSurfaceReader.readNativeDeclarations(Path.of(arguments[0])));
        entries.add(
                new Entry(
                        "native-exception",
                        "N01",
                        "expected=Java_io_euhedral_1execution_hardware_1utils_windows_WindowsAffinity_ntSetTimerResolution;"
                            + "observed=Java_io_euhedral_1execution_hardware_1utils_windows_WindowsTimerResolution_ntSetTimerResolution"));
        entries.add(new Entry(
                "native-exception",
                "N02",
                "expected=Java_io_euhedral_1execution_hardware_1utils_osx_OSXSystemLayout_getSysctlString;"
                        + "observed=Java_io_euhedral_1execution_hardware_1utils_osx_OSXSystemLayout_getSysctlString"
                        + "(JNIEnv*,jclass,jstring,jcharArray)"));
        new ApiSurface(entries).writeCreateNew(Path.of(arguments[1]));
    }
}
