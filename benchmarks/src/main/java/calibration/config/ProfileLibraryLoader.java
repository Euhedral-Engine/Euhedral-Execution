package calibration.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.euhedral_execution.core.config.FragmentDecisionWeights;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.jspecify.annotations.NonNull;

/// Loads and resolves external profile libraries into namespaced profile tables.
public class ProfileLibraryLoader {

    private final ObjectMapper mapper;
    private final Map<File, ProfileLibrary> libraryCache = new HashMap<>();

    public ProfileLibraryLoader(@NonNull ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper cannot be null");
    }

    /// Loads a HarnessConfig from the specified file and resolves all external profile imports.
    public static HarnessConfig loadAndResolve(@NonNull File rootConfigFile, @NonNull ObjectMapper mapper)
            throws Exception {
        Objects.requireNonNull(rootConfigFile, "rootConfigFile cannot be null");
        Objects.requireNonNull(mapper, "mapper cannot be null");
        File canonicalRoot = rootConfigFile.getCanonicalFile();
        HarnessConfig rawConfig = mapper.readValue(canonicalRoot, HarnessConfig.class);
        return new ProfileLibraryLoader(mapper).resolveImports(rawConfig, canonicalRoot);
    }

    /// Resolves all external profile imports declared in rootConfig, using rootConfigFile as base for relative paths.
    public static HarnessConfig loadAndResolve(
            @NonNull HarnessConfig rootConfig, @NonNull File rootConfigFile, @NonNull ObjectMapper mapper) {
        Objects.requireNonNull(rootConfig, "rootConfig cannot be null");
        Objects.requireNonNull(rootConfigFile, "rootConfigFile cannot be null");
        Objects.requireNonNull(mapper, "mapper cannot be null");
        return new ProfileLibraryLoader(mapper).resolveImports(rootConfig, rootConfigFile);
    }

    /// Resolves all external profile imports declared in rootConfig, using rootConfigFileOrBaseDir as reference base.
    public HarnessConfig resolveImports(@NonNull HarnessConfig rootConfig, @NonNull File rootConfigFileOrBaseDir) {
        Objects.requireNonNull(rootConfig, "rootConfig cannot be null");
        Objects.requireNonNull(rootConfigFileOrBaseDir, "rootConfigFileOrBaseDir cannot be null");

        List<ProfileImport> imports = rootConfig.imports();
        if (imports == null || imports.isEmpty()) {
            return rootConfig;
        }

        File canonicalFile;
        try {
            canonicalFile = rootConfigFileOrBaseDir.getCanonicalFile();
        } catch (IOException e) {
            throw new IllegalArgumentException(
                    "Failed to canonicalize base path: " + rootConfigFileOrBaseDir.getPath(), e);
        }

        File importingFile = canonicalFile.isDirectory() ? new File(canonicalFile, "root.json") : canonicalFile;

        Map<String, CalibrationBenchmarkConfig> accumulatedCalProfiles = new HashMap<>();
        Map<String, FragmentDecisionWeights> accumulatedWeightProfiles = new HashMap<>();

        List<File> activeImportChain = new ArrayList<>();
        activeImportChain.add(importingFile);

        for (ProfileImport importDecl : imports) {
            loadLibraryRecursively(
                    importingFile,
                    "",
                    importDecl,
                    activeImportChain,
                    accumulatedCalProfiles,
                    accumulatedWeightProfiles);
        }

        if (rootConfig.calibrationProfiles() != null) {
            for (Map.Entry<String, CalibrationBenchmarkConfig> entry :
                    rootConfig.calibrationProfiles().entrySet()) {
                if (accumulatedCalProfiles.containsKey(entry.getKey())) {
                    throw new IllegalArgumentException("Duplicate calibration profile name: " + entry.getKey());
                }
                accumulatedCalProfiles.put(entry.getKey(), entry.getValue());
            }
        }

        if (rootConfig.decisionWeightProfiles() != null) {
            for (Map.Entry<String, FragmentDecisionWeights> entry :
                    rootConfig.decisionWeightProfiles().entrySet()) {
                if (accumulatedWeightProfiles.containsKey(entry.getKey())) {
                    throw new IllegalArgumentException("Duplicate decision weight profile name: " + entry.getKey());
                }
                accumulatedWeightProfiles.put(entry.getKey(), entry.getValue());
            }
        }

        return new HarnessConfig(
                rootConfig.schemaVersion(),
                rootConfig.id(),
                rootConfig.name(),
                rootConfig.description(),
                rootConfig.labels(),
                rootConfig.imports(),
                rootConfig.runOptions(),
                rootConfig.artifacts(),
                accumulatedCalProfiles.isEmpty() ? null : Map.copyOf(accumulatedCalProfiles),
                accumulatedWeightProfiles.isEmpty() ? null : Map.copyOf(accumulatedWeightProfiles),
                rootConfig.sweeps(),
                rootConfig.searches(),
                rootConfig.trials());
    }

