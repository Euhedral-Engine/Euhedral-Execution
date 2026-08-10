package io.euhedral_execution.hardware_utils.internal;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileOwnerAttributeView;
import java.nio.file.attribute.UserPrincipal;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongPredicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;

final class NativeLibraryExtractor {

    static final String EXTRACT_DIRECTORY_PROPERTY = "io.euhedral.native.extract.dir";
    static final long MAX_LIBRARY_BYTES = 67_108_864L;
    private static final long STALE_AGE_MILLIS = 24L * 60L * 60L * 1_000L;
    private static final int MAX_STALE_ENTRIES = 64;
    private static final int COPY_BUFFER_SIZE = 64 * 1_024;
    private static final Pattern RUN_DIRECTORY = Pattern.compile("load-([1-9][0-9]*)-[0-9a-f]{32}");
    private static final Pattern LIBRARY_NAME =
            Pattern.compile("(?:linux|osx|windows)_jni_(?:x64|arm64)\\.(?:so|dylib|dll)");
    private final String operatingSystem;
    private final Path parent;
    private final NativeFileSecurity security;
    private final ResourceInput resources;
    private final Clock clock;
    private final LongPredicate processAlive;
    private final SecureRandom random;
    private final Logger logger;
    private final long processId;
    private final AtomicBoolean hookRegistered = new AtomicBoolean();
    private Path baseDirectory;
    private Path runDirectory;
    private Path marker;
    private Path retainedLibrary;

    NativeLibraryExtractor(
            String operatingSystem,
            Path parent,
            NativeFileSecurity security,
            ResourceInput resources,
            Clock clock,
            LongPredicate processAlive,
            SecureRandom random,
            long processId,
            Logger logger) {
        this.operatingSystem = Objects.requireNonNull(operatingSystem, "operatingSystem");
        this.parent = Objects.requireNonNull(parent, "parent").toAbsolutePath().normalize();
        this.security = Objects.requireNonNull(security, "security");
        this.resources = Objects.requireNonNull(resources, "resources");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.processAlive = Objects.requireNonNull(processAlive, "processAlive");
        this.random = Objects.requireNonNull(random, "random");
        this.processId = processId;
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    static NativeLibraryExtractor create(String canonicalOperatingSystem, Logger logger) throws IOException {
        return new NativeLibraryExtractor(
                canonicalOperatingSystem,
                resolveParent(),
                NativeFileSecurity.forOperatingSystem(canonicalOperatingSystem),
                path -> JNIClassLoader.class.getResourceAsStream(path),
                Clock.systemUTC(),
                pid -> ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false),
                new SecureRandom(),
                ProcessHandle.current().pid(),
                logger);
    }

    private static Path resolveParent() throws IOException {
        String configured = System.getProperty(EXTRACT_DIRECTORY_PROPERTY);
        String value = configured == null ? System.getProperty("java.io.tmpdir") : configured;
        if (value == null || value.isBlank()) {
            throw new IOException("native-loader: no extraction parent is configured");
        }
        Path path = Path.of(value);
        if (configured != null && !path.isAbsolute()) {
            throw new IOException("native-loader: " + EXTRACT_DIRECTORY_PROPERTY + " must be absolute");
        }
        return path.toAbsolutePath().normalize();
    }

    private static Marker parseMarker(String contents) throws IOException {
        String[] lines = contents.split("\n", -1);
        if (lines.length != 4
                || !"schema=1".equals(lines[0])
                || !lines[1].startsWith("pid=")
                || !lines[2].startsWith("createdEpochMillis=")
                || !lines[3].isEmpty()) {
            throw new IOException("native-loader: invalid owner marker");
        }
        try {
            return new Marker(
                    Long.parseLong(lines[1].substring("pid=".length())),
                    Long.parseLong(lines[2].substring("createdEpochMillis=".length())));
        } catch (NumberFormatException e) {
            throw new IOException("native-loader: invalid owner marker number", e);
        }
    }

    private static long age(long now, long created) {
        if (now < created) {
            return -1;
        }
        try {
            return Math.subtractExact(now, created);
        } catch (ArithmeticException ignored) {
            return Long.MAX_VALUE;
        }
    }

