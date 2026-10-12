package io.euhedral_execution.hardware_utils.nativelib;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
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
        assertTrue(failure.getMessage().contains("noexec mount is a possible cause"));
        assertTrue(failure.getMessage().contains("-Dio.euhedral.native.extract.dir="));
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
                new NativeFileSecurity.PosixSecurity(owners("mallory", "tester")));
        IOException notOwned = assertThrows(IOException.class, () -> foreign.prepare(product()));
        assertTrue(notOwned.getMessage().contains("not owned by"), notOwned.getMessage());
        // Same owner, loose mode: the mode is rejected and the directory is NOT silently chmodded.
        NativeLibraryExtractor loose = extractor(
                "linux",
                path -> new ByteArrayInputStream(new byte[] {1}),
                new NativeFileSecurity.PosixSecurity(owners("tester", "tester")));
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
                new NativeFileSecurity.PosixSecurity(owners("mallory", "tester")));
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
                new NativeFileSecurity.PosixSecurity(lookup));
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
            NativeLibraryExtractor extractor = posixExtractorAt(shared);
            IOException failure = assertThrows(IOException.class, () -> extractor.prepare(product()), mode);
            assertTrue(failure.getMessage().contains(shared.toString()), failure.getMessage());
            assertTrue(failure.getMessage().contains("sticky"), failure.getMessage());
        }
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void acceptsStickySharedParent() throws Exception {
        Path shared = temporaryDirectory.resolve("sticky");
        Files.createDirectory(shared);
        Files.setPosixFilePermissions(shared, PosixFilePermissions.fromString("rwxrwxrwx"));
        new ProcessBuilder("chmod", "1777", shared.toString())
                .inheritIO()
                .start()
                .waitFor();
        assertTrue(((Integer) Files.getAttribute(shared, "unix:mode") & 01000) != 0, "sticky bit must be set");
        NativeLibraryExtractor extractor = posixExtractorAt(shared);
        Path library = extractor.prepare(product());
        assertTrue(library.startsWith(shared));
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
                new NativeFileSecurity.PosixSecurity(broken));
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
                logger());
    }

    private NativeLibraryExtractor posixExtractorAt(Path parent) {
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
