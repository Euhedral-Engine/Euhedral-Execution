package io.euhedral_execution.benchmarks.cfd.validation;

import com.fasterxml.jackson.databind.JsonNode;
import io.euhedral_execution.benchmarks.cfd.config.CfdConfiguration;
import io.euhedral_execution.benchmarks.cfd.config.ConfigLoader;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/// Driver-only orchestration. Solver execution remains in independently bounded processes.
public final class ValidationRunner {
    public static final String OPENLB_ARCHIVE = "e4d2421b50643482036917c4416145f7d6b495492f3afac64e048212361c8b3e";

    private ValidationRunner() {}

    public record CaseResult(
            String id,
            ValidationSuite.Status status,
            boolean verified,
            String reason,
            List<String> features,
            String artifacts,
            Map<String, Object> evidence) {}

    public record Report(
            int schemaVersion,
            String candidateIdentity,
            String candidateRevision,
            JsonNode referenceIdentity,
            boolean externalNumericsEligible,
            boolean backendEquivalenceVerified,
            List<String> verifiedFeatures,
            List<CaseResult> cases) {}

    public static int command(String[] args, PrintStream out) throws IOException {
        Path suite = null, home = null;
        var seen = new java.util.HashSet<String>();
        for (int i = 1; i < args.length; i += 2) {
            ValidationSuite.require(i + 1 < args.length && seen.add(args[i]), "missing or duplicate validation option");
            switch (args[i]) {
                case "--suite" -> suite = Path.of(args[i + 1]);
                case "--openlb-home" -> home = Path.of(args[i + 1]);
                default -> throw new IllegalArgumentException("unknown validation option: " + args[i]);
            }
        }
        ValidationSuite.require(suite != null, "--suite is required");
        if (home == null && System.getenv("OPENLB_HOME") != null) home = Path.of(System.getenv("OPENLB_HOME"));
        var report = run(suite, home, out);
        return report.externalNumericsEligible() ? 0 : 4;
    }

    public static Report run(Path suitePath, Path home, PrintStream out) throws IOException {
        suitePath = suitePath.toAbsolutePath().normalize();
        var suite = ValidationSuite.load(suitePath);
        Path base = suitePath.getParent();
        Path output = base.resolve(suite.outputDirectory()).normalize();
        Files.createDirectories(output);
        Path run = Files.createTempDirectory(output, "validation-");
        out.println("Validation directory: " + run);
        JsonNode identity = null;
        String unavailable = null;
        if (home == null) unavailable = "OPENLB_HOME/--openlb-home is not configured";
        else {
            home = home.toAbsolutePath().normalize();
            try {
                identity = referenceIdentity(home);
            } catch (IOException | IllegalArgumentException e) {
                unavailable = "reference installation unavailable: " + e.getMessage();
            }
        }
        String candidateIdentity = candidateIdentity();
        var results = new ArrayList<CaseResult>();
        for (var fixture : suite.cases()) {
            Path directory = Files.createDirectory(run.resolve(fixture.id()));
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("fixture", fixture);
            details.put("candidateIdentity", candidateIdentity);
            boolean verified = verifiedEvidence(base, fixture, details);
            CaseResult result;
            if (unavailable != null)
                result = result(fixture, ValidationSuite.Status.UNAVAILABLE, verified, unavailable, directory, details);
            else {
                try {
                    result = execute(
                            base,
                            fixture,
                            home,
                            identity,
                            output,
                            directory,
                            suite.processDeadlineMillis(),
                            verified,
                            details);
                } catch (IOException | IllegalArgumentException e) {
                    result = result(
                            fixture, ValidationSuite.Status.FAILED, verified, e.getMessage(), directory, details);
                }
            }
            results.add(result);
            Files.writeString(directory.resolve("result.json"), ConfigLoader.json(result));
            out.println(fixture.id() + ": " + result.status() + (result.verified() ? "" : " (UNVERIFIED)") + " - "
                    + result.reason());
        }
        var features = new java.util.TreeSet<String>();
        for (var result : results)
            if (result.status() == ValidationSuite.Status.PASSED && result.verified())
                features.addAll(result.features());
        boolean passed = results.stream().allMatch(r -> r.status() == ValidationSuite.Status.PASSED && r.verified());
        var report = new Report(
                1,
                candidateIdentity,
                candidateRevision(),
                identity,
                passed,
                false,
                List.copyOf(features),
                List.copyOf(results));
        Files.writeString(run.resolve("report.json"), ConfigLoader.json(report));
        Files.writeString(run.resolve("report.md"), markdown(report));
        return report;
    }