    private static boolean sameOwner(Path child, Path base) throws IOException {
        FileOwnerAttributeView childView =
                Files.getFileAttributeView(child, FileOwnerAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        FileOwnerAttributeView baseView =
                Files.getFileAttributeView(base, FileOwnerAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (childView == null || baseView == null) {
            return true;
        }
        UserPrincipal childOwner = childView.getOwner();
        return childOwner.equals(baseView.getOwner());
    }

    Path prepare(NativeProduct firstProduct) throws IOException {
        validateParent();
        baseDirectory = parent.resolve("euhedral-native-v1");
        createOrValidateDirectory(baseDirectory);
        security.secureDirectory(baseDirectory);
        cleanupStaleDirectories();
        createRunDirectory();
        registerShutdownHook();
        return pathFor(firstProduct);
    }

    Path pathFor(NativeProduct product) throws IOException {
        if (runDirectory == null) {
            return prepare(product);
        }
        if (!LIBRARY_NAME.matcher(product.filename()).matches()) {
            throw new IOException("native-loader: invalid native filename for " + product.id());
        }
        return runDirectory.resolve(product.filename());
    }

    Path extract(NativeProduct product) throws IOException {
        Path destination = pathFor(product);
        if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("native-loader: extraction path already exists: " + destination);
        }
        long count = 0;
        try (InputStream input = resources.open(product.resourcePath())) {
            if (input == null) {
                throw new IOException("native-loader: missing product resource " + product.resourcePath());
            }
            try (var output =
                    Files.newOutputStream(destination, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                byte[] buffer = new byte[COPY_BUFFER_SIZE];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    if (read == 0) {
                        continue;
                    }
                    if (count > MAX_LIBRARY_BYTES - read) {
                        throw new IOException(
                                "native-loader: product exceeds " + MAX_LIBRARY_BYTES + " bytes: " + product.id());
                    }
                    output.write(buffer, 0, read);
                    count += read;
                }
            }
            if (count == 0) {
                throw new IOException("native-loader: empty product resource " + product.resourcePath());
            }
            if (Files.size(destination) != count) {
                throw new IOException("native-loader: extracted size mismatch for " + product.id());
            }
            security.secureLibrary(destination);
            retainedLibrary = destination;
            return destination;
        } catch (IOException | SecurityException e) {
            deleteCandidate(destination);
            throw e;
        }
    }

    void candidateFailed(Path library) {
        deleteCandidate(library);
    }

    void loadSucceeded(Path library) {
        retainedLibrary = library;
        if (!"windows".equals(operatingSystem)) {
            try {
                Files.deleteIfExists(library);
                Files.deleteIfExists(marker);
                Files.deleteIfExists(runDirectory);
                retainedLibrary = null;
                marker = null;
                runDirectory = null;
            } catch (IOException | SecurityException e) {
                logger.debug("native-loader: immediate extraction cleanup failed for {}", library, e);
            }
        }
    }

    void allCandidatesFailed() {
        cleanupOwnedRun();
    }

    private void validateParent() throws IOException {
        if (!parent.isAbsolute()
                || Files.isSymbolicLink(parent)
                || !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)
                || !Files.isWritable(parent)) {
            throw new IOException(
                    "native-loader: extraction parent must be an absolute existing writable directory: " + parent);
        }
    }

    private void createOrValidateDirectory(Path directory) throws IOException {
        try {
            Files.createDirectory(directory);
        } catch (FileAlreadyExistsException ignored) {
            if (Files.isSymbolicLink(directory) || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("native-loader: extraction directory is not a safe directory: " + directory);
            }
        }
    }

    private void createRunDirectory() throws IOException {
        for (int attempt = 0; attempt < 16; attempt++) {
            byte[] randomBytes = new byte[16];
            random.nextBytes(randomBytes);
            StringBuilder suffix = new StringBuilder(32);
            for (byte value : randomBytes) {
                suffix.append(String.format(Locale.ROOT, "%02x", Byte.toUnsignedInt(value)));
            }
            Path candidate = baseDirectory.resolve("load-" + processId + '-' + suffix);
            try {
                Files.createDirectory(candidate);
                runDirectory = candidate;
                security.secureDirectory(runDirectory);
                marker = runDirectory.resolve("owner.properties");
                String contents = "schema=1\npid=" + processId + "\ncreatedEpochMillis=" + clock.millis() + "\n";
                Files.writeString(
                        marker,
                        contents,
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE_NEW,
                        StandardOpenOption.WRITE);
                security.secureMarker(marker);
                return;
            } catch (FileAlreadyExistsException ignored) {
                // Try another cryptographically random name.
            } catch (IOException | SecurityException e) {
                cleanupOwnedRun();
                throw e;
            }
        }
        throw new IOException("native-loader: could not allocate a unique extraction directory");
    }

