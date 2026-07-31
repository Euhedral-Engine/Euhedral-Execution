package io.euhedral_execution.hardware_utils.compatibility.helpers;

import java.nio.file.Path;

public final class TestPaths {

    public static Path projectDirectory() {
        return requiredPath("p0.project.basedir");
    }

    public static Path classesDirectory() {
        return requiredPath("p0.classes.directory");
    }

    public static Path buildDirectory() {
        return requiredPath("p0.build.directory");
    }

    public static Path resource(String name) {
        return projectDirectory().resolve("src/test/resources/compatibility").resolve(name);
    }

    private static Path requiredPath(String property) {
        String value = System.getProperty(property);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("missing test system property " + property);
        }
        return Path.of(value).toAbsolutePath().normalize();
    }

    private TestPaths() {
    }
}