    private static CaseResult execute(
            Path base,
            ValidationSuite.Case fixture,
            Path home,
            JsonNode identity,
            Path cacheRoot,
            Path directory,
            long deadline,
            boolean verified,
            Map<String, Object> details)
            throws IOException {
        var config = ConfigLoader.load(base.resolve(fixture.config()));
        details.put("configuration", config);
        details.put("candidateMethod", ValidationWorker.method(config));
        details.put(
                "physicalSampleTimes",
                fixture.sampleSteps().stream()
                        .map(t -> t * config.physics().timeStep())
                        .toList());
        ValidationSuite.require(
                fixture.sampleSteps().getLast() == config.steps(), "last sample must equal simulation duration");
        boolean open = config.config().geometry().faces().openAxis() >= 0;
        boolean unresolved = open && fixture.mode() == ValidationSuite.Mode.MATCHED_DISCRETIZATION;
        if (fixture.mode() == ValidationSuite.Mode.PHYSICAL_CASE_CONVERGENCE) {
            unresolved |= fixture.refinementEvidence().size() < 2;
            for (var evidence : fixture.refinementEvidence())
                unresolved |= !Files.isRegularFile(base.resolve(evidence.file()))
                        || !sha(base.resolve(evidence.file())).equals(evidence.sha256());
        }
        if (unresolved) verified = false;
        Path candidate = Files.createDirectory(directory.resolve("candidate"));
        Path fixturePath = directory.resolve("fixture.json");
        Files.writeString(fixturePath, ConfigLoader.json(fixture));
        Path replay = directory.resolve("configuration.json");
        Files.writeString(replay, ConfigLoader.replayJson(config));
        var command = new ArrayList<String>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.add("-Xmx256m");
        command.add("-cp");
        command.add(absoluteClasspath());
        command.add(ValidationWorker.class.getName());
        command.add(fixturePath.toString());
        command.add(replay.toString());
        command.add(candidate.toString());
        var candidateRun = ValidationProcess.run(command, directory, directory.resolve("candidate.log"), deadline);
        details.put("candidateProcess", candidateRun);
        if (!candidateRun.completed() || !Files.exists(candidate.resolve("completed")))
            return result(
                    fixture,
                    ValidationSuite.Status.FAILED,
                    verified,
                    candidateRun.timedOut()
                            ? "candidate process deadline exceeded"
                            : "candidate process failed/incomplete; see candidate.log",
                    directory,
                    details);
        CfdConfiguration referenceConfig = config;
        Path referenceInput = candidate.resolve("input.txt");
        if (fixture.referenceConfig() != null) {
            referenceConfig = ConfigLoader.load(base.resolve(fixture.referenceConfig()));
            String difference = CaseCorrespondence.difference(config, referenceConfig);
            if (difference != null)
                return result(
                        fixture,
                        ValidationSuite.Status.INCOMPATIBLE,
                        false,
                        "physical case inputs differ: " + difference,
                        directory,
                        details);
            var referenceSteps = new ArrayList<Long>();
            for (long step : fixture.sampleSteps()) referenceSteps.add(referenceStep(step, config, referenceConfig));
            var referenceFixture = new ValidationSuite.Case(
                    fixture.id(),
                    fixture.referenceConfig(),
                    fixture.features(),
                    fixture.mode(),
                    fixture.pressureGauge(),
                    referenceSteps,
                    fixture.tolerances(),
                    null,
                    fixture.correspondence(),
                    List.of());
            Path preparation = Files.createDirectory(directory.resolve("reference-preparation"));
            Path referenceFixturePath = preparation.resolve("fixture.json"),
                    referenceReplay = preparation.resolve("configuration.json");
            Files.writeString(referenceFixturePath, ConfigLoader.json(referenceFixture));
            Files.writeString(referenceReplay, ConfigLoader.replayJson(referenceConfig));
            var prepare = new ArrayList<>(command.subList(0, 5));
            prepare.add(referenceFixturePath.toString());
            prepare.add(referenceReplay.toString());
            prepare.add(preparation.toString());
            prepare.add("prepare-only");
            var prepared =
                    ValidationProcess.run(prepare, directory, directory.resolve("reference-preparation.log"), deadline);
            if (!prepared.completed())
                return result(
                        fixture,
                        ValidationSuite.Status.FAILED,
                        false,
                        "reference configuration preparation failed/deadline exceeded",
                        directory,
                        details);
            referenceInput = preparation.resolve("input.txt");
        }
        details.put("referenceConfiguration", referenceConfig);
        details.put("regime", "transient samples; no steady-state convergence claim");
        if (fixture.mode() == ValidationSuite.Mode.MATCHED_DISCRETIZATION
                && !Files.readString(candidate.resolve("input.txt")).equals(Files.readString(referenceInput)))
            return result(
                    fixture,
                    ValidationSuite.Status.INCOMPATIBLE,
                    false,
                    "matched case inputs differ",
                    directory,
                    details);
        if (fixture.sampling() == ValidationSuite.Sampling.DIRECT
                && (!config.config().grid().equals(referenceConfig.config().grid())
                        || config.physics().voxelWidth()
                                != referenceConfig.physics().voxelWidth()))
            return result(
                    fixture,
                    ValidationSuite.Status.INCOMPATIBLE,
                    false,
                    "different grids require explicit TRILINEAR sampling",
                    directory,
                    details);
        String key = sha(
                (Files.readString(referenceInput) + ConfigLoader.json(referenceConfig.meshes()) + identity.toString())
                        .getBytes(StandardCharsets.UTF_8));
        Path cache = cacheRoot.resolve("reference-cache").resolve(key);
        Files.createDirectories(cache.getParent());
        Path reference;
        if (validCache(cache)) {
            reference = cache;
            details.put("referenceReused", true);
            details.put(
                    "referenceRepeatability",
                    "cached artifact manifest was established by two identical reference runs");
        } else {
            reference = Files.createDirectory(directory.resolve("reference"));
            var process = ValidationProcess.run(
                    List.of(home.resolve("cfd-openlb").toString(), referenceInput.toString(), reference.toString()),
                    directory,
                    directory.resolve("reference.log"),
                    deadline);
            details.put("referenceProcess", process);
            if (!process.completed() || !Files.exists(reference.resolve("completed")))
                return result(
                        fixture,
                        ValidationSuite.Status.FAILED,
                        verified,
                        process.timedOut()
                                ? "reference process deadline exceeded"
                                : "reference process failed/incomplete; see reference.log",
                        directory,
                        details);
            Path repeat = Files.createDirectory(directory.resolve("reference-repeat"));
            var repeated = ValidationProcess.run(
                    List.of(home.resolve("cfd-openlb").toString(), referenceInput.toString(), repeat.toString()),
                    directory,
                    directory.resolve("reference-repeat.log"),
                    deadline);
            if (!repeated.completed() || !manifest(reference).equals(manifest(repeat)))
                return result(
                        fixture,
                        ValidationSuite.Status.FAILED,
                        false,
                        "reference repeatability failed",
                        directory,
                        details);
            details.put("referenceReused", false);
            details.put("referenceRepeatability", "identical exported artifacts in two independent processes");
        }
        details.put("referenceArtifacts", reference.toString());
        details.put("referenceCacheKey", key);
        var method = ValidationSuite.JSON.readTree(Files.readString(reference.resolve("method.json")));
        details.put("referenceMethod", method);
        var actual = ValidationSuite.JSON.valueToTree(ValidationWorker.method(referenceConfig));
        for (String field : List.of(
                "stencil", "collision", "tau", "densityReference", "force", "wall", "population", "velocityCorrection"))
            if (!sameValue(actual.get(field), method.get(field)))
                return result(
                        fixture,
                        ValidationSuite.Status.INCOMPATIBLE,
                        false,
                        "actual method mismatch: " + field,
                        directory,
                        details);
        var samples = new ArrayList<Map<String, Object>>();
        boolean passed = true;
        for (long step : fixture.sampleSteps()) {
            double time = step * config.physics().timeStep();
            var a = Snapshot.read(
                    candidate.resolve("snapshot-" + step + ".tsv"),
                    config.config().grid(),
                    config.physics().voxelWidth(),
                    time);
            long refStep = referenceStep(step, config, referenceConfig);
            var b = Snapshot.read(
                    reference.resolve("snapshot-" + refStep + ".tsv"),
                    referenceConfig.config().grid(),
                    referenceConfig.physics().voxelWidth(),
                    time);
            var sampled = fixture.sampling() == ValidationSuite.Sampling.TRILINEAR
                    ? ReferenceSampling.atCandidateCenters(a, b)
                    : null;
            var comparison = FieldComparison.compare(
                    sampled == null ? a : sampled.candidate(),
                    sampled == null ? b : sampled.reference(),
                    fixture.pressureGauge(),
                    fixture.tolerances());
            if (sampled != null && sampled.coverage() < fixture.minimumCoverage()) passed = false;
            var forces = compareForces(
                    candidate.resolve("forces-" + step + ".tsv"),
                    reference.resolve("forces-" + refStep + ".tsv"),
                    fixture);
            var observableErrors = observableErrors(a, b, config, referenceConfig, fixture);
            passed &= observableErrors.values().stream().allMatch(FieldComparison.Metric::passed);
            var drags = drag(candidate, reference, step, refStep, config, fixture);
            passed &= !Boolean.FALSE.equals(drags.get("passed"));
            var analyticalCandidate = AnalyticalChecks.check(config, a, step);
            var analyticalReference = AnalyticalChecks.check(referenceConfig, b, refStep);
            samples.add(Map.of(
                    "coverage",
                    sampled == null
                            ? Map.of("fraction", 1.0, "sampling", "DIRECT")
                            : Map.of(
                                    "fraction",
                                    sampled.coverage(),
                                    "requestedFluid",
                                    sampled.requestedFluid(),
                                    "coveredFluid",
                                    sampled.coveredFluid(),
                                    "sampling",
                                    "TRILINEAR; eight fluid neighbors; no extrapolation"),
                    "step",
                    step,
                    "time",
                    time,
                    "fields",
                    comparison,
                    "forces",
                    forces,
                    "candidateAnalytical",
                    analyticalCandidate,
                    "referenceAnalytical",
                    analyticalReference,
                    "observables",
                    Map.of(
                            "candidate",
                            observables(a, config),
                            "reference",
                            observables(b, referenceConfig),
                            "errors",
                            observableErrors),
                    "drag",
                    drags));
            if (Boolean.FALSE.equals(analyticalCandidate.get("passed"))
                    || Boolean.FALSE.equals(analyticalReference.get("passed"))) passed = false;
            passed &= comparison.passed() && forces.values().stream().allMatch(FieldComparison.Metric::passed);
        }
        details.put("samples", samples);
        var geometry = geometry(reference.resolve("geometry.tsv"), referenceConfig);
        details.put("geometryComparison", geometry);

        details.put(
                "flowObservables",
                open
                        ? "macroscopic face fluxes and pressure drop; discrete boundary-update mass transfer is not interchangeable with these estimates"
                        : "closed/periodic cases: mass; inlet/outlet flux and pressure drop not applicable");
        if (!Files.exists(cache)) {
            Path pending = Files.createTempDirectory(cache.getParent(), ".reference-");
            for (String file : manifest(reference).keySet()) Files.copy(reference.resolve(file), pending.resolve(file));
            Files.writeString(pending.resolve("manifest.json"), ConfigLoader.json(manifest(reference)));
            try {
                Files.move(pending, cache);
            } catch (java.nio.file.FileAlreadyExistsException ignored) {
                /// Concurrent validation already published this identity.
            }
        }
        if (unresolved)
            return result(
                    fixture,
                    ValidationSuite.Status.INCOMPATIBLE,
                    false,
                    "measured field errors retained; open-boundary correspondence or convergence tolerance evidence remains unverified",
                    directory,
                    details);
        return result(
                fixture,
                passed ? ValidationSuite.Status.PASSED : ValidationSuite.Status.FAILED,
                verified,
                passed ? "field and observable limits satisfied" : "field or observable tolerance exceeded",
                directory,
                details);
    }

