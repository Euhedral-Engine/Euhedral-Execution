package io.euhedral_execution.hardware_utils.nativelib;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

class NativeLoaderTest {

    private static final String CATALOG = """
        schema\t1
        os\texact\tlinux\tlinux
        os\texact\tmac os x\tmacos
        os\tprefix\twindows\twindows
        arch\tamd64\tx64
        arch\tx86_64\tx64
        arch\taarch64\tarm64
        product\tlinux-glibc-x64\tlinux\tx64\tglibc\t10\t/bin/linux/glibc/linux_jni_x64.so
        product\tlinux-musl-x64\tlinux\tx64\tmusl\t20\t/bin/linux/musl/linux_jni_x64.so
        product\tmacos-arm64\tmacos\tarm64\tnone\t10\t/bin/osx/osx_jni_arm64.dylib
        product\twindows-x64\twindows\tx64\tnone\t10\t/bin/windows/windows_jni_x64.dll
        """;

    private static final String USER = "tester";

    @TempDir
    Path temporaryDirectory;

    private static NativeProduct product() {
        return new NativeProduct("linux-glibc-x64", "linux", "x64", "glibc", 10, "/bin/linux/glibc/linux_jni_x64.so");
    }

    private static Path staleRun(
            Path base, long pid, String suffix, long created, String markerName, boolean unexpected) throws Exception {
        Path run = base.resolve("load-" + pid + '-' + suffix);
        Files.createDirectory(run);
        String marker = "schema=1\npid=" + pid + "\ncreatedEpochMillis=" + created + "\n";
        Files.writeString(run.resolve(markerName), marker);
        if (unexpected) {
            Files.writeString(run.resolve("unexpected.txt"), "keep");
        }
        return run;
    }

    private static org.slf4j.Logger logger() {
        return LoggerFactory.getLogger(NativeLoaderTest.class);
    }

    @Test
    void appliesOnlyCatalogAliasesAndOrdersFallbacks() throws Exception {
        NativeProductCatalog catalog = catalog();
        assertEquals(
                List.of("linux-glibc-x64", "linux-musl-x64"),
                catalog.select("  LINUX  ", "AMD64").stream()
                        .map(NativeProduct::id)
                        .toList());
        assertEquals(
                "macos-arm64",
                catalog.select(" Mac\t OS   X ", "AARCH64").get(0).id());
        assertEquals(
                "windows-x64", catalog.select("Windows 11", "x86_64").get(0).id());

        IllegalArgumentException unknown =
                assertThrows(IllegalArgumentException.class, () -> catalog.select("Linux", "sparc"));
        assertTrue(unknown.getMessage().contains("sparc"));
        assertTrue(unknown.getMessage().contains("aarch64"));
        assertFalse(unknown.getMessage().contains("arm64"));
    }

    @Test
    void parserRejectsUnknownRowsDuplicatesAndMissingResources() {
        assertThrows(
                IOException.class,
                () -> NativeProductCatalog.parse(CATALOG.replace("schema\t1", "future\t1"), path -> true));
        assertThrows(
                IOException.class,
                () -> NativeProductCatalog.parse(
                        CATALOG.replace("arch\tamd64\tx64\n", "arch\tamd64\tx64\narch\tamd64\tx64\n"), path -> true));
        IOException missing = assertThrows(
                IOException.class, () -> NativeProductCatalog.parse(CATALOG, path -> !path.contains("musl")));
        assertTrue(missing.getMessage().contains("missing product resource"));
    }

    @Test
    void fallsBackOnLinkageIoAndSecurityFailures() throws Exception {
        for (FailureKind failureKind : FailureKind.values()) {
            List<String> attempts = new ArrayList<>();
            NativeLibraryExtractor extractor = extractor(
                    "linux",
                    path -> {
                        if (failureKind == FailureKind.IO && path.contains("glibc")) {
                            throw new IOException("synthetic I/O failure");
                        }
                        if (failureKind == FailureKind.SECURITY && path.contains("glibc")) {
                            throw new SecurityException("synthetic security failure");
                        }
                        return new ByteArrayInputStream(new byte[] {1, 2, 3});
                    },
                    new RecordingSecurity());
            NativeLibrarySystem system = path -> {
                attempts.add(path.getFileName().toString());
                if (failureKind == FailureKind.LINKAGE && attempts.size() == 1) {
                    throw new UnsatisfiedLinkError("synthetic link failure");
                }
            };
            NativeLoadResult result =
                    new NativeLibraryLoader(catalog(), extractor, system, logger()).load("linux", "amd64");
            assertEquals("linux-musl-x64", result.product().id(), failureKind.name());
            assertEquals(1, result.failures().size(), failureKind.name());
        }
    }

    @Test
    void neverCatchesAnArbitraryError() throws Exception {
        NativeLibraryExtractor extractor =
                extractor("linux", path -> new ByteArrayInputStream(new byte[] {1}), new RecordingSecurity());
        AssertionError failure = assertThrows(
                AssertionError.class,
                () -> new NativeLibraryLoader(
                                catalog(),
                                extractor,
                                path -> {
                                    throw new AssertionError("must escape");
                                },
                                logger())
                        .load("linux", "amd64"));
        assertEquals("must escape", failure.getMessage());
    }

    @Test
    void rejectsEmptyResourcesAndReportsActionableAllCandidateFailure() throws Exception {
        NativeLibraryExtractor extractor =
                extractor("linux", path -> new ByteArrayInputStream(new byte[0]), new RecordingSecurity());
        ExceptionInInitializerError failure = assertThrows(
                ExceptionInInitializerError.class,
                () -> new NativeLibraryLoader(catalog(), extractor, path -> {}, logger()).load("linux", "amd64"));
        assertEquals(2, failure.getSuppressed().length);
        assertInstanceOf(IOException.class, failure.getSuppressed()[0]);
        assertTrue(failure.getMessage().contains("/bin/linux/glibc/linux_jni_x64.so"));
        assertTrue(failure.getMessage().contains("/bin/linux/musl/linux_jni_x64.so"));
        assertTrue(failure.getMessage().contains("a noexec mount;"));
        assertFalse(failure.getMessage().contains("unsafe extraction parent"), failure.getMessage());
        assertTrue(failure.getMessage().contains("-Dio.euhedral.native.extract.dir to a private"));
    }