    private void loadLibraryRecursively(
            File importingFile,
            String namespacePrefix,
            ProfileImport importDecl,
            List<File> activeImportChain,
            Map<String, CalibrationBenchmarkConfig> accumulatedCalProfiles,
            Map<String, FragmentDecisionWeights> accumulatedWeightProfiles) {

        String rawPath = importDecl.path();
        String ns = importDecl.namespace();
        File targetFile = new File(rawPath);
        if (!targetFile.isAbsolute()) {
            File baseDir = importingFile.getParentFile() != null ? importingFile.getParentFile() : new File(".");
            targetFile = new File(baseDir, rawPath);
        }

        File canonicalTarget;
        try {
            canonicalTarget = targetFile.getCanonicalFile();
        } catch (IOException e) {
            throw new IllegalArgumentException(
                    "Failed to canonicalize import path '" + rawPath + "' with namespace '" + ns + "' from '"
                            + importingFile.getPath() + "': " + e.getMessage(),
                    e);
        }

        if (!canonicalTarget.exists() || !canonicalTarget.isFile()) {
            throw new IllegalArgumentException(
                    "Imported profile library file not found: path='" + rawPath + "', namespace='" + ns
                            + "', importingConfig='" + importingFile.getPath() + "', resolved to '"
                            + canonicalTarget.getPath() + "'");
        }

        if (activeImportChain.contains(canonicalTarget)) {
            String chain = activeImportChain.stream().map(File::getPath).collect(Collectors.joining(" -> ")) + " -> "
                    + canonicalTarget.getPath();
            throw new IllegalArgumentException("Cyclic profile library import detected: " + chain);
        }

        ProfileLibrary library = libraryCache.get(canonicalTarget);
        if (library == null) {
            try {
                library = mapper.readValue(canonicalTarget, ProfileLibrary.class);
                libraryCache.put(canonicalTarget, library);
            } catch (Exception e) {
                throw new IllegalArgumentException(
                        "Failed to load profile library import '" + rawPath + "' with namespace '" + ns + "' from '"
                                + importingFile.getPath() + "': " + e.getMessage(),
                        e);
            }
        }

        String currentNs = namespacePrefix.isEmpty() ? ns : namespacePrefix + "." + ns;

        if (library.decisionWeightProfiles() != null) {
            for (Map.Entry<String, FragmentDecisionWeights> entry :
                    library.decisionWeightProfiles().entrySet()) {
                String qualifiedName = currentNs + "." + entry.getKey();
                if (accumulatedWeightProfiles.containsKey(qualifiedName)) {
                    throw new IllegalArgumentException(
                            "Duplicate decision weight profile name in namespace: " + qualifiedName);
                }
                accumulatedWeightProfiles.put(qualifiedName, entry.getValue());
            }
        }

        if (library.calibrationProfiles() != null) {
            for (Map.Entry<String, CalibrationBenchmarkConfig> entry :
                    library.calibrationProfiles().entrySet()) {
                String qualifiedName = currentNs + "." + entry.getKey();
                if (accumulatedCalProfiles.containsKey(qualifiedName)) {
                    throw new IllegalArgumentException(
                            "Duplicate calibration profile name in namespace: " + qualifiedName);
                }
                CalibrationBenchmarkConfig profile = entry.getValue();
                if (profile.decisionWeightProfile() != null) {
                    String ref = profile.decisionWeightProfile();
                    String qualifiedRef = currentNs + "." + ref;
                    profile = profile.withDecisionWeightProfile(qualifiedRef);
                }
                accumulatedCalProfiles.put(qualifiedName, profile);
            }
        }

        if (library.imports() != null) {
            activeImportChain.add(canonicalTarget);
            try {
                for (ProfileImport nestedImport : library.imports()) {
                    loadLibraryRecursively(
                            canonicalTarget,
                            currentNs,
                            nestedImport,
                            activeImportChain,
                            accumulatedCalProfiles,
                            accumulatedWeightProfiles);
                }
            } finally {
                activeImportChain.remove(activeImportChain.size() - 1);
            }
        }
    }
}