    static long referenceStep(long step, CfdConfiguration candidate, CfdConfiguration reference) {
        double value =
                step * candidate.physics().timeStep() / reference.physics().timeStep();
        long rounded = Math.round(value);
        ValidationSuite.require(
                Double.isFinite(value)
                        && rounded >= 0
                        && rounded <= reference.steps()
                        && Snapshot.sameTime(
                                step * candidate.physics().timeStep(),
                                rounded * reference.physics().timeStep()),
                "physical sample time does not align to a reference timestep");
        return rounded;
    }

    private static boolean sameValue(JsonNode a, JsonNode b) {
        return a != null
                && b != null
                && (a.isNumber() && b.isNumber() ? Double.compare(a.doubleValue(), b.doubleValue()) == 0 : a.equals(b));
    }

    private static Map<String, Object> geometry(Path path, CfdConfiguration config) throws IOException {
        long mismatch = 0, commonFluid = 0, candidateFluid = 0, referenceFluid = 0, count = 0;
        var shape = config.config().grid();
        boolean[] seen = new boolean[Math.toIntExact(shape.cellCount())];
        try (var reader = Files.newBufferedReader(path)) {
            if (!"x\ty\tz\tmatchedId\tindependentId".equals(reader.readLine()))
                throw new IOException("missing independent geometry header");
            for (String line; (line = reader.readLine()) != null; ) {
                String[] fields = line.split("\t");
                if (fields.length != 5) throw new IOException("invalid geometry row");
                int x = Integer.parseInt(fields[0]), y = Integer.parseInt(fields[1]), z = Integer.parseInt(fields[2]);
                if (x < 0 || x >= shape.nx() || y < 0 || y >= shape.ny() || z < 0 || z >= shape.nz())
                    throw new IOException("invalid geometry coordinate");
                int index = x + shape.nx() * (y + shape.ny() * z);
                if (seen[index]) throw new IOException("duplicate geometry sample");
                seen[index] = true;
                int a = Integer.parseInt(fields[3]), b = Integer.parseInt(fields[4]);
                if (a != b) mismatch++;
                if (a == 0) candidateFluid++;
                if (b == 0) referenceFluid++;
                if (a == 0 && b == 0) commonFluid++;
                count++;
            }
        }
        if (count != shape.cellCount()) throw new IOException("incomplete independent geometry export");
        return Map.of(
                "samples",
                count,
                "classificationStatus",
                mismatch == 0 ? "IDENTICAL" : "DIFFERENT_DISCRETIZATION",
                "classificationMismatches",
                mismatch,
                "commonFluid",
                commonFluid,
                "candidateFluid",
                candidateFluid,
                "independentReferenceFluid",
                referenceFluid,
                "method",
                "OpenLB cell-center indicators versus Java conservative STL surface-intersection voxels; shared wall layers; solver uses matched mask");
    }