    @Test
    void rejectsAResourceLargerThanTheSixtyFourMebibyteBound() throws Exception {
        NativeLibraryExtractor extractor = extractor(
                "linux",
                path -> new java.io.InputStream() {
                    private long remaining = NativeLibraryExtractor.MAX_LIBRARY_BYTES + 1;

                    @Override
                    public int read() {
                        if (remaining-- > 0) {
                            return 0;
                        }
                        return -1;
                    }

                    @Override
                    public int read(byte[] bytes, int offset, int length) {
                        if (remaining == 0) {
                            return -1;
                        }
                        int count = (int) Math.min(remaining, length);
                        remaining -= count;
                        return count;
                    }
                },
                new RecordingSecurity());
        NativeProduct product = product();
        Path destination = extractor.pathFor(product);
        IOException failure = assertThrows(IOException.class, () -> extractor.extract(product));
        assertTrue(failure.getMessage().contains(Long.toString(NativeLibraryExtractor.MAX_LIBRARY_BYTES)));
        assertFalse(Files.exists(destination));
        extractor.allCandidatesFailed();
    }

    @Test
    void posixCleansImmediatelyWhileWindowsRetainsUntilShutdown() throws Exception {
        RecordingSecurity posixSecurity = new RecordingSecurity();
        NativeLibraryExtractor posix =
                extractor("linux", path -> new ByteArrayInputStream(new byte[] {1}), posixSecurity);
        NativeLoadResult posixResult =
                new NativeLibraryLoader(catalog(), posix, path -> {}, logger()).load("linux", "amd64");
        assertFalse(Files.exists(posixResult.extractionPath()));
        assertTrue(posixSecurity.libraryCalls.get() > 0);

        RecordingSecurity windowsSecurity = new RecordingSecurity();
        NativeLibraryExtractor windows =
                extractor("windows", path -> new ByteArrayInputStream(new byte[] {1}), windowsSecurity);
        NativeLoadResult windowsResult =
                new NativeLibraryLoader(catalog(), windows, path -> {}, logger()).load("Windows 11", "amd64");
        assertTrue(Files.exists(windowsResult.extractionPath()));
        assertEquals(0, windowsSecurity.posixCalls.get(), "Windows policy must not request POSIX attributes");
        windows.allCandidatesFailed();
        assertFalse(Files.exists(windowsResult.extractionPath()));
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void staleCleanupRejectsUnsafeEntriesAndStopsAfterSixtyFourChildren() throws Exception {
        long now = Instant.parse("2026-08-01T12:00:00Z").toEpochMilli();
        Path parent = temporaryDirectory.resolve("stale-parent");
        Path base = parent.resolve(NativeLibraryExtractor.baseDirectoryName(USER));
        Files.createDirectories(base);
        Path eligible =
                staleRun(base, 90001, "11111111111111111111111111111111", now - 86_400_001L, "owner.properties", false);
        Path young = staleRun(base, 90002, "22222222222222222222222222222222", now, "owner.properties", false);
        long livePid = ProcessHandle.current().pid();
        Path live = staleRun(
                base, livePid, "33333333333333333333333333333333", now - 86_400_001L, "owner.properties", false);
        Path invalid =
                staleRun(base, 90003, "44444444444444444444444444444444", now - 86_400_001L, "bad.properties", false);
        Path unexpected =
                staleRun(base, 90004, "55555555555555555555555555555555", now - 86_400_001L, "owner.properties", true);
        Path external = temporaryDirectory.resolve("symlink-target");
        Files.createDirectories(external);
        Path symlink = base.resolve("load-90005-66666666666666666666666666666666");
        Files.createSymbolicLink(symlink, external);

        extractorAt(parent, pid -> pid == livePid).prepare(product());
        assertFalse(Files.exists(eligible));
        assertTrue(Files.exists(young));
        assertTrue(Files.exists(live));
        assertTrue(Files.exists(invalid));
        assertTrue(Files.exists(unexpected));
        assertTrue(Files.isSymbolicLink(symlink));

        Path boundedParent = temporaryDirectory.resolve("bounded-parent");
        Path boundedBase = boundedParent.resolve(NativeLibraryExtractor.baseDirectoryName(USER));
        Files.createDirectories(boundedBase);
        for (int index = 0; index < 64; index++) {
            Files.createDirectory(
                    boundedBase.resolve(String.format("load-%d-00000000000000000000000000000000", index + 1)));
        }
        Path sixtyFifth = staleRun(
                boundedBase, 99999, "ffffffffffffffffffffffffffffffff", now - 86_400_001L, "owner.properties", false);
        extractorAt(boundedParent, pid -> false).prepare(product());
        assertTrue(Files.exists(sixtyFifth), "entry 65 must not be inspected");
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static String hashedCatalog(String hash) {
        return CATALOG.replaceAll("(?m)^(product\t[^\n]*)$", "$1\t" + hash);
    }

    private static UserPrincipal principal(String name) {
        return new UserPrincipal() {
            @Override
            public String getName() {
                return name;
            }

            @Override
            public boolean equals(Object other) {
                return other instanceof UserPrincipal that && that.getName().equals(name);
            }

            @Override
            public int hashCode() {
                return name.hashCode();
            }

            @Override
            public String toString() {
                return name;
            }
        };
    }

    private static NativeFileSecurity.OwnerLookup owners(String owner, String current) {
        return new NativeFileSecurity.OwnerLookup() {
            @Override
            public UserPrincipal ownerOf(Path path) {
                return principal(owner);
            }

            @Override
            public UserPrincipal currentUser(Path probeParent) {
                return principal(current);
            }
        };
    }

    private static Path privateDirectory(Path directory) throws Exception {
        Files.createDirectory(directory);
        Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
        return directory;
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void rejectsBaseDirectoryWithLoosePermissions() throws Exception {
        Path base = temporaryDirectory.resolve(NativeLibraryExtractor.baseDirectoryName(USER));
        Files.createDirectory(base);
        Files.setPosixFilePermissions(base, PosixFilePermissions.fromString("rwxrwxrwx"));
        // Foreign owner: ownership is reported first.
        NativeLibraryExtractor foreign = extractor(
                "linux",
                path -> new ByteArrayInputStream(new byte[] {1}),
                new NativeFileSecurity.PosixSecurity(owners("mallory", "tester"), NO_PROC));
        IOException notOwned = assertThrows(IOException.class, () -> foreign.prepare(product()));
        assertTrue(notOwned.getMessage().contains("not owned by"), notOwned.getMessage());
        // Same owner, loose mode: the mode is rejected and the directory is NOT silently chmodded.
        NativeLibraryExtractor loose = extractor(
                "linux",
                path -> new ByteArrayInputStream(new byte[] {1}),
                new NativeFileSecurity.PosixSecurity(owners("tester", "tester"), NO_PROC));
        IOException modeFailure = assertThrows(IOException.class, () -> loose.prepare(product()));
        assertTrue(modeFailure.getMessage().contains("rwxrwxrwx"), modeFailure.getMessage());
        assertEquals(PosixFilePermissions.fromString("rwxrwxrwx"), Files.getPosixFilePermissions(base));
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void rejectsForeignOwnedPrivateBaseDirectory() throws Exception {
        privateDirectory(temporaryDirectory.resolve(NativeLibraryExtractor.baseDirectoryName(USER)));
        NativeLibraryExtractor extractor = extractor(
                "linux",
                path -> new ByteArrayInputStream(new byte[] {1}),
                new NativeFileSecurity.PosixSecurity(owners("mallory", "tester"), NO_PROC));
        IOException failure = assertThrows(IOException.class, () -> extractor.prepare(product()));
        assertTrue(failure.getMessage().contains("not owned by"), failure.getMessage());
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void rejectsSymbolicLinkBaseDirectory() throws Exception {
        Path target = privateDirectory(temporaryDirectory.resolve("elsewhere"));
        Files.createSymbolicLink(temporaryDirectory.resolve(NativeLibraryExtractor.baseDirectoryName(USER)), target);
        NativeLibraryExtractor extractor = extractor(
                "linux", path -> new ByteArrayInputStream(new byte[] {1}), new NativeFileSecurity.PosixSecurity());
        assertThrows(IOException.class, () -> extractor.prepare(product()));
    }

    /// Delegates attribute and verification calls to the real POSIX policy but turns every post-creation chmod into
    /// a no-op, so the observed modes can only come from the creation-time attributes.
    private static final class CreationOnlySecurity implements NativeFileSecurity {

        private final NativeFileSecurity delegate = new NativeFileSecurity.PosixSecurity();
        private final List<String> attributeRequests = new ArrayList<>();

        @Override
        public void secureDirectory(Path path) {}

        @Override
        public void secureMarker(Path path) {}

        @Override
        public void secureLibrary(Path path) {}

        @Override
        public void adoptExistingDirectory(Path directory) {}

        @Override
        public FileAttribute<?>[] directoryAttributes(Path parentDirectory) throws IOException {
            attributeRequests.add("directory");
            return delegate.directoryAttributes(parentDirectory);
        }

        @Override
        public FileAttribute<?>[] markerAttributes(Path parentDirectory) throws IOException {
            attributeRequests.add("marker");
            return delegate.markerAttributes(parentDirectory);
        }

        @Override
        public FileAttribute<?>[] libraryAttributes(Path parentDirectory) throws IOException {
            attributeRequests.add("library");
            return delegate.libraryAttributes(parentDirectory);
        }

        @Override
        public void verifyPrivateDirectory(Path directory, Path probeParent) throws IOException {
            delegate.verifyPrivateDirectory(directory, probeParent);
        }

        @Override
        public void verifyPrivateLibrary(Path library, Path probeParent) throws IOException {
            delegate.verifyPrivateLibrary(library, probeParent);
        }
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void createsRunDirectoryPrivateAtCreation() throws Exception {
        CreationOnlySecurity security = new CreationOnlySecurity();
        NativeLibraryExtractor extractor =
                extractor("linux", path -> new ByteArrayInputStream(new byte[] {1, 2, 3}), security);
        Path library = extractor.prepare(product());
        Path run = library.getParent();
        Path base = run.getParent();
        Path marker = run.resolve("owner.properties");
        Set<PosixFilePermission> directoryMode = PosixFilePermissions.fromString("rwx------");
        assertEquals(directoryMode, Files.getPosixFilePermissions(base));
        assertEquals(directoryMode, Files.getPosixFilePermissions(run));
        assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(marker));
        Path extracted = extractor.extract(product());
        assertEquals(directoryMode, Files.getPosixFilePermissions(extracted));
        assertTrue(security.attributeRequests.containsAll(List.of("directory", "marker", "library")));
        extractor.allCandidatesFailed();
    }

    @Test
    void rejectsHashMismatch() throws Exception {
        NativeProductCatalog catalog = NativeProductCatalog.parse(hashedCatalog("0".repeat(64)), path -> true);
        List<Path> loaded = new ArrayList<>();
        NativeLibraryExtractor extractor =
                extractor("linux", path -> new ByteArrayInputStream(new byte[] {1, 2, 3}), new RecordingSecurity());
        ExceptionInInitializerError failure = assertThrows(
                ExceptionInInitializerError.class,
                () -> new NativeLibraryLoader(catalog, extractor, loaded::add, logger()).load("linux", "amd64"));
        assertTrue(loaded.isEmpty(), "System.load must not be reached after a hash mismatch");
        assertEquals(2, failure.getSuppressed().length);
        IOException cause = assertInstanceOf(IOException.class, failure.getSuppressed()[0]);
        assertTrue(cause.getMessage().contains("SHA-256 mismatch"), cause.getMessage());
    }

    @Test
    void acceptsMatchingHashAndRejectsMalformedHash() throws Exception {
        byte[] content = {1, 2, 3};
        NativeProductCatalog catalog = NativeProductCatalog.parse(hashedCatalog(sha256(content)), path -> true);
        assertEquals(sha256(content), catalog.select("linux", "amd64").get(0).sha256());
        List<Path> loaded = new ArrayList<>();
        NativeLibraryExtractor extractor =
                extractor("linux", path -> new ByteArrayInputStream(content), new RecordingSecurity());
        new NativeLibraryLoader(catalog, extractor, loaded::add, logger()).load("linux", "amd64");
        assertEquals(1, loaded.size());

        assertThrows(IOException.class, () -> NativeProductCatalog.parse(hashedCatalog("ABC"), path -> true));
        assertThrows(
                IOException.class,
                () -> NativeProductCatalog.parse(hashedCatalog("A".repeat(64)), path -> true),
                "hash digits must be lowercase");
        String mixed = CATALOG.replace(
                "/bin/linux/glibc/linux_jni_x64.so\n", "/bin/linux/glibc/linux_jni_x64.so\t" + "0".repeat(64) + "\n");
        assertThrows(IOException.class, () -> NativeProductCatalog.parse(mixed, path -> true));
    }

    @Test
    void catalogWithoutHashColumnStillLoadsWithSizeCheckOnly() throws Exception {
        NativeProductCatalog catalog = catalog();
        assertEquals(null, catalog.select("linux", "amd64").get(0).sha256());
        List<Path> loaded = new ArrayList<>();
        NativeLibraryExtractor extractor =
                extractor("linux", path -> new ByteArrayInputStream(new byte[] {9, 9}), new RecordingSecurity());
        NativeLoadResult result =
                new NativeLibraryLoader(catalog, extractor, loaded::add, logger()).load("linux", "amd64");
        assertEquals("linux-glibc-x64", result.product().id());
        assertEquals(1, loaded.size());
    }

    @Test
    void perUserDirectoryNameIsSanitizedAndBounded() {
        assertEquals("euhedral-native-v1-alice", NativeLibraryExtractor.baseDirectoryName("alice"));
        assertEquals("euhedral-native-v1-a_b_c._-", NativeLibraryExtractor.baseDirectoryName("a b/c._-"));
        assertEquals("euhedral-native-v1-___", NativeLibraryExtractor.baseDirectoryName("éè\\"));
        assertEquals("euhedral-native-v1-_", NativeLibraryExtractor.baseDirectoryName(null));
        assertEquals("euhedral-native-v1-_", NativeLibraryExtractor.baseDirectoryName(""));
        assertEquals("euhedral-native-v1-" + "x".repeat(64), NativeLibraryExtractor.baseDirectoryName("x".repeat(200)));
    }

    @Test
    void extractionUsesThePerUserBaseDirectory() throws Exception {
        NativeLibraryExtractor extractor =
                extractor("linux", path -> new ByteArrayInputStream(new byte[] {1}), new RecordingSecurity());
        Path library = extractor.prepare(product());
        assertEquals(
                temporaryDirectory.resolve("euhedral-native-v1-tester"),
                library.getParent().getParent());
        extractor.allCandidatesFailed();
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void verifyBeforeLoadRejectsTamperedLibrary() throws Exception {
        byte[] content = {1, 2, 3, 4};
        NativeProduct hashed = new NativeProduct(
                "linux-glibc-x64", "linux", "x64", "glibc", 10, "/bin/linux/glibc/linux_jni_x64.so", sha256(content));
        NativeLibraryExtractor extractor =
                extractor("linux", path -> new ByteArrayInputStream(content), new NativeFileSecurity.PosixSecurity());

        Path library = extractor.extract(hashed);
        extractor.verifyBeforeLoad(hashed, library);

        Files.write(library, new byte[] {1, 2, 3, 5});
        IOException changed = assertThrows(IOException.class, () -> extractor.verifyBeforeLoad(hashed, library));
        assertTrue(changed.getMessage().contains("SHA-256"), changed.getMessage());
        extractor.allCandidatesFailed();
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void verifyBeforeLoadRejectsLooseModeHardLinkAndSymlink() throws Exception {
        byte[] content = {1, 2, 3, 4};
        NativeProduct hashed = new NativeProduct(
                "linux-glibc-x64", "linux", "x64", "glibc", 10, "/bin/linux/glibc/linux_jni_x64.so", sha256(content));
        NativeLibraryExtractor extractor =
                extractor("linux", path -> new ByteArrayInputStream(content), new NativeFileSecurity.PosixSecurity());
        Path library = extractor.extract(hashed);

        Files.setPosixFilePermissions(library, PosixFilePermissions.fromString("rwxrwx---"));
        IOException loose = assertThrows(IOException.class, () -> extractor.verifyBeforeLoad(hashed, library));
        assertTrue(loose.getMessage().contains("rwxrwx---"), loose.getMessage());
        Files.setPosixFilePermissions(library, PosixFilePermissions.fromString("rwx------"));

        Path link = library.getParent().resolve("extra-link");
        Files.createLink(link, library);
        IOException hardLink = assertThrows(IOException.class, () -> extractor.verifyBeforeLoad(hashed, library));
        assertTrue(hardLink.getMessage().contains("hard link"), hardLink.getMessage());
        Files.delete(link);
        extractor.verifyBeforeLoad(hashed, library);

        Path copy = temporaryDirectory.resolve("copy.so");
        Files.copy(library, copy);
        Files.delete(library);
        Files.createSymbolicLink(library, copy);
        assertThrows(IOException.class, () -> extractor.verifyBeforeLoad(hashed, library));
        Files.delete(library);
        extractor.allCandidatesFailed();
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void verifyBeforeLoadRejectsForeignOwnedRunDirectory() throws Exception {
        AtomicReference<String> runOwner = new AtomicReference<>("tester");
        NativeFileSecurity.OwnerLookup lookup = new NativeFileSecurity.OwnerLookup() {
            @Override
            public UserPrincipal ownerOf(Path path) {
                // Foreign only for the run directory; the base directory and library stay ours.
                boolean run = path.getFileName().toString().startsWith("load-");
                return principal(run ? runOwner.get() : "tester");
            }

            @Override
            public UserPrincipal currentUser(Path probeParent) {
                return principal("tester");
            }
        };
        NativeLibraryExtractor extractor = extractor(
                "linux",
                path -> new ByteArrayInputStream(new byte[] {1}),
                new NativeFileSecurity.PosixSecurity(lookup, NO_PROC));
        Path library = extractor.extract(product());
        extractor.verifyBeforeLoad(product(), library);
        runOwner.set("mallory");
        IOException failure = assertThrows(IOException.class, () -> extractor.verifyBeforeLoad(product(), library));
        assertTrue(failure.getMessage().contains("not owned by"), failure.getMessage());
        assertTrue(failure.getMessage().contains("load-"), failure.getMessage());
        runOwner.set("tester");
        extractor.allCandidatesFailed();
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void verifyBeforeLoadRejectsLooseRunDirectoryMode() throws Exception {
        NativeLibraryExtractor extractor = extractor(
                "linux", path -> new ByteArrayInputStream(new byte[] {1}), new NativeFileSecurity.PosixSecurity());
        Path library = extractor.extract(product());
        Path run = library.getParent();
        Files.setPosixFilePermissions(run, PosixFilePermissions.fromString("rwxr-xr-x"));
        IOException failure = assertThrows(IOException.class, () -> extractor.verifyBeforeLoad(product(), library));
        assertTrue(failure.getMessage().contains("rwxr-xr-x"), failure.getMessage());
        Files.setPosixFilePermissions(run, PosixFilePermissions.fromString("rwx------"));
        extractor.allCandidatesFailed();
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void rejectsSharedWritableParentWithoutStickyBit() throws Exception {
        for (String mode : List.of("rwxrwxrwx", "rwxrwx---")) {
            Path shared = temporaryDirectory.resolve("shared-" + mode);
            Files.createDirectory(shared);
            Files.setPosixFilePermissions(shared, PosixFilePermissions.fromString(mode));
            NativeLibraryExtractor extractor = posixExtractorAt(shared, true);
            IOException failure = assertThrows(IOException.class, () -> extractor.prepare(product()), mode);
            assertTrue(failure.getMessage().contains(shared.toString()), failure.getMessage());
            assertTrue(failure.getMessage().contains("sticky"), failure.getMessage());
        }
    }

    @Test
    void rejectedParentIsPreparedOnceAcrossCandidates() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        NativeFileSecurity rejecting = new NativeFileSecurity() {
            @Override
            public void secureDirectory(Path path) {}

            @Override
            public void secureMarker(Path path) {}

            @Override
            public void secureLibrary(Path path) {}

            @Override
            public void verifySafeParent(Path parent) throws IOException {
                attempts.incrementAndGet();
                throw new VerificationException("native-loader: unsafe parent " + parent);
            }
        };
        NativeLibraryExtractor extractor = extractorWith(temporaryDirectory, rejecting, true, logger());
        ExceptionInInitializerError failure = assertThrows(
                ExceptionInInitializerError.class,
                () -> new NativeLibraryLoader(catalog(), extractor, path -> {}, logger()).load("linux", "amd64"));
        assertEquals(2, failure.getSuppressed().length, "both candidates must still fail");
        assertEquals(1, attempts.get(), "the rejected parent must be prepared exactly once");
        assertInstanceOf(NativeFileSecurity.VerificationException.class, failure.getSuppressed()[0]);
        assertSame(failure.getSuppressed()[0], failure.getSuppressed()[1].getCause());
        assertTrue(failure.getMessage().contains("unsafe extraction parent"), failure.getMessage());
        assertTrue(failure.getMessage().contains("strict mode"), failure.getMessage());
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void ownerUidAboveIntMaxIsUnsigned() throws Exception {
        assertEquals(2147483648L, NativeFileSecurity.UnixIds.unsignedUid(Integer.MIN_VALUE));
        assertEquals(4294967295L, NativeFileSecurity.UnixIds.unsignedUid(-1));
        assertEquals(1000L, NativeFileSecurity.UnixIds.unsignedUid(1000));
        assertEquals(2147483648L, NativeFileSecurity.UnixIds.unsignedUid(2147483648L));
        Path parent = temporaryDirectory.resolve("high-uid");
        Files.createDirectory(parent);
        Files.setPosixFilePermissions(parent, PosixFilePermissions.fromString("rwx------"));
        // Owner uid 2^31 equals effective uid 2147483648: accepted, not seen as negative.
        extractorWithIds(parent, ids(2147483648L, 0x8000_0000L), true).prepare(product());
    }

    /// `/proc/self/status` unreadable (macOS): ownership falls back to the probe identity.
    private static final NativeFileSecurity.UnixIds NO_PROC = ids(-1, 0);

    private static NativeFileSecurity.UnixIds idsBy(long effective, java.util.function.ToLongFunction<Path> owners) {
        return new NativeFileSecurity.UnixIds() {
            @Override
            public long effectiveUid() {
                return effective;
            }

            @Override
            public long ownerUid(Path path) {
                return owners.applyAsLong(path);
            }
        };
    }

    private static NativeFileSecurity.UnixIds ids(long effective, long owner) {
        return new NativeFileSecurity.UnixIds() {
            @Override
            public long effectiveUid() {
                return effective;
            }

            @Override
            public long ownerUid(Path path) {
                return owner;
            }
        };
    }

    private NativeLibraryExtractor extractorWithIds(Path parent, NativeFileSecurity.UnixIds ids, boolean strict) {
        return extractorWith(
                parent,
                new NativeFileSecurity.PosixSecurity(NativeFileSecurity.OwnerLookup.DEFAULT, ids),
                strict,
                logger());
    }

    private NativeLibraryExtractor extractorWith(
            Path parent, NativeFileSecurity security, boolean strict, org.slf4j.Logger log) {
        return new NativeLibraryExtractor(
                "linux",
                parent,
                security,
                path -> new ByteArrayInputStream(new byte[] {1}),
                Clock.fixed(Instant.parse("2026-08-01T12:00:00Z"), ZoneOffset.UTC),
                pid -> false,
                new SecureRandom(new byte[] {5, 6, 7, 8}),
                424242,
                USER,
                strict,
                log);
    }

    private Path stickyParent(String name) throws Exception {
        Path shared = temporaryDirectory.resolve(name);
        Files.createDirectory(shared);
        Files.setPosixFilePermissions(shared, PosixFilePermissions.fromString("rwxrwxrwx"));
        assertEquals(
                0,
                new ProcessBuilder("chmod", "1777", shared.toString())
                        .inheritIO()
                        .start()
                        .waitFor());
        assertTrue(((Integer) Files.getAttribute(shared, "unix:mode") & 01000) != 0, "sticky bit must be set");
        return shared;
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void acceptsStickySharedParentOwnedByRoot() throws Exception {
        Path shared = stickyParent("sticky-root");
        NativeLibraryExtractor extractor =
                extractorWithIds(shared, idsBy(1000, path -> path.equals(shared) ? 0 : 1000), true);
        Path library = extractor.prepare(product());
        assertTrue(library.startsWith(shared));
        extractor.allCandidatesFailed();
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void rejectsStickySharedParentOwnedByANonRootUser() throws Exception {
        Path shared = stickyParent("sticky-user");
        // Even when the owner is the current user: a sticky directory's owner can rename entries and clear the bit.
        IOException failure = assertThrows(
                IOException.class,
                () -> extractorWithIds(shared, ids(1000, 1000), true).prepare(product()));
        assertInstanceOf(NativeFileSecurity.VerificationException.class, failure);
        assertTrue(failure.getMessage().contains(shared.toString()), failure.getMessage());
        assertTrue(failure.getMessage().contains("uid 1000"), failure.getMessage());
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void privateParentMustBeOwnedByTheCurrentUidOrRoot() throws Exception {
        Path own = privateDirectory(temporaryDirectory.resolve("own"));
        NativeLibraryExtractor accepted = extractorWithIds(own, ids(1000, 1000), true);
        accepted.prepare(product());
        accepted.allCandidatesFailed();

        Path foreign = privateDirectory(temporaryDirectory.resolve("foreign"));
        IOException failure = assertThrows(
                IOException.class,
                () -> extractorWithIds(foreign, ids(1000, 2000), true).prepare(product()));
        assertInstanceOf(NativeFileSecurity.VerificationException.class, failure);
        assertTrue(failure.getMessage().contains("uid 2000"), failure.getMessage());
        assertTrue(failure.getMessage().contains("-Dio.euhedral.native.extract.dir"), failure.getMessage());
    }

    // ---- relaxed (default) parent policy ----

    private static org.slf4j.Logger mockLogger() {
        return org.mockito.Mockito.mock(org.slf4j.Logger.class);
    }

    private static long warnCount(org.slf4j.Logger log) {
        return org.mockito.Mockito.mockingDetails(log).getInvocations().stream()
                .filter(invocation -> invocation.getMethod().getName().equals("warn"))
                .count();
    }

    private static NativeFileSecurity.OwnerLookup identity(
            String owner, String probe, String userName, String resolvedUser) {
        return new NativeFileSecurity.OwnerLookup() {
            @Override
            public UserPrincipal ownerOf(Path path) {
                return principal(owner);
            }

            @Override
            public UserPrincipal currentUser(Path probeParent) {
                return principal(probe);
            }

            @Override
            public String userName() {
                return userName;
            }

            @Override
            public UserPrincipal lookupUser(String name) throws IOException {
                if (resolvedUser == null) {
                    throw new IOException("no such user " + name);
                }
                return principal(resolvedUser);
            }
        };
    }

    private Path parentWithMode(String name, String chmodMode) throws Exception {
        Path directory = temporaryDirectory.resolve(name);
        Files.createDirectory(directory);
        assertEquals(
                0,
                new ProcessBuilder("chmod", chmodMode, directory.toString())
                        .inheritIO()
                        .start()
                        .waitFor());
        return directory;
    }

    private NativeLibraryExtractor relaxedExtractor(
            Path parent, NativeFileSecurity.UnixIds ids, NativeFileSecurity.OwnerLookup lookup, org.slf4j.Logger log) {
        return extractorWith(parent, new NativeFileSecurity.PosixSecurity(lookup, ids), false, log);
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void relaxedModeLoadsFromANonStickyWorldWritableParentAndWarnsOnce() throws Exception {
        Path shared = parentWithMode("relaxed-0777", "0777");
        org.slf4j.Logger log = mockLogger();
        NativeLibraryExtractor extractor =
                relaxedExtractor(shared, ids(1000, 1000), identity("tester", "tester", "tester", "tester"), log);
        List<Path> loaded = new ArrayList<>();
        // Both candidates are attempted (the first fails at System.load); the parent is assessed once.
        NativeLibrarySystem failFirst = path -> {
            loaded.add(path);
            if (loaded.size() == 1) {
                throw new UnsatisfiedLinkError("first candidate fails");
            }
        };
        NativeLoadResult result = new NativeLibraryLoader(catalog(), extractor, failFirst, log).load("linux", "amd64");
        assertEquals(2, loaded.size(), "both candidates must reach System.load");
        assertTrue(result.extractionPath().startsWith(shared));
        assertEquals(1, warnCount(log), "exactly one WARN per process");
        var warning = org.mockito.Mockito.mockingDetails(log).getInvocations().stream()
                .filter(invocation -> invocation.getMethod().getName().equals("warn"))
                .findFirst()
                .orElseThrow();
        String text =
                warning.getRawArguments()[0] + " " + java.util.Arrays.toString((Object[]) warning.getRawArguments()[1]);
        assertTrue(text.contains(shared.toString()), text);
        assertTrue(text.contains("0777"), text);
        assertTrue(text.contains("1000"), text);
        assertTrue(text.contains("swap the extracted library"), text);
        assertTrue(text.contains(NativeLibraryExtractor.STRICT_PARENT_PROPERTY), text);
        assertTrue(text.contains("-Dio.euhedral.native.extract.dir"), text);
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void relaxedModeAcceptsGroupWritableParentOwnedByTheCurrentUser() throws Exception {
        Path group = parentWithMode("relaxed-0775", "0775");
        org.slf4j.Logger log = mockLogger();
        NativeLibraryExtractor extractor =
                relaxedExtractor(group, ids(1000, 1000), identity("tester", "tester", "tester", "tester"), log);
        assertTrue(extractor.prepare(product()).startsWith(group));
        assertEquals(1, warnCount(log));
        extractor.allCandidatesFailed();
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void relaxedModeAcceptsStickyParentOwnedByUid65534() throws Exception {
        Path sticky = parentWithMode("relaxed-sticky-65534", "1777");
        org.slf4j.Logger log = mockLogger();
        NativeLibraryExtractor extractor = relaxedExtractor(
                sticky,
                idsBy(1000, path -> path.equals(sticky) ? 65534 : 1000),
                identity("tester", "tester", "tester", "tester"),
                log);
        assertTrue(extractor.prepare(product()).startsWith(sticky));
        assertEquals(1, warnCount(log));
        extractor.allCandidatesFailed();
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void relaxedModeStaysQuietForASafeParent() throws Exception {
        Path own = privateDirectory(temporaryDirectory.resolve("relaxed-safe"));
        org.slf4j.Logger log = mockLogger();
        NativeLibraryExtractor extractor =
                relaxedExtractor(own, ids(1000, 1000), identity("tester", "tester", "tester", "tester"), log);
        extractor.prepare(product());
        assertEquals(0, warnCount(log));
        extractor.allCandidatesFailed();
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void strictModeRejectsTheSameParentsAsBefore() throws Exception {
        record Case(String name, String chmod, long owner, String expected) {}
        for (Case parentCase : List.of(
                new Case("strict-0777", "0777", 1000, "without the sticky bit"),
                new Case("strict-0775", "0775", 1000, "without the sticky bit"),
                new Case("strict-sticky-65534", "1777", 65534, "uid 65534"))) {
            Path parent = parentWithMode(parentCase.name(), parentCase.chmod());
            org.slf4j.Logger log = mockLogger();
            NativeLibraryExtractor extractor = extractorWith(
                    parent,
                    new NativeFileSecurity.PosixSecurity(
                            identity("tester", "tester", "tester", "tester"), ids(1000, parentCase.owner())),
                    true,
                    log);
            IOException failure =
                    assertThrows(IOException.class, () -> extractor.prepare(product()), parentCase.name());
            assertInstanceOf(NativeFileSecurity.VerificationException.class, failure);
            assertTrue(failure.getMessage().contains(parentCase.expected()), failure.getMessage());
            assertEquals(0, warnCount(log), "strict mode rejects instead of warning");
            assertTrue(extractor.parentRejectedByStrictMode());
        }
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void relaxedModeRejectsAProbeIdentityThatDisagreesWithTheUserDatabase() throws Exception {
        Path shared = parentWithMode("relaxed-spoof", "0777");
        // The probe in the unsafe parent says "mallory" while user.name resolves to "tester".
        NativeLibraryExtractor mismatch =
                relaxedExtractor(shared, NO_PROC, identity("mallory", "mallory", "tester", "tester"), mockLogger());
        IOException failure = assertThrows(IOException.class, () -> mismatch.prepare(product()));
        assertInstanceOf(NativeFileSecurity.VerificationException.class, failure);
        assertTrue(failure.getMessage().contains("refusing to trust"), failure.getMessage());

        // A failed lookup fails closed instead of falling back to the probe.
        Path other = parentWithMode("relaxed-lookup-fails", "0777");
        NativeLibraryExtractor unresolved =
                relaxedExtractor(other, NO_PROC, identity("tester", "tester", "tester", null), mockLogger());
        IOException lookupFailure = assertThrows(IOException.class, () -> unresolved.prepare(product()));
        assertInstanceOf(NativeFileSecurity.VerificationException.class, lookupFailure);
        assertTrue(lookupFailure.getMessage().contains("lookup of user 'tester' failed"), lookupFailure.getMessage());

        // An unset user name fails closed too.
        Path unnamed = parentWithMode("relaxed-no-name", "0777");
        NativeLibraryExtractor noName =
                relaxedExtractor(unnamed, NO_PROC, identity("tester", "tester", null, "tester"), mockLogger());
        assertThrows(NativeFileSecurity.VerificationException.class, () -> noName.prepare(product()));
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void relaxedModeDoesNotCrossCheckTheProbeForASafeParent() throws Exception {
        Path own = privateDirectory(temporaryDirectory.resolve("relaxed-safe-nolookup"));
        // user.name cannot be resolved, but the parent is safe, so the probe identity is used as before.
        NativeLibraryExtractor extractor =
                relaxedExtractor(own, ids(-1, 1000), identity("tester", "tester", "tester", null), mockLogger());
        extractor.prepare(product());
        extractor.allCandidatesFailed();
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void relaxedModeKeepsRunDirectoryAndHashChecksStrict() throws Exception {
        Path shared = parentWithMode("relaxed-strict-rest", "0777");
        NativeFileSecurity.OwnerLookup lookup = new NativeFileSecurity.OwnerLookup() {
            @Override
            public UserPrincipal ownerOf(Path path) throws IOException {
                return NativeFileSecurity.OwnerLookup.DEFAULT.ownerOf(path);
            }

            @Override
            public UserPrincipal currentUser(Path probeParent) throws IOException {
                return NativeFileSecurity.OwnerLookup.DEFAULT.currentUser(probeParent);
            }
        };
        NativeLibraryExtractor extractor = relaxedExtractor(shared, ids(1000, 1000), lookup, mockLogger());
        Path library = extractor.extract(product());
        Path run = library.getParent();
        extractor.verifyBeforeLoad(product(), library);
        Files.setPosixFilePermissions(run, PosixFilePermissions.fromString("rwxr-xr-x"));
        IOException loose = assertThrows(IOException.class, () -> extractor.verifyBeforeLoad(product(), library));
        assertTrue(loose.getMessage().contains("rwxr-xr-x"), loose.getMessage());
        Files.setPosixFilePermissions(run, PosixFilePermissions.fromString("rwx------"));
        Files.write(library, new byte[] {9});
        IOException tampered = assertThrows(IOException.class, () -> extractor.verifyBeforeLoad(product(), library));
        assertTrue(tampered.getMessage().contains("SHA-256"), tampered.getMessage());
        extractor.allCandidatesFailed();

        NativeProductCatalog hashed = NativeProductCatalog.parse(hashedCatalog("0".repeat(64)), path -> true);
        NativeLibraryExtractor mismatch = relaxedExtractor(shared, ids(1000, 1000), lookup, mockLogger());
        List<Path> loaded = new ArrayList<>();
        ExceptionInInitializerError failure = assertThrows(
                ExceptionInInitializerError.class,
                () -> new NativeLibraryLoader(hashed, mismatch, loaded::add, mockLogger()).load("linux", "amd64"));
        assertTrue(loaded.isEmpty());
        assertTrue(
                java.util.Arrays.stream(failure.getSuppressed())
                        .anyMatch(cause -> cause.getMessage().contains("SHA-256 mismatch")),
                "the hash mismatch must be the recorded cause");
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void relaxedModeUsesTheProcUidSoAnUnresolvableUserNameDoesNotBlockTheLoad() throws Exception {
        // Arbitrary container uid: user.name is unresolvable, /proc is readable.
        Path shared = parentWithMode("relaxed-no-passwd", "0777");
        NativeLibraryExtractor extractor =
                relaxedExtractor(shared, ids(4242, 4242), identity("nobody", "nobody", "?", null), mockLogger());
        Path library = extractor.prepare(product());
        assertTrue(library.startsWith(shared));
        extractor.allCandidatesFailed();
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void relaxedModeProcUidStillRejectsForeignBaseRunAndLibraryOwners() throws Exception {
        java.util.concurrent.atomic.AtomicReference<String> foreignName =
                new java.util.concurrent.atomic.AtomicReference<>();
        Path shared = parentWithMode("relaxed-proc-owners", "0777");
        NativeFileSecurity.UnixIds byName = idsBy(
                4242,
                path -> path.equals(shared) || !path.getFileName().toString().equals(foreignName.get()) ? 4242 : 7);
        NativeFileSecurity.OwnerLookup throwing = identity("x", "x", "?", null);
        NativeLibraryExtractor extractor = relaxedExtractor(shared, byName, throwing, mockLogger());
        extractor.prepare(product());
        Path library = extractor.extract(product());
        Path run = library.getParent();
        Path base = run.getParent();
        extractor.verifyBeforeLoad(product(), library);
        for (Path victim : List.of(library, run, base)) {
            foreignName.set(victim.getFileName().toString());
            IOException failure = assertThrows(IOException.class, () -> extractor.verifyBeforeLoad(product(), library));
            assertInstanceOf(NativeFileSecurity.VerificationException.class, failure);
            assertTrue(failure.getMessage().contains("owner uid=7"), failure.getMessage());
        }
        foreignName.set(null);
        extractor.allCandidatesFailed();

        // A foreign base directory is already rejected by prepare.
        Path other = parentWithMode("relaxed-proc-base", "0777");
        NativeLibraryExtractor foreignBase =
                relaxedExtractor(other, idsBy(4242, path -> path.equals(other) ? 4242 : 7), throwing, mockLogger());
        privateDirectory(other.resolve(NativeLibraryExtractor.baseDirectoryName(USER)));
        IOException baseFailure = assertThrows(IOException.class, () -> foreignBase.prepare(product()));
        assertTrue(baseFailure.getMessage().contains("not owned by"), baseFailure.getMessage());
    }

    @Test
    void effectiveUidIsParsedFromProcStatus() throws Exception {
        String status = "Name:\tjava\nUmask:\t0022\nUid:\t1000\t1001\t1002\t1003\nGid:\t5\t6\t7\t8\n";
        assertEquals(1001L, NativeFileSecurity.UnixIds.parseEffectiveUid(status));
        assertThrows(IOException.class, () -> NativeFileSecurity.UnixIds.parseEffectiveUid("Name:\tjava\n"));
        assertThrows(IOException.class, () -> NativeFileSecurity.UnixIds.parseEffectiveUid("Uid:\t1000\n"));
    }

    @Test
    void hashMismatchSurfacesAsVerificationException() throws Exception {
        NativeProduct wrong = new NativeProduct(
                "linux-glibc-x64", "linux", "x64", "glibc", 10, "/bin/linux/glibc/linux_jni_x64.so", "0".repeat(64));
        NativeLibraryExtractor extractor =
                extractor("linux", path -> new ByteArrayInputStream(new byte[] {1}), new RecordingSecurity());
        assertThrows(NativeFileSecurity.VerificationException.class, () -> extractor.extract(wrong));
        extractor.allCandidatesFailed();
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void failsClosedWhenOwnerLookupThrows() throws Exception {
        NativeFileSecurity.OwnerLookup broken = new NativeFileSecurity.OwnerLookup() {
            @Override
            public UserPrincipal ownerOf(Path path) throws IOException {
                throw new IOException("lookup unavailable");
            }

            @Override
            public UserPrincipal currentUser(Path probeParent) {
                return principal("tester");
            }
        };
        NativeLibraryExtractor extractor = extractor(
                "linux",
                path -> new ByteArrayInputStream(new byte[] {1}),
                new NativeFileSecurity.PosixSecurity(broken, NO_PROC));
        assertThrows(IOException.class, () -> extractor.prepare(product()));
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void realOwnerLookupAcceptsADirectoryThisProcessCreated() throws Exception {
        NativeLibraryExtractor extractor = extractor(
                "linux", path -> new ByteArrayInputStream(new byte[] {1}), new NativeFileSecurity.PosixSecurity());
        Path library = extractor.prepare(product());
        // A second extractor re-adopts the now pre-existing per-user base directory.
        NativeLibraryExtractor second = extractor(
                "linux", path -> new ByteArrayInputStream(new byte[] {1}), new NativeFileSecurity.PosixSecurity());
        second.prepare(product());
        second.allCandidatesFailed();
        extractor.allCandidatesFailed();
        assertTrue(library.getParent().getParent().getFileName().toString().endsWith("-tester"));
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void staleCleanupFiltersBeforeApplyingTheEntryLimit() throws Exception {
        long now = Instant.parse("2026-08-01T12:00:00Z").toEpochMilli();
        Path parent = temporaryDirectory.resolve("filter-parent");
        Path base = parent.resolve(NativeLibraryExtractor.baseDirectoryName(USER));
        Files.createDirectories(base);
        for (int index = 0; index < 100; index++) {
            Files.createDirectory(base.resolve(String.format("aaa-unrelated-%03d", index)));
        }
        Path stale =
                staleRun(base, 99999, "ffffffffffffffffffffffffffffffff", now - 86_400_001L, "owner.properties", false);
        extractorAt(parent, pid -> false).prepare(product());
        assertFalse(Files.exists(stale), "unrelated entries must not consume the inspection budget");
    }

    private NativeProductCatalog catalog() throws IOException {
        return NativeProductCatalog.parse(CATALOG, path -> true);
    }

    private NativeLibraryExtractor extractor(
            String operatingSystem, NativeLibraryExtractor.ResourceInput resources, NativeFileSecurity security) {
        return new NativeLibraryExtractor(
                operatingSystem,
                temporaryDirectory,
                security,
                resources,
                Clock.fixed(Instant.parse("2026-08-01T12:00:00Z"), ZoneOffset.UTC),
                pid -> false,
                new SecureRandom(new byte[] {1, 2, 3, 4}),
                424242,
                USER,
                false,
                logger());
    }

    private NativeLibraryExtractor posixExtractorAt(Path parent, boolean strict) {
        return new NativeLibraryExtractor(
                "linux",
                parent,
                new NativeFileSecurity.PosixSecurity(),
                path -> new ByteArrayInputStream(new byte[] {1}),
                Clock.fixed(Instant.parse("2026-08-01T12:00:00Z"), ZoneOffset.UTC),
                pid -> false,
                new SecureRandom(new byte[] {5, 6, 7, 8}),
                424242,
                USER,
                strict,
                logger());
    }

    private NativeLibraryExtractor extractorAt(Path parent, java.util.function.LongPredicate processAlive) {
        return new NativeLibraryExtractor(
                "linux",
                parent,
                new RecordingSecurity(),
                path -> new ByteArrayInputStream(new byte[] {1}),
                Clock.fixed(Instant.parse("2026-08-01T12:00:00Z"), ZoneOffset.UTC),
                processAlive,
                new SecureRandom(new byte[] {5, 6, 7, 8}),
                424242,
                USER,
                false,
                logger());
    }

    private enum FailureKind {
        LINKAGE,
        IO,
        SECURITY
    }

    private static final class RecordingSecurity implements NativeFileSecurity {

        private final AtomicInteger posixCalls = new AtomicInteger();
        private final AtomicInteger libraryCalls = new AtomicInteger();

        @Override
        public void secureDirectory(Path path) {}

        @Override
        public void secureMarker(Path path) {}

        @Override
        public void secureLibrary(Path path) {
            libraryCalls.incrementAndGet();
        }
    }
}