    private void registerShutdownHook() throws IOException {
        if (!hookRegistered.compareAndSet(false, true)) {
            return;
        }
        try {
            Runtime.getRuntime().addShutdownHook(new Thread(this::cleanupOwnedRun, "euhedral-native-cleanup"));
        } catch (IllegalStateException | SecurityException e) {
            hookRegistered.set(false);
            throw new IOException("native-loader: could not register extraction cleanup", e);
        }
    }

    private void cleanupStaleDirectories() {
        List<Path> children = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(baseDirectory)) {
            for (Path child : stream) {
                children.add(child);
            }
        } catch (IOException | SecurityException e) {
            logger.debug("native-loader: stale cleanup inventory failed for {}", baseDirectory, e);
            return;
        }
        children.sort(Comparator.comparing(path -> path.getFileName().toString()));
        children.stream().limit(MAX_STALE_ENTRIES).forEach(this::cleanupStaleDirectory);
    }

    private void cleanupStaleDirectory(Path directory) {
        try {
            if (Files.isSymbolicLink(directory) || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
                return;
            }
            Matcher matcher = RUN_DIRECTORY.matcher(directory.getFileName().toString());
            if (!matcher.matches()) {
                return;
            }
            long directoryPid = Long.parseLong(matcher.group(1));
            Path staleMarker = directory.resolve("owner.properties");
            if (Files.isSymbolicLink(staleMarker)
                    || !Files.isRegularFile(staleMarker, LinkOption.NOFOLLOW_LINKS)
                    || Files.size(staleMarker) > 4_096) {
                return;
            }
            Marker parsed = parseMarker(Files.readString(staleMarker, StandardCharsets.UTF_8));
            if (parsed.pid() != directoryPid
                    || parsed.createdEpochMillis() < 0
                    || age(clock.millis(), parsed.createdEpochMillis()) < STALE_AGE_MILLIS
                    || processAlive.test(directoryPid)
                    || !sameOwner(directory, baseDirectory)) {
                return;
            }
            List<Path> entries;
            try (var paths = Files.list(directory)) {
                entries = paths.sorted(
                                Comparator.comparing(path -> path.getFileName().toString()))
                        .toList();
            }
            Path library = null;
            for (Path entry : entries) {
                if (entry.equals(staleMarker)) {
                    continue;
                }
                if (library != null
                        || Files.isSymbolicLink(entry)
                        || !Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)
                        || !LIBRARY_NAME.matcher(entry.getFileName().toString()).matches()
                        || Files.size(entry) > MAX_LIBRARY_BYTES
                        || !sameOwner(entry, baseDirectory)) {
                    return;
                }
                library = entry;
            }
            if (library != null) {
                Files.delete(library);
            }
            Files.delete(staleMarker);
            Files.delete(directory);
        } catch (IOException | SecurityException | NumberFormatException e) {
            logger.debug("native-loader: skipped unsafe stale extraction {}", directory, e);
        }
    }

    private void deleteCandidate(Path library) {
        if (library == null || !library.getParent().equals(runDirectory)) {
            return;
        }
        try {
            Files.deleteIfExists(library);
            if (library.equals(retainedLibrary)) {
                retainedLibrary = null;
            }
        } catch (IOException | SecurityException e) {
            logger.debug("native-loader: candidate cleanup failed for {}", library, e);
        }
    }

    private void cleanupOwnedRun() {
        Path ownedRun = runDirectory;
        if (ownedRun == null
                || !ownedRun.getParent().equals(baseDirectory)
                || !RUN_DIRECTORY.matcher(ownedRun.getFileName().toString()).matches()) {
            return;
        }
        try {
            if (retainedLibrary != null && retainedLibrary.getParent().equals(ownedRun)) {
                Files.deleteIfExists(retainedLibrary);
            }
            if (marker != null && marker.getParent().equals(ownedRun)) {
                Files.deleteIfExists(marker);
            }
            Files.deleteIfExists(ownedRun);
        } catch (IOException | SecurityException e) {
            logger.debug("native-loader: shutdown cleanup failed for {}", ownedRun, e);
        }
    }

    @FunctionalInterface
    interface ResourceInput {

        InputStream open(String resourcePath) throws IOException;
    }

    private record Marker(long pid, long createdEpochMillis) {}
}