    private static Map<String, FieldComparison.Metric> observableErrors(
            Snapshot a, Snapshot b, CfdConfiguration c, CfdConfiguration r, ValidationSuite.Case fixture) {
        var ca = observables(a, c);
        var rb = observables(b, r);
        ValidationSuite.require(ca.keySet().equals(rb.keySet()), "observable definitions differ");
        var result = new TreeMap<String, FieldComparison.Metric>();
        for (String metric : ca.keySet()) {
            ValidationSuite.require(
                    fixture.tolerances().containsKey(metric), "missing observable tolerance: " + metric);
            result.put(
                    metric,
                    FieldComparison.scalar(
                            Math.abs(ca.get(metric) - rb.get(metric)),
                            fixture.tolerances().get(metric)));
        }
        return result;
    }

    private static Map<String, Double> observables(Snapshot s, CfdConfiguration c) {
        double mass = 0, inlet = 0, outlet = 0, pin = 0, pout = 0;
        int nin = 0, nout = 0;
        var boundary = io.euhedral_execution.benchmarks.cfd.solver.OpenBoundaries.resolve(c);
        for (int z = 0; z < s.shape.nz(); z++)
            for (int y = 0; y < s.shape.ny(); y++)
                for (int x = 0; x < s.shape.nx(); x++) {
                    int i = x + s.shape.nx() * (y + s.shape.ny() * z);
                    if (s.ids[i] != 0) continue;
                    mass += s.fields[3][i] * Math.pow(s.spacing, 3);
                    if (boundary != null) {
                        int face = boundary.face(x, y, z, s.shape);
                        if (face >= 0) {
                            double flux = s.fields[3][i]
                                    * s.fields[boundary.axis()][i]
                                    * s.spacing
                                    * s.spacing
                                    * io.euhedral_execution.benchmarks.cfd.solver.OpenBoundaries.inwardSign(
                                            boundary.inletFace());
                            if (boundary.isInlet(face)) {
                                inlet += flux;
                                pin += s.fields[4][i];
                                nin++;
                            } else {
                                outlet += flux;
                                pout += s.fields[4][i];
                                nout++;
                            }
                        }
                    }
                }
        return boundary == null
                ? Map.of("mass", mass)
                : Map.of(
                        "mass",
                        mass,
                        "inletFlux",
                        inlet,
                        "outletFlux",
                        outlet,
                        "pressureDrop",
                        pin / nin - pout / nout);
    }

