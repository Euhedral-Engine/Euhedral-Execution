package io.euhedral_execution.hardware_utils.compatibility.helpers;

import java.nio.file.Path;

public final class TestPaths {

    private TestPaths() {}

    public static Path projectDirectory() {
        return requiredPath("project.basedir");
    }

    public static Path classesDirectory() {
        return requiredPath("classes.directory");
    }

    public static Path buildDirectory() {
        return requiredPath("build.directory");
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
}
