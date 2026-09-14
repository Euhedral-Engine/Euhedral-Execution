package io.euhedral_execution.benchmarks.cfd.validation;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.euhedral_execution.benchmarks.cfd.config.CfdConfiguration;
import io.euhedral_execution.benchmarks.cfd.config.ConfigLoader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.TreeMap;
import java.util.jar.JarFile;

/// Content identities separate numerical coverage from scheduling variants and output paths.
public final class NumericalIdentity {
    private NumericalIdentity() {}

    public static String current() throws IOException {
        Path location;
        try {
            location = Path.of(NumericalIdentity.class
                    .getProtectionDomain()
                    .getCodeSource()
                    .getLocation()
                    .toURI());
        } catch (java.net.URISyntaxException error) {
            throw new IOException(error);
        }
        var hashes = new TreeMap<String, String>();
        if (Files.isRegularFile(location)) {
            try (var jar = new JarFile(location.toFile())) {
                for (var entry :
                        jar.stream().filter(e -> numerical(e.getName())).toList()) {
                    try (var input = jar.getInputStream(entry)) {
                        hashes.put(entry.getName(), ValidationRunner.sha(input.readAllBytes()));
                    }
                }
            }
        } else {
            try (var paths = Files.walk(location)) {
                for (var path : paths.filter(Files::isRegularFile).toList()) {
                    String name = location.relativize(path).toString().replace('\\', '/');
                    if (numerical(name)) {
                        hashes.put(name, ValidationRunner.sha(path));
                    }
                }
            }
        }
        if (hashes.isEmpty()) {
            throw new IOException("no numerical classes available for validation identity");
        }
        return hash(ConfigLoader.json(hashes));
    }

    private static boolean numerical(String path) {
        String root = "io/euhedral_execution/benchmarks/cfd/";
        for (String area : List.of("config/", "frames/", "geometry/", "solver/")) {
            if (path.startsWith(root + area) && path.endsWith(".class")) {
                return true;
            }
        }
        return false;
    }

    public static String caseIdentity(CfdConfiguration configuration) throws IOException {
        return hash(physicalCase(configuration).toString());
    }

    /// Representative coverage only: unforced lattice-unit shear in a fully periodic, empty domain.
    /// Grid and duration may scale; all other physics, modes and guards must still match.
    public static String periodicShearIdentity(CfdConfiguration configuration) throws IOException {
        var config = configuration.config();
        var geometry = config.geometry();
        if (config.physics().shear() == null
                || config.physics().physical() != null
                || configuration.physics().acceleration().magnitude() != 0
                || !geometry.boxes().isEmpty()
                || !geometry.spheres().isEmpty()
                || !geometry.cylinders().isEmpty()
                || !geometry.meshes().isEmpty()) {
            return null;
        }
        for (int face = 0; face < 6; face++) {
            if (geometry.faces().at(face)
                    != io.euhedral_execution.benchmarks.cfd.config.SimulationConfig.FaceCondition.PERIODIC) {
                return null;
            }
        }
        ObjectNode value = physicalCase(configuration);
        value.remove("grid");
        ((ObjectNode) value.get("execution")).remove("steps");
        return hash(value.toString());
    }

    private static ObjectNode physicalCase(CfdConfiguration configuration) throws IOException {
        ObjectNode value = (ObjectNode) ValidationSuite.JSON.readTree(ConfigLoader.json(configuration.config()));
        value.remove(List.of("output", "memoryLimitBytes"));
        ObjectNode execution = (ObjectNode) value.get("execution");
        execution.remove(List.of(
                "brick",
                "backendOptions",
                "stepDeadlineMillis",
                "geometrySources",
                "geometryDeadlineMillis",
                "durationSeconds",
                "diagnosticsEverySteps"));
        execution.put("steps", configuration.steps());
        var meshes = value.path("geometry").path("meshes");
        for (int i = 0; i < meshes.size(); i++) {
            ((ObjectNode) meshes.get(i))
                    .put("file", configuration.meshes().get(i).sha256());
        }
        return value;
    }

    public static String hash(String value) {
        return ValidationRunner.sha(value.getBytes(StandardCharsets.UTF_8));
    }
}