    private static Map<String, Object> drag(
            Path candidate,
            Path reference,
            long step,
            long referenceStep,
            CfdConfiguration c,
            ValidationSuite.Case fixture)
            throws IOException {
        var ref = c.config().physics().forceReference();
        if (ref == null) return Map.of("applicable", false, "reason", "no drag reference declared");
        var a = forces(candidate.resolve("forces-" + step + ".tsv"));
        var b = forces(reference.resolve("forces-" + referenceStep + ".tsv"));
        var values = new TreeMap<Integer, Object>();
        double denominator = .5 * ref.density() * ref.velocity() * ref.velocity() * ref.area();
        double norm = ref.direction().magnitude();
        for (int id : a.keySet()) {
            double ca = (a.get(id)[0] * ref.direction().x()
                            + a.get(id)[1] * ref.direction().y()
                            + a.get(id)[2] * ref.direction().z())
                    / norm
                    / denominator;
            double cb = (b.get(id)[0] * ref.direction().x()
                            + b.get(id)[1] * ref.direction().y()
                            + b.get(id)[2] * ref.direction().z())
                    / norm
                    / denominator;
            values.put(
                    id,
                    Map.of(
                            "candidate",
                            ca,
                            "reference",
                            cb,
                            "difference",
                            ca - cb,
                            "error",
                            FieldComparison.scalar(
                                    Math.abs(ca - cb), fixture.tolerances().get("drag"))));
        }
        boolean passed = true;
        for (Object value : values.values()) {
            var row = (Map<?, ?>) value;
            passed &= ((FieldComparison.Metric) row.get("error")).passed();
        }
        return Map.of(
                "reference", ref, "coefficients", values, "forceDirection", "force on obstacle", "passed", passed);
    }

    static Map<Integer, FieldComparison.Metric> compareForces(
            Path candidate, Path reference, ValidationSuite.Case fixture) throws IOException {
        var a = forces(candidate);
        var b = forces(reference);
        if (!a.keySet().equals(b.keySet())) throw new IOException("force obstacle IDs differ");
        var result = new TreeMap<Integer, FieldComparison.Metric>();
        for (int id : a.keySet()) {
            double error = 0;
            for (int axis = 0; axis < 3; axis++) error = Math.hypot(error, a.get(id)[axis] - b.get(id)[axis]);
            result.put(id, FieldComparison.scalar(error, fixture.tolerances().get("force")));
        }
        return result;
    }

    static Map<Integer, double[]> forces(Path path) throws IOException {
        var values = new TreeMap<Integer, double[]>();
        try (var reader = Files.newBufferedReader(path)) {
            if (!"id\tfx\tfy\tfz".equals(reader.readLine())) throw new IOException("invalid force header");
            for (String line; (line = reader.readLine()) != null; ) {
                String[] columns = line.split("\t", -1);
                if (columns.length != 4) throw new IOException("invalid force row");
                try {
                    int id = Integer.parseInt(columns[0]);
                    double[] vector = {
                        Double.parseDouble(columns[1]), Double.parseDouble(columns[2]), Double.parseDouble(columns[3])
                    };
                    for (double value : vector)
                        if (!Double.isFinite(value)) throw new NumberFormatException("non-finite force");
                    if (id <= 0 || values.putIfAbsent(id, vector) != null)
                        throw new NumberFormatException("invalid/duplicate obstacle ID");
                } catch (IllegalArgumentException e) {
                    throw new IOException("invalid force export", e);
                }
            }
        }
        return values;
    }

    static JsonNode referenceIdentity(Path home) throws IOException {
        var identity = ValidationSuite.JSON.readTree(Files.readString(home.resolve("identity.json")));
        ValidationSuite.require(
                identity.path("version").asText().equals("1.9.0")
                        && identity.path("archiveSha256").asText().equals(OPENLB_ARCHIVE)
                        && identity.path("protocol").asInt() == 1
                        && identity.path("precision").asText().equals("double"),
                "reference is not pinned OpenLB 1.9.0/double/protocol 1");
        ValidationSuite.require(
                Files.isExecutable(home.resolve("cfd-openlb"))
                        && identity.path("executableSha256").asText().equals(sha(home.resolve("cfd-openlb"))),
                "reference executable hash differs");
        try (var adapter = ValidationRunner.class.getResourceAsStream("/validation/openlb/reference.cpp")) {
            if (adapter == null) throw new IOException("bundled reference adapter is missing");
            ValidationSuite.require(
                    identity.path("adapterSha256").asText().equals(sha(adapter.readAllBytes())),
                    "reference adapter needs rebuilding");
        }
        for (String required : List.of("compiler", "flags", "platform", "parallelMode", "configSha256"))
            ValidationSuite.require(
                    !identity.path(required).asText().isBlank(), "reference identity missing " + required);
        return identity;
    }

    private static boolean verifiedEvidence(Path base, ValidationSuite.Case fixture, Map<String, Object> details)
            throws IOException {
        if (fixture.evidence() == null) {
            details.put("toleranceVerification", "UNVERIFIED: no frozen tolerance evidence");
            return false;
        }
        Path path = base.resolve(fixture.evidence().file());
        if (!Files.isRegularFile(path) || !sha(path).equals(fixture.evidence().sha256())) {
            details.put("toleranceVerification", "UNVERIFIED: evidence absent or hash differs");
            return false;
        }
        var evidence = ValidationSuite.JSON.readTree(Files.readString(path));
        details.put("toleranceEvidence", evidence);
        var qualified = evidence.path("cases").path(fixture.id());
        var configuration = ConfigLoader.load(base.resolve(fixture.config()));
        boolean match = ValidationSuite.JSON.valueToTree(fixture.tolerances()).equals(evidence.path("tolerances"))
                && evidence.path("basis").isTextual()
                && evidence.path("mode").asText().equals(fixture.mode().name())
                && qualified.path("configurationSha256").asText().equals(sha(base.resolve(fixture.config())))
                && qualified
                        .path("sampleSteps")
                        .toString()
                        .equals(ValidationSuite.JSON
                                .valueToTree(fixture.sampleSteps())
                                .toString())
                && qualified.path("features").equals(ValidationSuite.JSON.valueToTree(fixture.features()))
                && qualified
                        .path("pressureGauge")
                        .asText()
                        .equals(fixture.pressureGauge().name())
                && qualified.path("sampling").asText().equals(fixture.sampling().name())
                && qualified.path("minimumCoverage").asDouble(-1) == fixture.minimumCoverage()
                && qualified
                        .path("meshSha256")
                        .equals(ValidationSuite.JSON.valueToTree(configuration.meshes().stream()
                                .map(m -> m.sha256())
                                .toList()))
                && qualified
                        .path("referenceConfigurationSha256")
                        .asText()
                        .equals(
                                fixture.referenceConfig() == null
                                        ? "same"
                                        : sha(base.resolve(fixture.referenceConfig())));
        details.put(
                "toleranceVerification",
                match
                        ? "frozen evidence matches declared limits"
                        : "UNVERIFIED: evidence does not qualify these limits/mode");
        return match;
    }

    static Map<String, String> manifest(Path directory) throws IOException {
        var result = new TreeMap<String, String>();
        try (var paths = Files.list(directory)) {
            for (Path path : paths.sorted().toList()) {
                if (Files.isRegularFile(path) && !path.getFileName().toString().equals("manifest.json"))
                    result.put(path.getFileName().toString(), sha(path));
            }
        }
        return result;
    }

    static boolean validCache(Path directory) throws IOException {
        if (!Files.isRegularFile(directory.resolve("completed"))
                || !Files.isRegularFile(directory.resolve("manifest.json"))) return false;
        return ValidationSuite.JSON
                .valueToTree(manifest(directory))
                .equals(ValidationSuite.JSON.readTree(Files.readString(directory.resolve("manifest.json"))));
    }

    private static CaseResult result(
            ValidationSuite.Case fixture,
            ValidationSuite.Status status,
            boolean verified,
            String reason,
            Path directory,
            Map<String, Object> details) {
        return new CaseResult(
                fixture.id(), status, verified, reason, fixture.features(), directory.toString(), Map.copyOf(details));
    }

    private static String absoluteClasspath() {
        return java.util.Arrays.stream(System.getProperty("java.class.path").split(java.io.File.pathSeparator))
                .map(p -> Path.of(p).toAbsolutePath().toString())
                .collect(java.util.stream.Collectors.joining(java.io.File.pathSeparator));
    }

    private static String candidateRevision() throws IOException {
        try {
            Path location = Path.of(ValidationWorker.class
                    .getProtectionDomain()
                    .getCodeSource()
                    .getLocation()
                    .toURI());
            if (Files.isRegularFile(location)) {
                try (var jar = new java.util.jar.JarFile(location.toFile())) {
                    String revision = jar.getManifest().getMainAttributes().getValue("Cfd-Source-Revision");
                    return revision == null ? "unknown; loaded artifact hash is authoritative" : revision;
                }
            }
        } catch (java.net.URISyntaxException e) {
            throw new IOException(e);
        }
        return "development classes; loaded class hashes include uncommitted changes";
    }

    private static String candidateIdentity() throws IOException {
        /// Hash loaded CFD classes so dirty/uncommitted numerical changes invalidate eligibility too.
        Path location;
        try {
            location = Path.of(ValidationWorker.class
                    .getProtectionDomain()
                    .getCodeSource()
                    .getLocation()
                    .toURI());
        } catch (java.net.URISyntaxException e) {
            throw new IOException(e);
        }
        if (Files.isRegularFile(location)) return "jar-sha256:" + sha(location);
        var hashes = new TreeMap<String, String>();
        try (var paths = Files.walk(location)) {
            for (Path path : paths.filter(Files::isRegularFile).sorted().toList())
                hashes.put(location.relativize(path).toString(), sha(path));
        }
        return "classes-sha256:" + sha(ConfigLoader.json(hashes).getBytes(StandardCharsets.UTF_8));
    }

    public static String sha(Path path) throws IOException {
        try (var input = Files.newInputStream(path)) {
            MessageDigest digest = digest();
            byte[] buffer = new byte[65536];
            for (int n; (n = input.read(buffer)) >= 0; ) digest.update(buffer, 0, n);
            return HexFormat.of().formatHex(digest.digest());
        }
    }

    static String sha(byte[] bytes) {
        return HexFormat.of().formatHex(digest().digest(bytes));
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String markdown(Report report) {
        var text =
                new StringBuilder("# External CFD validation\n\nCandidate: `" + report.candidateIdentity() + "`\n\n");
        text.append("Source revision: `").append(report.candidateRevision()).append("`\n\n");
        text.append("External numerics eligible: ")
                .append(report.externalNumericsEligible())
                .append(". Backend equivalence: not yet checked.\n\n");
        text.append("| Case | Result | Tolerances verified | Detail |\n| --- | --- | --- | --- |\n");
        for (var result : report.cases())
            text.append("| ")
                    .append(result.id())
                    .append(" | ")
                    .append(result.status())
                    .append(" | ")
                    .append(result.verified())
                    .append(" | ")
                    .append(result.reason().replace('|', '/').replace('\n', ' '))
                    .append(" |\n");
        text.append("\nField summary (largest maximum across snapshots; RMS from that snapshot):\n\n");
        text.append("| Case | Metric | RMS error | Maximum error | Limit |\n| --- | --- | --- | --- | --- |\n");
        for (var result : report.cases()) {
            if (!(result.evidence().get("samples") instanceof List<?> snapshots)) continue;
            var maxima = new TreeMap<String, FieldComparison.Metric>();
            for (Object snapshot : snapshots) {
                if (!(snapshot instanceof Map<?, ?> values)
                        || !(values.get("fields") instanceof FieldComparison.Result fields)) continue;
                for (var metric : fields.metrics().entrySet()) {
                    var previous = maxima.get(metric.getKey());
                    if (previous == null || metric.getValue().maxError() > previous.maxError())
                        maxima.put(metric.getKey(), metric.getValue());
                }
            }
            for (var entry : maxima.entrySet())
                text.append("| ")
                        .append(result.id())
                        .append(" | ")
                        .append(entry.getKey())
                        .append(" | ")
                        .append(entry.getValue().rmsError())
                        .append(" | ")
                        .append(entry.getValue().maxError())
                        .append(" | ")
                        .append(entry.getValue().acceptanceLimit())
                        .append(" |\n");
        }
        text.append(
                "\nSee report.json and each case's result.json for actual methods, units, sample times, coverage, errors, limits and artifact paths.\n");
        return text.toString();
    }
}
